package dev.phoneagent.core

import kotlinx.serialization.Serializable

@Serializable enum class ActionKind { INTENT, DEEP_LINK, API, SHELL, UI_CLICK, UI_SET_TEXT, UI_SCROLL, GLOBAL_BACK, GLOBAL_HOME, WAIT }
@Serializable enum class ShellOp { WIFI_ON, WIFI_OFF, BRIGHTNESS, VOLUME, OPEN_SETTINGS }
@Serializable data class Selector(val text: String? = null, val viewId: String? = null, val description: String? = null)
@Serializable data class ApiRequest(val url: String, val method: String = "GET", val body: String? = null)
@Serializable data class Action(
    val kind: ActionKind,
    val packageName: String? = null,
    val intentAction: String? = null,
    val uri: String? = null,
    val text: String? = null,
    val selector: Selector? = null,
    val x: Int? = null,
    val y: Int? = null,
    val shellOp: ShellOp? = null,
    val value: Int? = null,
    val api: ApiRequest? = null,
    val description: String = "",
    val sensitive: Boolean = false
)
@Serializable enum class VerificationKind { FOREGROUND_PACKAGE, TEXT_PRESENT, TEXT_ABSENT, SYSTEM_SETTING, API_STATUS }
@Serializable data class Verification(val kind: VerificationKind, val expected: String, val key: String? = null)
@Serializable data class PlanStep(
    val id: String,
    val title: String,
    val alternatives: List<Action>,
    val verification: Verification,
    val maxAttempts: Int = 2
)
@Serializable data class Plan(val summary: String, val steps: List<PlanStep>)
@Serializable data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
@Serializable data class UiNode(
    val nodeId: String,
    val text: String = "",
    val description: String = "",
    val viewId: String = "",
    val bounds: Bounds,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val enabled: Boolean = true,
    val password: Boolean = false
)
@Serializable data class Observation(
    val packageName: String = "",
    val nodes: List<UiNode> = emptyList(),
    val screenshotBase64: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val capturedAt: Long = System.currentTimeMillis()
)
@Serializable data class ExecutionResult(val dispatched: Boolean, val detail: String, val retryable: Boolean = true, val apiStatus: Int? = null)
@Serializable data class VerificationResult(val satisfied: Boolean, val evidence: String)
@Serializable enum class SessionStatus { CREATED, PLANNING, RUNNING, AWAITING_CONFIRMATION, PAUSED, SUCCEEDED, FAILED, CANCELLED }
@Serializable data class TraceEntry(val stepId: String, val action: Action, val attempt: Int, val detail: String, val verified: Boolean = false, val timestamp: Long = System.currentTimeMillis())
@Serializable data class ApprovalRequest(
    val token: String,
    val stepId: String,
    val action: Action,
    val reason: String,
    val observationFingerprint: String,
    val createdAt: Long = System.currentTimeMillis(),
    val screenSummary: String = ""
)
@Serializable data class Session(
    val id: String,
    val task: String,
    val status: SessionStatus = SessionStatus.CREATED,
    val plan: Plan? = null,
    val nextStep: Int = 0,
    val trace: List<TraceEntry> = emptyList(),
    val approval: ApprovalRequest? = null,
    val message: String = "",
    val memory: Map<String, String> = emptyMap(),
    val updatedAt: Long = System.currentTimeMillis()
)
interface Planner {
    suspend fun plan(task: String, observation: Observation, memory: Map<String, String>): Plan
    suspend fun ground(action: Action, observation: Observation): Action = action
    suspend fun repair(task: String, plan: Plan, failedStep: PlanStep, observation: Observation, memory: Map<String, String>): Plan? = null
}
interface DeviceGateway {
    suspend fun observe(withScreenshot: Boolean = false): Observation
    suspend fun execute(action: Action): ExecutionResult
    suspend fun verify(verification: Verification, observation: Observation, result: ExecutionResult): VerificationResult
}
interface SessionStore {
    suspend fun save(session: Session)
    suspend fun load(id: String): Session?
    suspend fun list(): List<Session>
}
