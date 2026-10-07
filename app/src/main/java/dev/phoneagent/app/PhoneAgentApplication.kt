package dev.phoneagent.app

import android.app.Application
import android.content.Intent
import dev.phoneagent.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PhoneAgentApplication : Application() {
    lateinit var runtime: AgentRuntime
        private set
    override fun onCreate() { super.onCreate(); runtime = AgentRuntime(this) }
}

class AgentRuntime(private val app: Application) {
    val settings = AgentSettings(app)
    val store = FileSessionStore(app)
    val shell = PrivilegedShell(app) { settings.privilegedMode }
    val gateway = AndroidDeviceGateway(app, shell) { settings.apiHosts }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    fun reportError(value: String?) { _error.value = value }
    val engine = TaskEngine(object : Planner {
        private fun planner(): Planner = if (settings.cloudEnabled) ModelPlanner(OpenAiTransport(settings)) { settings.screenshotUpload }
            else RulePlanner { settings.privilegedMode != PrivilegedMode.NONE }
        private fun context(memory: Map<String, String>): Map<String, String> {
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = app.packageManager.queryIntentActivities(launch, 0)
            val installed = buildJsonObject { apps.take(120).forEach { put(it.loadLabel(app.packageManager).toString(), it.activityInfo.packageName) } }
            val defaults = mutableMapOf<String, String>()
            val browser = app.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")), 0)?.activityInfo?.packageName
            if (browser != null && browser !in setOf("android", "com.android.intentresolver")) defaults["default_browser_package"] = browser
            val home = app.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
            if (home != null) defaults["default_home_package"] = home
            return memory + defaults + mapOf("installed_apps" to installed.toString(), "privileged_mode" to settings.privilegedMode.name,
                "privileged_enabled" to (settings.privilegedMode != PrivilegedMode.NONE).toString(),
                "api_hosts" to settings.apiHosts.joinToString(","), "assistant_package" to app.packageName)
        }
        override suspend fun plan(task: String, observation: Observation, memory: Map<String, String>) = planner().plan(task, observation, context(memory))
        override suspend fun ground(action: Action, observation: Observation) = planner().ground(action, observation)
        override suspend fun repair(task: String, plan: Plan, failedStep: PlanStep, observation: Observation, memory: Map<String, String>) = planner().repair(task, plan, failedStep, observation, context(memory))
    }, gateway, store)
}
