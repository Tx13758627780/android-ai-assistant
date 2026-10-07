package dev.phoneagent.core

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Durable, serial execution; dispatch alone never marks a step complete. */
class TaskEngine(
    private val planner: Planner,
    private val gateway: DeviceGateway,
    private val store: SessionStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val approvalTtlMs: Long = 120_000
) {
    private val validator = PlanValidator()
    private val safety = SafetyPolicy()
    private val mutex = Mutex()
    private val active = ConcurrentHashMap<String, Job>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val mutableState = MutableStateFlow<Session?>(null)
    val state: StateFlow<Session?> = mutableState.asStateFlow()

    suspend fun start(task: String, initialMemory: Map<String, String> = emptyMap()): Session {
        require(task.isNotBlank() && task.length <= 8_000) { "Task must contain 1–8000 characters" }
        require(initialMemory.keys.none { it.startsWith("_engine.") }) { "Runtime context cannot override engine control state" }
        val session = Session(id = UUID.randomUUID().toString(), task = task.trim(), memory = initialMemory, updatedAt = now())
        return serialized(session.id) {
            persist(session.copy(status = SessionStatus.PLANNING, message = "Planning task"))
            val observation = gateway.observe(withScreenshot = true)
            checkActive(session.id)
            val plan = validator.validate(planner.plan(session.task, observation, session.memory))
            checkActive(session.id)
            val planned = persist(current(session.id).copy(status = SessionStatus.RUNNING, plan = plan, message = "Executing verified steps"))
            runPlan(planned)
        }
    }

    suspend fun resume(id: String): Session = serialized(id) {
        var session = requireNotNull(store.load(id)) { "Unknown session" }
        mutableState.value = session
        if (session.status in terminalStates || session.status == SessionStatus.AWAITING_CONFIRMATION) return@serialized session
        val plan = session.plan ?: return@serialized persist(session.copy(status = SessionStatus.PAUSED, message = "Planning was interrupted. Start a new task to plan again."))
        validator.validate(plan)
        val inFlight = session.memory[IN_FLIGHT]
        if (inFlight != null) {
            val action = Json.decodeFromString<Action>(inFlight)
            validator.validateAction(action)
            val step = plan.steps.getOrNull(session.nextStep) ?: throw IllegalArgumentException("Invalid persisted step position")
            val observation = gateway.observe(withScreenshot = isUi(action))
            val evidence = gateway.verify(step.verification, observation, ExecutionResult(false, "Recovering an interrupted operation", retryable = false))
            if (evidence.satisfied && evidence.evidence.isNotBlank()) {
                session = persist(session.copy(nextStep = session.nextStep + 1, trace = session.trace + TraceEntry(step.id, action, session.memory[PENDING_ATTEMPT]?.toIntOrNull() ?: 1, "Recovered without redispatch; ${evidence.evidence}", true, now()), memory = clearStepMemory(session.memory) + mapOf("last_package" to observation.packageName, "last_evidence" to evidence.evidence), message = evidence.evidence))
            } else if (!safety.isSafelyRepeatable(action)) {
                return@serialized persist(session.copy(status = SessionStatus.PAUSED, approval = null, message = "An interrupted operation may already have taken effect. It will not be repeated; verify its outcome in the app before resuming."))
            } else {
                val nextAttempt = (session.memory[PENDING_ATTEMPT]?.toIntOrNull() ?: 1) + 1
                session = persist(session.copy(memory = session.memory.minus(IN_FLIGHT) + (PENDING_ATTEMPT to nextAttempt.toString())))
            }
        }
        runPlan(persist(session.copy(status = SessionStatus.RUNNING, approval = null)))
    }

    suspend fun approve(id: String, token: String): Session = serialized(id) {
        val session = requireNotNull(store.load(id)) { "Unknown session" }
        mutableState.value = session
        val approval = session.approval
        if (session.status != SessionStatus.AWAITING_CONFIRMATION || approval == null) return@serialized session
        if (!constantTimeEquals(approval.token, token)) return@serialized persist(session.copy(message = "Invalid approval token; no action was executed."))
        val step = session.plan?.steps?.getOrNull(session.nextStep)
        require(step?.id == approval.stepId) { "Approval is not bound to the current step" }
        require(session.memory[APPROVAL_ACTION_HASH] == safety.actionFingerprint(approval.action)) { "Approval action binding is invalid" }
        validator.validateAction(approval.action)
        val observation = gateway.observe(withScreenshot = isUi(approval.action))
        checkActive(id)
        if (now() - approval.createdAt !in 0..approvalTtlMs || (isUi(approval.action) && safety.fingerprint(observation) != approval.observationFingerprint)) {
            val action = refreshGrounded(approval.action, observation)
            return@serialized requestApproval(session, step!!, action, observation, "The screen changed or approval expired. Confirm the fresh operation.", session.memory[PENDING_ALT]?.toIntOrNull() ?: 0, session.memory[PENDING_ATTEMPT]?.toIntOrNull() ?: 1)
        }
        safety.validateUiTarget(approval.action, observation)
        val permit = Permit(approval.stepId, approval.action, approval.observationFingerprint, approval.createdAt + approvalTtlMs)
        runPlan(persist(session.copy(status = SessionStatus.RUNNING, approval = null, message = "Exact operation approved")), permit)
    }

    suspend fun reject(id: String): Session = serialized(id) {
        val session = requireNotNull(store.load(id)) { "Unknown session" }
        if (session.status != SessionStatus.AWAITING_CONFIRMATION || session.approval == null) return@serialized session
        persist(session.copy(status = SessionStatus.CANCELLED, approval = null, message = "Sensitive operation rejected", memory = clearApprovalMemory(session.memory)))
    }

    /** Cancellation does not wait for a blocked gateway before signalling its coroutine. */
    suspend fun cancel(id: String): Session {
        cancelled += id
        active[id]?.cancel(CancellationException("Task cancelled by user"))
        return mutex.withLock {
            val session = requireNotNull(store.load(id)) { "Unknown session" }
            if (session.status in terminalStates) {
                cancelled.remove(id)
                return@withLock session
            }
            persist(session.copy(status = SessionStatus.CANCELLED, approval = null, message = "Task cancelled", memory = clearApprovalMemory(session.memory)))
        }
    }

    private suspend fun runPlan(initial: Session, initialPermit: Permit? = null): Session {
        var session = initial
        var permit = initialPermit
        var plan = requireNotNull(session.plan)
        require(session.nextStep in 0..plan.steps.size) { "Invalid persisted step position" }
        while (session.nextStep < plan.steps.size) {
            checkActive(session.id)
            val step = plan.steps[session.nextStep]
            var completed = false
            var lastFailure = "No supported executor available"
            val startAlternative = session.memory[PENDING_ALT]?.toIntOrNull() ?: 0
            require(startAlternative in step.alternatives.indices) { "Invalid persisted alternative" }
            for (alternativeIndex in startAlternative until step.alternatives.size) {
                val original = step.alternatives[alternativeIndex]
                var attempt = if (alternativeIndex == startAlternative) session.memory[PENDING_ATTEMPT]?.toIntOrNull() ?: 1 else 1
                while (attempt <= step.maxAttempts) {
                    checkActive(session.id)
                    val observation = gateway.observe(withScreenshot = isUi(original))
                    checkActive(session.id)
                    val action = try {
                        val activePermit = permit
                        val candidate = if (activePermit != null && activePermit.stepId == step.id && activePermit.action.kind == original.kind) activePermit.action else refreshGrounded(original, observation)
                        safety.validateUiTarget(validator.validateAction(candidate), observation)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (preflightFailure: Exception) {
                        // No dispatch happened. Reject this target and try a validated alternative
                        // or the one permitted repair; never turn a failed guard into a click.
                        permit = null
                        lastFailure = "${step.title}: ${preflightFailure.message ?: "UI grounding failed"}"
                        session = persist(session.copy(trace = session.trace + TraceEntry(step.id, original, attempt, "Rejected before dispatch: $lastFailure", false, now()), memory = clearApprovalMemory(session.memory).minus(IN_FLIGHT) + (PENDING_ATTEMPT to (step.maxAttempts + 1).toString()), message = lastFailure))
                        break
                    }
                    val reason = safety.confirmationReason(action, observation)
                    if (reason != null) {
                        val approved = permit?.let { it.stepId == step.id && it.action == action && (!isUi(action) || it.fingerprint == safety.fingerprint(observation)) && now() in (it.expiresAt - approvalTtlMs)..it.expiresAt } == true
                        if (!approved) return requestApproval(session, step, action, observation, reason, alternativeIndex, attempt)
                    }
                    permit = null // One approval authorizes exactly one dispatch, including failed dispatches.
                    session = persist(session.copy(status = SessionStatus.RUNNING, approval = null, memory = clearApprovalMemory(session.memory) + mapOf(IN_FLIGHT to Json.encodeToString(action), PENDING_ALT to alternativeIndex.toString(), PENDING_ATTEMPT to attempt.toString()), message = step.title))
                    checkActive(session.id)
                    val result = gateway.execute(action)
                    checkActive(session.id)
                    val after = gateway.observe(withScreenshot = isUi(action))
                    val verified = gateway.verify(step.verification, after, result)
                    checkActive(session.id)
                    // The in-flight marker remains until verification and the trace are durably saved.
                    val succeeded = result.dispatched && verified.satisfied && verified.evidence.isNotBlank()
                    val updatedTrace = session.trace + TraceEntry(step.id, action, attempt, "${result.detail}; evidence: ${verified.evidence}", succeeded, now())
                    if (succeeded) {
                        // One durable write closes the dispatch marker and advances the cursor.
                        session = persist(session.copy(trace = updatedTrace, nextStep = session.nextStep + 1, memory = clearStepMemory(session.memory) + mapOf("last_package" to after.packageName, "last_evidence" to verified.evidence), message = verified.evidence))
                        completed = true
                        break
                    }
                    lastFailure = "${step.title}: ${result.detail}; ${verified.evidence}"
                    if (result.dispatched && !safety.isSafelyRepeatable(action)) {
                        // Keep the marker: resume may inspect evidence but must never replay this effect.
                        return persist(session.copy(trace = updatedTrace, status = SessionStatus.PAUSED, message = "Outcome is uncertain. Operation will not be repeated. $lastFailure"))
                    }
                    val canRetry = result.retryable && safety.isSafelyRepeatable(action)
                    val nextAttempt = if (canRetry) attempt + 1 else step.maxAttempts + 1
                    session = persist(session.copy(trace = updatedTrace, memory = session.memory.minus(IN_FLIGHT) + (PENDING_ATTEMPT to nextAttempt.toString())))
                    if (!canRetry) break
                    attempt = nextAttempt
                }
                if (completed) break
                val nextAlternative = alternativeIndex + 1
                val cursor = if (nextAlternative < step.alternatives.size) mapOf(PENDING_ALT to nextAlternative.toString(), PENDING_ATTEMPT to "1") else mapOf(PENDING_ALT to alternativeIndex.toString(), PENDING_ATTEMPT to (step.maxAttempts + 1).toString())
                session = persist(session.copy(memory = clearStepMemory(session.memory) + cursor))
            }
            if (!completed) {
                // Only reach here when an operation was never dispatched or is safely repeatable.
                // Unknown effects return PAUSED above; they never reach model repair.
                if ((session.memory[REPAIR_COUNT]?.toIntOrNull() ?: 0) < 1) {
                    session = persist(session.copy(memory = session.memory + (REPAIR_COUNT to "1"), message = "Repairing a safely failed step"))
                    val observation = gateway.observe(withScreenshot = true)
                    checkActive(session.id)
                    val suffix = planner.repair(session.task, plan, step, observation, session.memory)
                    checkActive(session.id)
                    if (suffix != null) {
                        val validatedSuffix = validator.validate(suffix)
                        val completedEffectHashes = session.trace.filter { it.verified && !safety.isSafelyRepeatable(it.action) }.map { safety.effectFingerprint(it.action) }.toSet()
                        require(validatedSuffix.steps.flatMap { it.alternatives }.none { safety.effectFingerprint(it) in completedEffectHashes }) { "Repaired plan cannot repeat a completed nonrepeatable operation" }
                        val combined = validator.validate(Plan(validatedSuffix.summary, plan.steps.take(session.nextStep) + validatedSuffix.steps))
                        plan = combined
                        session = persist(session.copy(plan = combined, memory = clearStepMemory(session.memory), message = "Validated one repaired plan"))
                        continue
                    }
                }
                return persist(session.copy(status = SessionStatus.FAILED, message = lastFailure, memory = clearStepMemory(session.memory)))
            }
        }
        return persist(session.copy(status = SessionStatus.SUCCEEDED, approval = null, message = "Task completed with evidence for every step", memory = clearStepMemory(session.memory)))
    }

    private suspend fun refreshGrounded(action: Action, observation: Observation): Action {
        if (!isUi(action)) return validator.validateAction(action)
        val grounded = planner.ground(action, observation)
        require(grounded.kind == action.kind && grounded.text == action.text && grounded.packageName == action.packageName && grounded.sensitive == action.sensitive && grounded.description == action.description) { "Grounding may only resolve the UI target" }
        return safety.validateUiTarget(validator.validateAction(grounded), observation)
    }

    private suspend fun requestApproval(session: Session, step: PlanStep, action: Action, observation: Observation, reason: String, alternative: Int, attempt: Int): Session {
        val screenSummary = if (isUi(action)) observation.nodes.filter { !it.password }
            .flatMap { listOf(it.text, it.description) }.filter { it.isNotBlank() }.distinct().joinToString(" · ").take(3_000) else ""
        val approval = ApprovalRequest(UUID.randomUUID().toString(), step.id, action, reason, safety.fingerprint(observation), now(), screenSummary)
        return persist(session.copy(status = SessionStatus.AWAITING_CONFIRMATION, approval = approval, message = reason, memory = session.memory.minus(IN_FLIGHT) + mapOf(APPROVAL_ACTION_HASH to safety.actionFingerprint(action), PENDING_ALT to alternative.toString(), PENDING_ATTEMPT to attempt.toString())))
    }

    private suspend fun serialized(id: String, block: suspend () -> Session): Session = mutex.withLock {
        val job = SupervisorJob(currentCoroutineContext()[Job])
        active[id] = job
        try {
            withContext(job) {
                checkActive(id)
                block()
            }
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                val previous = store.load(id)
                if (previous != null && previous.status in terminalStates) previous
                else if (previous != null) persist(previous.copy(status = if (id in cancelled) SessionStatus.CANCELLED else SessionStatus.PAUSED, approval = null, message = if (id in cancelled) "Task cancelled" else "Task execution interrupted; inspect evidence before resuming", memory = clearApprovalMemory(previous.memory)))
                else throw cancel
            }
        } catch (failure: Exception) {
            val previous = store.load(id) ?: throw failure
            val uncertain = previous.memory.containsKey(IN_FLIGHT)
            persist(previous.copy(status = if (uncertain) SessionStatus.PAUSED else SessionStatus.FAILED, approval = null, message = "${if (uncertain) "Outcome is uncertain; do not repeat this operation. " else ""}${failure.message ?: failure.javaClass.simpleName}", memory = clearApprovalMemory(previous.memory)))
        } finally {
            active.remove(id, job)
            job.complete()
        }
    }

    private suspend fun checkActive(id: String) {
        currentCoroutineContext().ensureActive()
        if (id in cancelled) throw CancellationException("Task cancelled")
    }

    private suspend fun persist(session: Session): Session {
        val updated = session.copy(updatedAt = now())
        store.save(updated)
        mutableState.value = updated
        return updated
    }

    private suspend fun current(id: String): Session = requireNotNull(store.load(id))
    private fun isUi(action: Action) = action.kind in setOf(ActionKind.UI_CLICK, ActionKind.UI_SET_TEXT, ActionKind.UI_SCROLL)
    private fun clearApprovalMemory(memory: Map<String, String>) = memory.minus(APPROVAL_ACTION_HASH)
    private fun clearStepMemory(memory: Map<String, String>) = clearApprovalMemory(memory).minus(IN_FLIGHT).minus(PENDING_ALT).minus(PENDING_ATTEMPT)
    private fun constantTimeEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    private data class Permit(val stepId: String, val action: Action, val fingerprint: String, val expiresAt: Long)

    companion object {
        const val IN_FLIGHT = "_engine.inflight"
        private const val APPROVAL_ACTION_HASH = "_engine.approval.actionHash"
        private const val PENDING_ALT = "_engine.pendingAlternative"
        private const val PENDING_ATTEMPT = "_engine.pendingAttempt"
        private const val REPAIR_COUNT = "_engine.repairCount"
        private val terminalStates = setOf(SessionStatus.SUCCEEDED, SessionStatus.FAILED, SessionStatus.CANCELLED)
    }
}
