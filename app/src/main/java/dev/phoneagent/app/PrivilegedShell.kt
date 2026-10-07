package dev.phoneagent.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.os.IBinder
import dev.phoneagent.core.Action
import dev.phoneagent.core.ExecutionResult
import dev.phoneagent.core.ShellOp
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku

enum class PrivilegedMode { NONE, ROOT, SHIZUKU }

/** Root and Shizuku are opt-in. Capability checks do not launch su or request permission. */
class PrivilegedShell(context: Context, private val preference: () -> PrivilegedMode) : AutoCloseable {
    private val appContext = context.applicationContext
    @Volatile private var service: IPrivilegedCommandService? = null
    @Volatile private var binding = false
    @Volatile private var closed = false
    private val serviceArgs = Shizuku.UserServiceArgs(ComponentName(appContext, PrivilegedCommandService::class.java))
        .daemon(false)
        .processNameSuffix("phone_agent")
        .debuggable(appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        .version(1)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IPrivilegedCommandService.Stub.asInterface(binder)
            binding = false
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null; binding = false
        }
        override fun onBindingDied(name: ComponentName?) {
            service = null; binding = false
        }
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (!closed && grantResult == PackageManager.PERMISSION_GRANTED && preference() == PrivilegedMode.SHIZUKU) bindShizuku()
    }
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (!closed && preference() == PrivilegedMode.SHIZUKU && shizukuPermissionGranted()) bindShizuku()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { service = null; binding = false }

    init {
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    fun selectedMode(): PrivilegedMode = preference()
    fun rootBinaryDetected(): Boolean = rootBinary() != null
    fun shizukuAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    fun shizukuPermissionGranted(): Boolean = shizukuAvailable() && runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
    fun shizukuServiceConnected(): Boolean = service?.asBinder()?.isBinderAlive == true
    fun capabilitySummary(): String = when (preference()) {
        PrivilegedMode.NONE -> "系统控制：未启用"
        PrivilegedMode.ROOT -> if (rootBinaryDetected()) "Root：检测到 su；执行时由 Root 管理器授权" else "Root：未检测到可执行 su"
        PrivilegedMode.SHIZUKU -> when {
            !shizukuAvailable() -> "Shizuku：服务未运行"
            !shizukuPermissionGranted() -> "Shizuku：等待用户授权"
            !shizukuServiceConnected() -> "Shizuku：已授权，用户服务未连接"
            else -> "Shizuku：用户服务已连接"
        }
    }

    /** Call only from an explicit user button. No permission prompt is launched by capability checks. */
    fun requestShizukuPermission(requestCode: Int): Boolean {
        if (!shizukuAvailable()) return false
        return runCatching { Shizuku.requestPermission(requestCode); true }.getOrDefault(false)
    }

    fun bindShizuku(): Boolean {
        if (closed || !shizukuPermissionGranted()) return false
        if (shizukuServiceConnected() || binding) return true
        return runCatching {
            binding = true
            Shizuku.bindUserService(serviceArgs, connection)
            true
        }.getOrElse { binding = false; false }
    }

    suspend fun execute(action: Action): ExecutionResult = withContext(Dispatchers.IO) {
        val op = action.shellOp ?: return@withContext ExecutionResult(false, "Missing typed shell operation", false)
        val value = action.value ?: 0
        if (AllowedShellCommands.forOperation(op, value) == null) {
            return@withContext ExecutionResult(false, "Unsupported or out-of-range typed shell operation", false)
        }
        when (preference()) {
            PrivilegedMode.NONE -> ExecutionResult(false, "Enable Root or Shizuku explicitly in settings", false)
            PrivilegedMode.ROOT -> {
                val binary = rootBinary() ?: return@withContext ExecutionResult(false, "No su binary detected", false)
                // All command tokens come from the fixed allowlist; no model-generated shell syntax.
                val commands = AllowedShellCommands.forOperation(op, value)!!
                var result: ProcessResult? = null
                for (command in commands) {
                    val executed = ProcessRunner.run(listOf(binary, "-c", command.joinToString(" ")))
                    if (!executed.success) { result = executed; break }
                }
                if (result != null) ExecutionResult(false, "Root operation failed: ${result.detail}")
                else ExecutionResult(true, "Root typed operation dispatched; verify device state independently")
            }
            PrivilegedMode.SHIZUKU -> {
                if (!shizukuPermissionGranted()) return@withContext ExecutionResult(false, "Shizuku service or explicit permission is missing", false)
                if (!shizukuServiceConnected()) {
                    bindShizuku()
                    repeat(8) { if (!shizukuServiceConnected()) delay(125) }
                }
                val binder = service?.takeIf { it.asBinder().isBinderAlive }
                    ?: return@withContext ExecutionResult(false, "Bind the Shizuku user service before executing", false)
                try {
                    val response = JSONObject(binder.executeOperation(op.name, value))
                    ExecutionResult(response.optBoolean("success"), response.optString("detail", "Shizuku operation returned no detail"))
                } catch (error: Exception) {
                    service = null
                    ExecutionResult(false, "Shizuku user service disconnected (${error.javaClass.simpleName})")
                }
            }
        }
    }

    override fun close() {
        closed = true
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        if (service != null || binding) runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
        service = null; binding = false
    }

    private fun rootBinary(): String? {
        val candidates = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su") +
            System.getenv("PATH").orEmpty().split(':').filter { it.startsWith('/') }.map { "$it/su" }
        return candidates.distinct().firstOrNull { File(it).isFile && File(it).canExecute() }
    }
}

