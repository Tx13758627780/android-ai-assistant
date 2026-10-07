package dev.phoneagent.app

import dev.phoneagent.core.ShellOp
import org.json.JSONObject

/** Shizuku starts this AIDL implementation as a user service under the shell/root UID. */
class PrivilegedCommandService : IPrivilegedCommandService.Stub() {
    override fun executeOperation(operation: String?, value: Int): String {
        val op = runCatching { ShellOp.valueOf(operation.orEmpty()) }.getOrNull()
        val commands = op?.let { AllowedShellCommands.forOperation(it, value) }
            ?: return JSONObject().put("success", false).put("detail", "Typed operation rejected by privileged allowlist").toString()
        for (command in commands) {
            val result = ProcessRunner.run(command)
            if (!result.success) return JSONObject().put("success", false).put("detail", result.detail).toString()
        }
        return JSONObject().put("success", true).put("detail", "Shizuku typed operation dispatched; verify real device state").toString()
    }

    override fun destroy() {
        kotlin.system.exitProcess(0)
    }
}
