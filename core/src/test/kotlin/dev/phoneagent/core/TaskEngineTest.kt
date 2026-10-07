package dev.phoneagent.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TaskEngineTest {
    @Test fun executesCrossAppPlanAndKeepsVerifiedMemory() = runTest {
        val gateway = FakeGateway()
        val engine = TaskEngine(FixedPlanner(plan(
            step("first", launch("com.example.first"), packageEvidence("com.example.first")),
            step("second", launch("com.example.second"), packageEvidence("com.example.second"))
        )), gateway, MemoryStore())
        val result = engine.start("Open the first app then the second")
        assertEquals(SessionStatus.SUCCEEDED, result.status)
        assertEquals(listOf("com.example.first", "com.example.second"), gateway.actions.map { it.packageName })
        assertEquals(2, result.nextStep)
        assertTrue(result.trace.all { it.verified })
        assertEquals("com.example.second", result.memory["last_package"])
        assertEquals(result, engine.state.value)
    }

    @Test fun dispatchWithoutCompletionEvidenceFailsWithinRetryBudget() = runTest {
        val gateway = FakeGateway().apply { verificationOverride = VerificationResult(false, "Expected package was absent") }
        val result = TaskEngine(FixedPlanner(plan(step("open", launch("com.example.target"), packageEvidence("com.example.target"), attempts = 2))), gateway, MemoryStore()).start("Open target")
        assertEquals(SessionStatus.FAILED, result.status)
        assertEquals(2, gateway.actions.size)
        assertFalse(result.trace.any { it.verified })
        assertEquals(0, result.nextStep)
    }

    @Test fun safelyRepeatableFailureCanRetryAndVerify() = runTest {
        val gateway = FakeGateway().apply {
            executeOverride = { action ->
                if (actions.size == 1) ExecutionResult(false, "Temporary binder failure")
                else { observation = observation.copy(packageName = action.packageName!!); ExecutionResult(true, "Launched") }
            }
        }
        val result = TaskEngine(FixedPlanner(plan(step("open", launch("com.example.target"), packageEvidence("com.example.target")))), gateway, MemoryStore()).start("Open target")
        assertEquals(SessionStatus.SUCCEEDED, result.status)
        assertEquals(listOf(1, 2), result.trace.map { it.attempt })
        assertEquals(2, gateway.actions.size)
    }

    @Test fun structuredExecutorIsTriedBeforeUiFallbackAndUiRequiresApproval() = runTest {
        val gateway = FakeGateway().apply {
            executeOverride = { action ->
                if (action.kind == ActionKind.INTENT) ExecutionResult(false, "App has no supported intent", retryable = false)
                else { observation = observation.copy(nodes = listOf(node("Done"))); ExecutionResult(true, "Clicked") }
            }
        }
        val click = Action(ActionKind.UI_CLICK, packageName = "com.example.first", selector = Selector(text = "Send"), description = "Tap a button")
        val planner = FixedPlanner(plan(PlanStep("fallback", "Try official interface then screen", listOf(click, launch("com.example.target")), Verification(VerificationKind.TEXT_PRESENT, "Done"), 1)))
        val engine = TaskEngine(planner, gateway, MemoryStore())
        val pending = engine.start("Do the task")
        assertEquals(SessionStatus.AWAITING_CONFIRMATION, pending.status)
        assertEquals(listOf(ActionKind.INTENT), gateway.actions.map { it.kind })
        val finished = engine.approve(pending.id, pending.approval!!.token)
        assertEquals(SessionStatus.SUCCEEDED, finished.status)
        assertEquals(listOf(ActionKind.INTENT, ActionKind.UI_CLICK), gateway.actions.map { it.kind })
    }

    @Test fun forgedApprovalDoesNotExecuteAndValidApprovalIsOneShot() = runTest {
        val gateway = FakeGateway()
        val engine = TaskEngine(FixedPlanner(plan(step("send", Action(ActionKind.UI_CLICK, packageName = "com.example.first", selector = Selector(text = "Send"), sensitive = false), Verification(VerificationKind.TEXT_PRESENT, "Sent")))), gateway, MemoryStore())
        val awaiting = engine.start("Send a message")
        assertEquals(SessionStatus.AWAITING_CONFIRMATION, awaiting.status)
        val forged = engine.approve(awaiting.id, "forged-token")
        assertEquals(SessionStatus.AWAITING_CONFIRMATION, forged.status)
        assertTrue(gateway.actions.isEmpty())
        val token = awaiting.approval!!.token
        assertEquals(SessionStatus.SUCCEEDED, engine.approve(awaiting.id, token).status)
        engine.approve(awaiting.id, token)
        assertEquals(1, gateway.actions.size)
    }

    @Test fun expiredApprovalProducesFreshTokenAndDoesNotExecute() = runTest {
        var time = 1_000L
        val gateway = FakeGateway()
        val engine = TaskEngine(FixedPlanner(plan(step("click", Action(ActionKind.UI_CLICK, packageName = "com.example.first", selector = Selector(text = "Send")), Verification(VerificationKind.TEXT_PRESENT, "Sent")))), gateway, MemoryStore(), now = { time }, approvalTtlMs = 100)
        val awaiting = engine.start("Click Send")
        time += 101
        val expired = engine.approve(awaiting.id, awaiting.approval!!.token)
        assertEquals(SessionStatus.AWAITING_CONFIRMATION, expired.status)
        assertTrue(expired.approval!!.token != awaiting.approval.token)
        assertTrue(gateway.actions.isEmpty())
    }

    @Test fun mutatedApiIsConfirmedRegardlessOfSensitiveFlagAndNeverRetried() = runTest {
        val gateway = FakeGateway().apply { verificationOverride = VerificationResult(false, "No server completion evidence") }
        val action = Action(ActionKind.API, api = ApiRequest("https://api.example.com/items", "DELETE"), sensitive = false)
        val engine = TaskEngine(FixedPlanner(plan(step("delete", action, Verification(VerificationKind.API_STATUS, "204"), attempts = 3))), gateway, MemoryStore())
        val awaiting = engine.start("Remove item")
        assertEquals(SessionStatus.AWAITING_CONFIRMATION, awaiting.status)
        assertTrue(gateway.actions.isEmpty())
        val uncertain = engine.approve(awaiting.id, awaiting.approval!!.token)
        assertEquals(SessionStatus.PAUSED, uncertain.status)
        assertEquals(1, gateway.actions.size)
        assertNotNull(uncertain.memory[TaskEngine.IN_FLIGHT])
        assertEquals(SessionStatus.PAUSED, engine.resume(uncertain.id).status)
        assertEquals(1, gateway.actions.size)
    }

    @Test fun structuredApprovalSurvivesForegroundChangeWhileBindingExactAction() = runTest {
        val gateway = FakeGateway()
        val action = Action(ActionKind.API, api = ApiRequest("https://api.example.com/items", "DELETE"))
        val engine = TaskEngine(FixedPlanner(plan(step("delete", action, Verification(VerificationKind.API_STATUS, "204")))), gateway, MemoryStore())
        val awaiting = engine.start("Remove the item")
        gateway.observation = gateway.observation.copy(packageName = "dev.phoneagent.app", nodes = listOf(node("Confirm")))
        val result = engine.approve(awaiting.id, awaiting.approval!!.token)
        assertEquals(SessionStatus.SUCCEEDED, result.status)
        assertEquals(listOf(action), gateway.actions)
    }

    @Test fun exhaustedSafeActionCanRepairRemainingPlanOnce() = runTest {
        var repairs = 0
        val gateway = FakeGateway().apply {
            executeOverride = { action ->
                if (action.packageName == "com.example.bad") ExecutionResult(false, "Unsupported", retryable = false)
                else { observation = observation.copy(packageName = action.packageName!!); ExecutionResult(true, "Launched") }
            }
        }
        val planner = object : Planner {
            override suspend fun plan(task: String, observation: Observation, memory: Map<String, String>) = plan(step("broken", launch("com.example.bad"), packageEvidence("com.example.bad")))
            override suspend fun repair(task: String, plan: Plan, failedStep: PlanStep, observation: Observation, memory: Map<String, String>): Plan { repairs++; return plan(step("fixed", launch("com.example.good"), packageEvidence("com.example.good"))) }
        }
        val result = TaskEngine(planner, gateway, MemoryStore()).start("Open the app")
        assertEquals(SessionStatus.SUCCEEDED, result.status)
        assertEquals(1, repairs)
        assertEquals(listOf("com.example.bad", "com.example.good"), gateway.actions.map { it.packageName })
    }

    @Test fun repairedPlanCannotRepeatCompletedNonrepeatableEffect() = runTest {
        val completedAction = Action(ActionKind.API, api = ApiRequest("https://api.example.com/items", "POST", "{}"))
        val gateway = FakeGateway().apply {
            executeOverride = { action -> if (action.kind == ActionKind.API) ExecutionResult(true, "Created", apiStatus = 204) else ExecutionResult(false, "Unsupported", retryable = false) }
        }
        val planner = object : Planner {
            override suspend fun plan(task: String, observation: Observation, memory: Map<String, String>) = plan(step("create", completedAction, Verification(VerificationKind.API_STATUS, "204")), step("broken", launch("com.example.bad"), packageEvidence("com.example.bad")))
            override suspend fun repair(task: String, plan: Plan, failedStep: PlanStep, observation: Observation, memory: Map<String, String>) = plan(step("duplicate-effect", completedAction.copy(description = "A different description", sensitive = true), Verification(VerificationKind.API_STATUS, "204")))
        }
        val engine = TaskEngine(planner, gateway, MemoryStore())
        val awaiting = engine.start("Create item and open app")
        val result = engine.approve(awaiting.id, awaiting.approval!!.token)
        assertEquals(SessionStatus.FAILED, result.status)
        assertTrue(result.message.contains("cannot repeat"))
        assertEquals(1, gateway.actions.count { it.kind == ActionKind.API })
    }

    @Test fun persistedInterruptedEffectCanCompleteFromEvidenceWithoutRedispatch() = runTest {
        val store = MemoryStore()
        val gateway = FakeGateway().apply {
            executeOverride = { action ->
                observation = observation.copy(packageName = action.packageName!!)
                throw IllegalStateException("Process interrupted after binder dispatch")
            }
        }
        val planner = FixedPlanner(plan(step("open", launch("com.example.target"), packageEvidence("com.example.target"))))
        val interrupted = TaskEngine(planner, gateway, store).start("Open target")
        assertEquals(SessionStatus.PAUSED, interrupted.status)
        assertNotNull(interrupted.memory[TaskEngine.IN_FLIGHT])
        val freshEngine = TaskEngine(planner, gateway, store)
        val restored = freshEngine.resume(interrupted.id)
        assertEquals(SessionStatus.SUCCEEDED, restored.status)
        assertEquals(1, gateway.actions.size)
        assertFalse(restored.memory.containsKey(TaskEngine.IN_FLIGHT))
    }

    @Test fun duplicateUiTargetsFailClosedBeforeApprovalOrDispatch() = runTest {
        val gateway = FakeGateway().apply { observation = observation.copy(nodes = listOf(node("Send", "1"), node("Send", "2"))) }
        val result = TaskEngine(FixedPlanner(plan(step("click", Action(ActionKind.UI_CLICK, packageName = "com.example.first", selector = Selector(text = "Send")), Verification(VerificationKind.TEXT_PRESENT, "Sent")))), gateway, MemoryStore()).start("Send")
        assertEquals(SessionStatus.FAILED, result.status)
        assertTrue(result.message.contains("ambiguous"))
        assertTrue(gateway.actions.isEmpty())
    }

    @Test fun malformedPlannerActionCannotDispatchRawShell() = runTest {
        val gateway = FakeGateway()
        val malformed = Action(ActionKind.SHELL, shellOp = ShellOp.WIFI_ON, text = "rm -rf /")
        val result = TaskEngine(FixedPlanner(plan(step("raw", malformed, Verification(VerificationKind.SYSTEM_SETTING, "1", "wifi_on")))), gateway, MemoryStore()).start("Turn WiFi on")
        assertEquals(SessionStatus.FAILED, result.status)
        assertTrue(gateway.actions.isEmpty())
    }

    @Test fun maliciousGroundingCannotChangeTheOperation() = runTest {
        val gateway = FakeGateway()
        val planner = object : Planner {
            override suspend fun plan(task: String, observation: Observation, memory: Map<String, String>) = plan(step("click", Action(ActionKind.UI_CLICK, packageName = "com.example.first", selector = Selector(text = "Send")), Verification(VerificationKind.TEXT_PRESENT, "Sent")))
            override suspend fun ground(action: Action, observation: Observation) = launch("com.example.attack")
        }
        val result = TaskEngine(planner, gateway, MemoryStore()).start("Send")
        assertEquals(SessionStatus.FAILED, result.status)
        assertTrue(gateway.actions.isEmpty())
    }

    @Test fun cancellationStopsCooperativeGatewayAndFurtherSteps() = runTest {
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val gateway = FakeGateway().apply {
            executeOverride = { entered.complete(Unit); never.await(); ExecutionResult(true, "Unexpected") }
        }
        val store = MemoryStore()
        val engine = TaskEngine(FixedPlanner(plan(step("first", launch("com.example.first"), packageEvidence("com.example.first")), step("second", launch("com.example.second"), packageEvidence("com.example.second")))), gateway, store)
        var startedResult: Session? = null
        val work = launch { startedResult = engine.start("Do two tasks") }
        entered.await()
        val id = engine.state.value!!.id
        val cancelled = engine.cancel(id)
        work.join()
        assertEquals(SessionStatus.CANCELLED, cancelled.status)
        assertEquals(SessionStatus.CANCELLED, startedResult!!.status)
        assertEquals(SessionStatus.CANCELLED, engine.resume(id).status)
        assertEquals(1, gateway.actions.size)
    }

    @Test fun initialRuntimeMemoryIsAvailableToPlanner() = runTest {
        var received = emptyMap<String, String>()
        val planner = object : Planner {
            override suspend fun plan(task: String, observation: Observation, memory: Map<String, String>): Plan {
                received = memory
                return plan(step("open", launch("com.example.target"), packageEvidence("com.example.target")))
            }
        }
        TaskEngine(planner, FakeGateway(), MemoryStore()).start("Open target", mapOf("installed_apps" to "Target=com.example.target"))
        assertEquals("Target=com.example.target", received["installed_apps"])
    }

    @Test fun terminalSuccessIsNotChangedByLateCancelRejectOrApprovalReplay() = runTest {
        val gateway = FakeGateway()
        val engine = TaskEngine(FixedPlanner(plan(step("open", launch("com.example.target"), packageEvidence("com.example.target")))), gateway, MemoryStore())
        val success = engine.start("Open target")
        assertEquals(SessionStatus.SUCCEEDED, engine.cancel(success.id).status)
        assertEquals(SessionStatus.SUCCEEDED, engine.reject(success.id).status)
        assertEquals(SessionStatus.SUCCEEDED, engine.approve(success.id, "old-token").status)
        assertEquals(SessionStatus.SUCCEEDED, engine.resume(success.id).status)
        assertEquals(1, gateway.actions.size)
    }

    @Test fun failedGroundingCanUseAnotherKnownTargetWithoutBypassingConfirmation() = runTest {
        val gateway = FakeGateway()
        val missing = Action(ActionKind.UI_CLICK, packageName = "com.example.first", selector = Selector(text = "Missing"))
        val known = missing.copy(selector = Selector(text = "Send"))
        val planner = FixedPlanner(plan(PlanStep("click", "Find supported target", listOf(missing, known), Verification(VerificationKind.TEXT_PRESENT, "Sent"))))
        val engine = TaskEngine(planner, gateway, MemoryStore())
        val pending = engine.start("Send")
        assertEquals(SessionStatus.AWAITING_CONFIRMATION, pending.status)
        assertEquals(known, pending.approval!!.action)
        assertTrue(gateway.actions.isEmpty())
        assertTrue(pending.trace.single().detail.contains("Rejected before dispatch"))
        assertEquals(SessionStatus.SUCCEEDED, engine.approve(pending.id, pending.approval.token).status)
        assertEquals(listOf(known), gateway.actions)
    }

    @Test fun emptyRepairCannotDiscardFailedRemainingTask() = runTest {
        val gateway = FakeGateway().apply {
            executeOverride = { action ->
                if (action.packageName == "com.example.bad") ExecutionResult(false, "Unsupported", retryable = false)
                else { observation = observation.copy(packageName = action.packageName!!); ExecutionResult(true, "Launched") }
            }
        }
        val planner = object : Planner {
            override suspend fun plan(task: String, observation: Observation, memory: Map<String, String>) = plan(step("good", launch("com.example.good"), packageEvidence("com.example.good")), step("bad", launch("com.example.bad"), packageEvidence("com.example.bad")))
            override suspend fun repair(task: String, plan: Plan, failedStep: PlanStep, observation: Observation, memory: Map<String, String>) = Plan("Pretend everything is done", emptyList())
        }
        val result = TaskEngine(planner, gateway, MemoryStore()).start("Open both apps")
        assertEquals(SessionStatus.FAILED, result.status)
        assertEquals(1, result.nextStep)
        assertTrue(result.message.contains("1–24"))
    }

    @Test fun restartDoesNotResetInterruptedSafeRetryBudget() = runTest {
        val gateway = FakeGateway().apply { executeOverride = { throw IllegalStateException("Interrupted without outcome evidence") } }
        val store = MemoryStore()
        val planner = FixedPlanner(plan(step("open", launch("com.example.target"), packageEvidence("com.example.target"), attempts = 1)))
        val paused = TaskEngine(planner, gateway, store).start("Open app")
        assertEquals(SessionStatus.PAUSED, paused.status)
        val restarted = TaskEngine(planner, gateway, store).resume(paused.id)
        assertEquals(SessionStatus.FAILED, restarted.status)
        assertEquals(1, gateway.actions.size)
    }

    private class FixedPlanner(private val supplied: Plan) : Planner {
        override suspend fun plan(task: String, observation: Observation, memory: Map<String, String>) = supplied
    }

    private class MemoryStore : SessionStore {
        val values = linkedMapOf<String, Session>()
        override suspend fun save(session: Session) { values[session.id] = session }
        override suspend fun load(id: String) = values[id]
        override suspend fun list() = values.values.toList()
    }

    private class FakeGateway : DeviceGateway {
        var observation = Observation(packageName = "com.example.first", nodes = listOf(node("Send")), width = 400, height = 800)
        val actions = mutableListOf<Action>()
        var executeOverride: (suspend FakeGateway.(Action) -> ExecutionResult)? = null
        var verificationOverride: VerificationResult? = null
        override suspend fun observe(withScreenshot: Boolean) = observation.copy(capturedAt = System.currentTimeMillis())
        override suspend fun execute(action: Action): ExecutionResult {
            actions += action
            return executeOverride?.invoke(this, action) ?: when (action.kind) {
                ActionKind.INTENT, ActionKind.DEEP_LINK -> { observation = observation.copy(packageName = action.packageName ?: observation.packageName); ExecutionResult(true, "App launched") }
                ActionKind.UI_CLICK -> { observation = observation.copy(nodes = listOf(node("Sent"))); ExecutionResult(true, "Click dispatched") }
                else -> ExecutionResult(true, "Operation dispatched", apiStatus = 204)
            }
        }
        override suspend fun verify(verification: Verification, observation: Observation, result: ExecutionResult): VerificationResult {
            verificationOverride?.let { return it }
            val satisfied = when (verification.kind) {
                VerificationKind.FOREGROUND_PACKAGE -> observation.packageName == verification.expected
                VerificationKind.TEXT_PRESENT -> observation.nodes.any { it.text == verification.expected }
                VerificationKind.TEXT_ABSENT -> observation.nodes.none { it.text == verification.expected }
                VerificationKind.API_STATUS -> result.apiStatus?.toString() == verification.expected
                VerificationKind.SYSTEM_SETTING -> false
            }
            return VerificationResult(satisfied, if (satisfied) "Observed ${verification.expected}" else "Expected ${verification.expected} absent")
        }
    }

    companion object {
        private fun launch(pkg: String) = Action(ActionKind.INTENT, packageName = pkg, intentAction = "android.intent.action.MAIN")
        private fun packageEvidence(pkg: String) = Verification(VerificationKind.FOREGROUND_PACKAGE, pkg)
        private fun step(id: String, action: Action, verification: Verification, attempts: Int = 2) = PlanStep(id, id, listOf(action), verification, attempts)
        private fun plan(vararg steps: PlanStep) = Plan("Test plan", steps.toList())
        private fun node(text: String, id: String = "1") = UiNode(id, text = text, bounds = Bounds(10, 10, 100, 50), clickable = true)
    }
}