/** Shared validation runs inside both the app and the Shizuku privileged binder process. */
internal object AllowedShellCommands {
    fun forOperation(op: ShellOp, value: Int): List<List<String>>? = when (op) {
        ShellOp.WIFI_ON -> listOf(listOf("/system/bin/cmd", "wifi", "set-wifi-enabled", "enabled"))
        ShellOp.WIFI_OFF -> listOf(listOf("/system/bin/cmd", "wifi", "set-wifi-enabled", "disabled"))
        ShellOp.BRIGHTNESS -> if (value in 0..255) listOf(
            listOf("/system/bin/settings", "put", "system", "screen_brightness_mode", "0"),
            listOf("/system/bin/settings", "put", "system", "screen_brightness", value.toString())
        ) else null
        ShellOp.VOLUME -> if (value in 0..15) listOf(
            listOf("/system/bin/cmd", "media_session", "volume", "--stream", "3", "--set", value.toString())
        ) else null
        ShellOp.OPEN_SETTINGS -> listOf(listOf("/system/bin/am", "start", "-a", "android.settings.SETTINGS"))
    }
}

internal data class ProcessResult(val success: Boolean, val detail: String)

internal object ProcessRunner {
    private const val TIMEOUT_SECONDS = 7L
    private const val MAX_OUTPUT = 2_000

    fun run(command: List<String>): ProcessResult {
        val reader = Executors.newSingleThreadExecutor { task -> Thread(task, "phone-agent-command-output").apply { isDaemon = true } }
        var process: Process? = null
        return try {
            process = ProcessBuilder(command).redirectErrorStream(true).start()
            val running = process
            val output = reader.submit<String> {
                running.inputStream.bufferedReader().use { stream ->
                    val retained = StringBuilder()
                    val buffer = CharArray(1_024)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        val available = MAX_OUTPUT - retained.length
                        if (available > 0) retained.append(buffer, 0, count.coerceAtMost(available))
                    }
                    retained.toString().trim()
                }
            }
            if (!running.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                running.destroyForcibly()
                ProcessResult(false, "Privileged command timed out")
            } else {
                val details = runCatching { output.get(500, TimeUnit.MILLISECONDS) }.getOrDefault("")
                ProcessResult(running.exitValue() == 0, if (details.isBlank()) "exit=${running.exitValue()}" else details)
            }
        } catch (error: Exception) {
            ProcessResult(false, "Command failed (${error.javaClass.simpleName})")
        } finally {
            runCatching { process?.inputStream?.close() }
            runCatching { process?.outputStream?.close() }
            runCatching { process?.errorStream?.close() }
            process?.destroy()
            reader.shutdownNow()
        }
    }
}
