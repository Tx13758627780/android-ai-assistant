package dev.phoneagent.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Independent review cases around the boundary between approval, dispatch and evidence. */
class ReviewRegressionTest {
    @Test
    fun `an approval token cannot dispatch the same operation twice`() = runTest {
        val fixture = Fixture()
        val pending = fixture.engine.start("Continue the task")
        val token = assertNotNull(pending.approval).token

        val completed = fixture.engine.approve(pending.id, token)
        assertEquals(SessionStatus.SUCCEEDED, completed.status)
        assertEquals(1, fixture.device.dispatches)

        val replayed = fixture.engine.approve(pending.id, token)
        assertEquals(SessionStatus.SUCCEEDED, replayed.status)
        assertEquals(1, fixture.device.dispatches)
    }

    @Test
    fun `a moved target invalidates approval even when its label stays identical`() = runTest {
        val fixture = Fixture()
        val pending = fixture.engine.start("Continue the task")
        val oldToken = assertNotNull(pending.approval).token
        fixture.device.screen = fixture.device.screen.copy(nodes = fixture.device.screen.nodes.map {
            it.copy(bounds = Bounds(500, 800, 700, 900))
        })

        val refreshed = fixture.engine.approve(pending.id, oldToken)
        assertEquals(SessionStatus.AWAITING_CONFIRMATION, refreshed.status)
        assertEquals(0, fixture.device.dispatches)
        val freshToken = assertNotNull(refreshed.approval).token
        assertNotEquals(oldToken, freshToken)

        fixture.engine.approve(pending.id, oldToken)
        assertEquals(0, fixture.device.dispatches)
        assertEquals(freshToken, assertNotNull(fixture.store.load(pending.id)?.approval).token)
        assertEquals(SessionStatus.SUCCEEDED, fixture.engine.approve(pending.id, freshToken).status)
        assertEquals(1, fixture.device.dispatches)
    }

    @Test
    fun `expired approval creates a new request without dispatch`() = runTest {
        var timestamp = 10_000L
        val fixture = Fixture(now = { timestamp })
        val pending = fixture.engine.start("Continue the task")
        val token = assertNotNull(pending.approval).token
        timestamp += 120_001

        val refreshed = fixture.engine.approve(pending.id, token)
        assertEquals(SessionStatus.AWAITING_CONFIRMATION, refreshed.status)
        assertNotEquals(token, assertNotNull(refreshed.approval).token)
        assertEquals(0, fixture.device.dispatches)
    }

    @Test
    fun `uncertain UI effects are never repeated by resume`() = runTest {
        val fixture = Fixture()
        fixture.device.hasEvidence = false
        val pending = fixture.engine.start("Continue the task")
        val paused = fixture.engine.approve(pending.id, assertNotNull(pending.approval).token)

        assertEquals(SessionStatus.PAUSED, paused.status)
        assertTrue(paused.memory.containsKey(TaskEngine.IN_FLIGHT))
        assertEquals(1, fixture.device.dispatches)
        repeat(3) {
            assertEquals(SessionStatus.PAUSED, fixture.engine.resume(pending.id).status)
        }
        assertEquals(1, fixture.device.dispatches)

        // A later independent observation may resolve the outcome without executing again.
        fixture.device.hasEvidence = true
        val recovered = fixture.engine.resume(pending.id)
        assertEquals(SessionStatus.SUCCEEDED, recovered.status)
        assertTrue(recovered.trace.any { it.stepId == "continue" && it.verified })
        assertEquals("Completed text observed", recovered.memory["last_evidence"])
        assertEquals(1, fixture.device.dispatches)
    }

    @Test
    fun `rejecting the operation remains terminal on resume and token replay`() = runTest {
        val fixture = Fixture()
        val pending = fixture.engine.start("Continue the task")
        val token = assertNotNull(pending.approval).token

        assertEquals(SessionStatus.CANCELLED, fixture.engine.reject(pending.id).status)
        assertEquals(SessionStatus.CANCELLED, fixture.engine.resume(pending.id).status)
        assertEquals(SessionStatus.CANCELLED, fixture.engine.approve(pending.id, token).status)
        assertEquals(0, fixture.device.dispatches)
    }

    @Test
    fun `every persisted post dispatch checkpoint is safe to recover after process death`() = runTest {
        for (evidenceAvailable in listOf(true, false)) {
            val fixture = Fixture()
            fixture.device.hasEvidence = evidenceAvailable
            val pending = fixture.engine.start("Continue the task")
            fixture.engine.approve(pending.id, assertNotNull(pending.approval).token)

            // A process can die after ANY completed SessionStore.save, including an
            // intermediate save before the engine gets to its final status update.
            val postDispatchCheckpoints = fixture.store.checkpoints.filter { it.trace.isNotEmpty() }
            assertTrue(postDispatchCheckpoints.isNotEmpty())
            for (checkpoint in postDispatchCheckpoints) {
                assertTrue(
                    checkpoint.nextStep == 1 || checkpoint.memory.containsKey(TaskEngine.IN_FLIGHT),
                    "An already dispatched nonrepeatable operation lost both its recovery marker " +
                        "and completed cursor at persisted checkpoint ${checkpoint.status}"
                )
            }
        }
    }

    private class Fixture(now: () -> Long = { 10_000L }) {
        val store = MemoryStore()
        val device = ReviewDevice()
        private val planner = object : Planner {
            override suspend fun plan(task: String, observation: Observation, memory: Map<String, String>) = Plan(
                "One UI operation",
                listOf(PlanStep(
                    id = "continue",
                    title = "Press the observed Continue control",
                    alternatives = listOf(Action(
                        kind = ActionKind.UI_CLICK,
                        packageName = "com.example.review",
                        selector = Selector(text = "Continue"),
                        description = "Continue on the current screen",
                        sensitive = false // The policy must still require confirmation.
                    )),
                    verification = Verification(VerificationKind.TEXT_PRESENT, "Completed"),
                    maxAttempts = 3
                ))
            )
        }
        val engine = TaskEngine(planner, device, store, now = now)
    }

    private class MemoryStore : SessionStore {
        private val sessions = mutableMapOf<String, Session>()
        val checkpoints = mutableListOf<Session>()
        override suspend fun save(session: Session) {
            sessions[session.id] = session
            checkpoints += session
        }
        override suspend fun load(id: String) = sessions[id]
        override suspend fun list() = sessions.values.toList()
    }

    private class ReviewDevice : DeviceGateway {
        var dispatches = 0
        var hasEvidence = true
        var screen = Observation(
            packageName = "com.example.review",
            width = 1080,
            height = 1920,
            nodes = listOf(UiNode(
                nodeId = "0.1",
                text = "Continue",
                bounds = Bounds(100, 200, 300, 300),
                clickable = true
            ))
        )
        override suspend fun observe(withScreenshot: Boolean) = screen
        override suspend fun execute(action: Action): ExecutionResult {
            dispatches++
            return ExecutionResult(true, "Dispatched", retryable = true)
        }
        override suspend fun verify(verification: Verification, observation: Observation, result: ExecutionResult) =
            VerificationResult(hasEvidence, if (hasEvidence) "Completed text observed" else "Completion evidence unavailable")
    }
}
