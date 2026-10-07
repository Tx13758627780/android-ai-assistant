package dev.phoneagent.core

import java.net.URI

/** The model is an untrusted input source. This validator is also used before dispatch. */
class PlanValidator {
    fun validate(plan: Plan): Plan {
        require(plan.summary.isNotBlank() && plan.summary.length <= 2_000) { "Missing or oversized plan summary" }
        require(plan.steps.size in 1..24) { "A plan must contain 1–24 steps" }
        require(plan.steps.map { it.id }.distinct().size == plan.steps.size) { "Step ids must be unique" }
        val steps = plan.steps.map { step ->
            require(step.id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid step id" }
            require(step.title.isNotBlank() && step.title.length <= 1_000) { "Invalid step title" }
            require(step.alternatives.size in 1..8) { "A step must contain 1–8 executable alternatives" }
            require(step.maxAttempts in 1..3) { "Retry budget must be 1–3" }
            validateVerification(step.verification)
            step.copy(alternatives = step.alternatives.map(::validateAction).sortedBy { priority(it.kind) })
        }
        return plan.copy(steps = steps)
    }

    fun validateAction(action: Action): Action {
        require(action.description.length <= 2_000) { "Action description is too long" }
        action.packageName?.let { require(it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "Invalid package name" } }
        val hasCoordinates = action.x != null || action.y != null
        require(!hasCoordinates || (action.x != null && action.y != null && action.x >= 0 && action.y >= 0)) { "Coordinates require nonnegative x and y" }
        action.selector?.let { selector ->
            require(listOfNotNull(selector.text, selector.viewId, selector.description).any { it.isNotBlank() }) { "Empty selector" }
            require(listOfNotNull(selector.text, selector.viewId, selector.description).all { it.isNotBlank() && it.length <= 1_000 }) { "Invalid selector" }
        }
        when (action.kind) {
            ActionKind.INTENT -> {
                require(action.intentAction in allowedIntentActions) { "Intent action is not allowed" }
                require(action.selector == null && !hasCoordinates && action.shellOp == null && action.api == null && action.text == null && action.value == null) { "Unexpected Intent fields" }
                action.uri?.let { validateUri(it, action.intentAction == "android.settings.APPLICATION_DETAILS_SETTINGS") }
                if (action.intentAction in setOf("android.intent.action.VIEW", "android.intent.action.SENDTO", "android.intent.action.DIAL")) require(!action.uri.isNullOrBlank()) { "Intent requires a URI" }
                if (action.intentAction == "android.intent.action.MAIN") require(action.packageName != null) { "Launching an app requires a package" }
                if (action.intentAction == "android.intent.action.VIEW") validateDeepLink(action)
                if (action.intentAction == "android.intent.action.DIAL") require(URI(action.uri!!).scheme == "tel") { "Dial requires a tel draft URI" }
                if (action.intentAction == "android.intent.action.SENDTO") require(URI(action.uri!!).scheme in setOf("sms", "smsto", "mailto")) { "Messaging requires a draft URI" }
            }
            ActionKind.DEEP_LINK -> {
                require(!action.uri.isNullOrBlank()) { "DeepLink requires a URI" }
                validateUri(action.uri)
                validateDeepLink(action)
                require(action.intentAction == null && action.selector == null && !hasCoordinates && action.shellOp == null && action.api == null && action.text == null && action.value == null) { "Unexpected DeepLink fields" }
            }
            ActionKind.API -> {
                val request = requireNotNull(action.api) { "API action requires a request" }
                validateHttpsUrl(request.url)
                require(request.method in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")) { "Unsupported API method" }
                require(request.body == null || request.body.length <= 32_768) { "API body is too large" }
                require(request.method !in setOf("GET", "HEAD") || request.body == null) { "Read requests cannot have a body" }
                require(action.intentAction == null && action.uri == null && action.selector == null && !hasCoordinates && action.shellOp == null && action.text == null && action.value == null && action.packageName == null) { "Unexpected API fields" }
            }
            ActionKind.SHELL -> {
                val operation = requireNotNull(action.shellOp) { "Only typed, allowlisted Shell operations are supported" }
                when (operation) {
                    ShellOp.BRIGHTNESS -> require(action.value in 0..255) { "Brightness must be 0–255" }
                    ShellOp.VOLUME -> require(action.value in 0..15) { "Volume must be 0–15" }
                    else -> require(action.value == null) { "Unexpected shell value" }
                }
                require(action.intentAction == null && action.uri == null && action.selector == null && !hasCoordinates && action.api == null && action.text == null && action.packageName == null) { "Raw shell commands are forbidden" }
            }
            ActionKind.UI_CLICK -> {
                require(action.packageName != null) { "UI actions must identify the expected foreground package" }
                require((action.selector != null) xor hasCoordinates) { "A click requires one selector or one coordinate pair" }
                requireNoStructuredPayload(action)
                require(action.text == null && action.value == null) { "Unexpected click payload" }
            }
            ActionKind.UI_SET_TEXT -> {
                require(action.packageName != null) { "UI actions must identify the expected foreground package" }
                require(action.selector != null && !hasCoordinates && action.text != null && action.text.length <= 8_000) { "Text entry requires a selector and bounded text" }
                requireNoStructuredPayload(action)
                require(action.value == null) { "Unexpected text-entry value" }
            }
            ActionKind.UI_SCROLL -> {
                require(action.packageName != null) { "UI actions must identify the expected foreground package" }
                require(action.selector == null && !hasCoordinates && action.text in setOf("forward", "backward")) { "Scroll direction must be forward or backward" }
                requireNoStructuredPayload(action)
                require(action.value == null) { "Unexpected scroll value" }
            }
            ActionKind.GLOBAL_BACK, ActionKind.GLOBAL_HOME -> {
                requireNoStructuredPayload(action)
                require(action.selector == null && !hasCoordinates && action.text == null && action.value == null && action.packageName == null) { "Unexpected global action fields" }
            }
            ActionKind.WAIT -> {
                requireNoStructuredPayload(action)
                require(action.value in 1..5_000 && action.selector == null && !hasCoordinates && action.text == null && action.packageName == null) { "Wait must be 1–5000 ms" }
            }
        }
        return action
    }

    private fun requireNoStructuredPayload(action: Action) {
        require(action.intentAction == null && action.uri == null && action.shellOp == null && action.api == null) { "Unexpected action fields" }
    }

    private fun validateVerification(verification: Verification) {
        require(verification.expected.isNotBlank() && verification.expected.length <= 2_000) { "Verification needs bounded evidence" }
        when (verification.kind) {
            VerificationKind.FOREGROUND_PACKAGE -> require(verification.expected.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "Invalid package verification" }
            VerificationKind.SYSTEM_SETTING -> {
                require(verification.key in setOf("wifi_on", "bluetooth_on", "screen_brightness", "volume_music")) { "Unsupported setting verification" }
                val expected = verification.expected.toIntOrNull()
                val allowed = when (verification.key) { "wifi_on", "bluetooth_on" -> 0..1; "screen_brightness" -> 0..255; else -> 0..15 }
                require(expected in allowed) { "Setting evidence is outside the allowed range" }
            }
            VerificationKind.API_STATUS -> require(verification.expected.toIntOrNull() in 100..599) { "Invalid HTTP status verification" }
            else -> Unit
        }
    }

    private fun validateUri(value: String, allowPackage: Boolean = false) {
        require(value.length <= 8_000 && value.none { it.code < 32 }) { "Invalid URI" }
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("Malformed URI") }
        require(!uri.scheme.isNullOrBlank() && uri.scheme.lowercase() !in setOf("javascript", "file", "content", "intent", "data")) { "Unsafe URI scheme" }
        if (uri.scheme.lowercase() in setOf("http", "https")) {
            require(!uri.host.isNullOrBlank() && uri.userInfo == null) { "URI needs a host without credentials" }
        }
        if (uri.scheme == "package") require(allowPackage) { "Package URI only allowed for app settings" }
    }

    private fun validateHttpsUrl(value: String) {
        validateUri(value)
        val uri = URI(value)
        require(uri.scheme == "https" && uri.port in setOf(-1, 443) && uri.fragment == null) { "API requests require HTTPS on port 443 without a fragment" }
        val host = uri.host.lowercase()
        require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local") && !host.endsWith(".internal") && !host.matches(Regex("[0-9.]+")) && ':' !in host) { "API host must be a public DNS name" }
    }

    private fun validateDeepLink(action: Action) {
        val scheme = URI(action.uri!!).scheme.lowercase()
        require(scheme !in setOf("tel", "sms", "smsto", "mailto", "package")) { "Calling or messaging must use a confirmed draft Intent" }
        require(scheme in setOf("https", "http", "geo") || action.packageName != null) { "App-specific DeepLinks require an explicit package" }
    }

    companion object {
        val allowedIntentActions = setOf(
            "android.intent.action.MAIN", "android.intent.action.VIEW", "android.intent.action.DIAL", "android.intent.action.SENDTO",
            "android.settings.SETTINGS", "android.settings.WIFI_SETTINGS", "android.settings.WIRELESS_SETTINGS",
            "android.settings.BLUETOOTH_SETTINGS", "android.settings.DISPLAY_SETTINGS", "android.settings.SOUND_SETTINGS",
            "android.settings.ACCESSIBILITY_SETTINGS", "android.settings.APPLICATION_DETAILS_SETTINGS", "android.settings.APPLICATION_SETTINGS",
            "android.settings.LOCALE_SETTINGS", "android.settings.DATE_SETTINGS", "android.settings.LOCATION_SOURCE_SETTINGS",
            "android.settings.BATTERY_SAVER_SETTINGS", "android.settings.INTERNAL_STORAGE_SETTINGS", "android.settings.NOTIFICATION_SETTINGS"
        )
        fun priority(kind: ActionKind): Int = when (kind) {
            ActionKind.INTENT -> 0
            ActionKind.DEEP_LINK -> 1
            ActionKind.API -> 2
            ActionKind.SHELL -> 3
            ActionKind.GLOBAL_HOME, ActionKind.GLOBAL_BACK -> 4
            ActionKind.UI_CLICK, ActionKind.UI_SET_TEXT, ActionKind.UI_SCROLL -> 5
            ActionKind.WAIT -> 6
        }
    }
}
