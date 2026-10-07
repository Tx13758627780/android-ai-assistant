package dev.phoneagent.core

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Deliberately small, honest offline planner. Unknown tasks fail before device actions. */
class RulePlanner(
    private val privilegedEnabled: () -> Boolean = { false }
) : Planner {
    private val validator = PlanValidator()
    override suspend fun plan(task: String, observation: Observation, memory: Map<String, String>): Plan {
        require(task.isNotBlank()) { "请输入要执行的任务" }
        require(task.length <= 4_000) { "任务过长" }
        val clauses = task.trim().split(
            Regex("(?:[，,]?\\s*(?:然后|接着|随后|(?:并且|并|再)(?=\\s*(?:打开|启动|搜索|返回|设置|调节|把)))\\s*|[；;]|[，,]\\s*(?=打开|启动|搜索|返回|设置|调节|把))")
        ).map { it.trim().trim('，', ',', '。', '.') }.filter { it.isNotBlank() }
        require(clauses.isNotEmpty() && clauses.size <= 24) { "任务步骤过多" }
        val steps = clauses.mapIndexed { index, clause -> stepFor(clause, index + 1, memory) }
        return validator.validate(Plan("离线执行：${task.trim().take(1_990)}", steps))
    }

    private fun stepFor(raw: String, index: Int, memory: Map<String, String>): PlanStep {
        val clause = raw.removePrefix("再").trim()
        val id = "step_$index"
        fun step(title: String, action: Action, verification: Verification) =
            PlanStep(id, title, listOf(action), verification)
        fun setting(title: String, intent: String) = step(
            title,
            Action(ActionKind.INTENT, intentAction = intent, description = title),
            Verification(VerificationKind.FOREGROUND_PACKAGE, "com.android.settings")
        )

        if (Regex("^(?:打开|进入|启动)\\s*(?:系统|手机)?\\s*设置(?:应用|界面)?$", RegexOption.IGNORE_CASE).matches(clause)) {
            return setting("打开系统设置", "android.settings.SETTINGS")
        }
        if (Regex("^(?:打开|进入)\\s*(?:Wi-?Fi|WLAN|无线网络|无线|网络)\\s*设置$", RegexOption.IGNORE_CASE).matches(clause)) {
            return setting("打开 WiFi 设置", "android.settings.WIFI_SETTINGS")
        }
        if (Regex("^(?:打开|进入)\\s*蓝牙\\s*设置$", RegexOption.IGNORE_CASE).matches(clause)) {
            return setting("打开蓝牙设置", "android.settings.BLUETOOTH_SETTINGS")
        }
        if (Regex("^(?:打开|进入)\\s*(?:显示|屏幕|亮度)\\s*设置$", RegexOption.IGNORE_CASE).matches(clause)) {
            return setting("打开显示设置", "android.settings.DISPLAY_SETTINGS")
        }
        val wifi = Regex("^(打开|开启|启用|关闭|关掉|禁用)\\s*(?:Wi-?Fi|WLAN|无线网络)$", RegexOption.IGNORE_CASE).matchEntire(clause)
        if (wifi != null) {
            require(privilegedEnabled()) { "离线 WiFi 开关需要先启用并授权 Root / Shizuku；也可以输入“打开 WiFi 设置”" }
            val enabled = wifi.groupValues[1] in listOf("打开", "开启", "启用")
            val title = if (enabled) "开启 WiFi" else "关闭 WiFi"
            return step(title, Action(ActionKind.SHELL, shellOp = if (enabled) ShellOp.WIFI_ON else ShellOp.WIFI_OFF, description = title),
                Verification(VerificationKind.SYSTEM_SETTING, if (enabled) "1" else "0", "wifi_on"))
        }
        val brightness = Regex("^(?:(?:把|将)?(?:屏幕)?亮度(?:设置|设|调节|调)?(?:为|到|成)?|(?:设置|调节)(?:屏幕)?亮度(?:为|到|成)?)\\s*(\\d{1,3})\\s*[%％]$").matchEntire(clause)
        if (brightness != null) {
            require(privilegedEnabled()) { "离线亮度设置需要先启用并授权 Root / Shizuku；也可以输入“打开显示设置”" }
            val percent = brightness.groupValues[1].toInt()
            require(percent in 0..100) { "亮度应在 0% 到 100% 之间" }
            val value = (percent * 255 + 50) / 100
            return step("将亮度设为 $percent%", Action(ActionKind.SHELL, shellOp = ShellOp.BRIGHTNESS, value = value, description = "亮度 $percent%"),
                Verification(VerificationKind.SYSTEM_SETTING, value.toString(), "screen_brightness"))
        }
        val search = Regex("^(?:(?:打开|启动)\\s*(?:浏览器)\\s*(?:并|并且|后)?|(?:在)?浏览器(?:中)?)?\\s*(?:搜索|查找|搜)\\s*(.+)$").matchEntire(clause)
        if (search != null) {
            val query = search.groupValues[1].trim().trim('“', '”', '"', '‘', '’')
            require(query.isNotBlank() && query.length <= 500) { "搜索内容为空或过长" }
            return step("搜索 $query", Action(ActionKind.DEEP_LINK,
                uri = "https://www.google.com/search?q=${encode(query)}", description = "在浏览器搜索 $query"),
                Verification(VerificationKind.TEXT_PRESENT, query))
        }
        val url = Regex("^(?:打开|访问|浏览|进入)?\\s*(https?://\\S+)$", RegexOption.IGNORE_CASE).matchEntire(clause)
        if (url != null) {
            val target = url.groupValues[1]
            val parsed = runCatching { URI(target) }.getOrNull()
            require(parsed != null && !parsed.host.isNullOrBlank()) { "网址无效" }
            return step("打开 ${parsed.host}", Action(ActionKind.DEEP_LINK, uri = target, description = "打开网页 $target"),
                Verification(VerificationKind.TEXT_PRESENT, parsed.host))
        }
        if (clause in listOf("返回桌面", "回到桌面", "打开桌面", "回桌面")) {
            val home = memory["default_home_package"]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("暂未读取桌面应用，无法可靠验证回到桌面；请启用无障碍服务后重试")
            return step("返回桌面", Action(ActionKind.GLOBAL_HOME, description = "返回桌面"),
                Verification(VerificationKind.FOREGROUND_PACKAGE, home))
        }
        val openApp = Regex("^(?:打开|启动|进入)\\s*(.+?)(?:应用|App|APP)?$").matchEntire(clause)
        if (openApp != null) {
            val name = openApp.groupValues[1].trim()
            val apps = installedApps(memory)
            val packageName = if (name == "浏览器") memory["default_browser_package"]?.takeIf { it.isNotBlank() }
                ?: apps.entries.firstOrNull { it.key.contains("浏览器") || it.key.equals("Chrome", true) || it.key.equals("Firefox", true) }?.value
            else apps.entries.firstOrNull { it.key.equals(name, true) }?.value
                ?: apps.entries.filter { it.key.contains(name, true) }.singleOrNull()?.value
            require(!packageName.isNullOrBlank()) { "未找到应用“$name”。请确认已安装；复杂任务请配置 AI 模型。" }
            return step("打开 $name", Action(ActionKind.INTENT, packageName = packageName,
                intentAction = "android.intent.action.MAIN", description = "打开 $name"),
                Verification(VerificationKind.FOREGROUND_PACKAGE, packageName))
        }
        throw IllegalArgumentException("离线模式暂不支持“$clause”。支持打开设置/已安装应用/网址、浏览器搜索及已授权的 WiFi 和亮度控制；跨应用交互、发送消息和支付请配置 AI 模型。")
    }

    private fun installedApps(memory: Map<String, String>): Map<String, String> {
        val encoded = memory["installed_apps"] ?: return emptyMap()
        return runCatching {
            (Json.parseToJsonElement(encoded) as JsonObject).mapValues { it.value.jsonPrimitive.content }
        }.getOrDefault(emptyMap())
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
