package dev.phoneagent.core

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Safety never trusts the planner's sensitive flag as an exemption. */
class SafetyPolicy {
    fun confirmationReason(action: Action, observation: Observation): String? {
        if (action.sensitive) return "The plan marks this operation as sensitive."
        if (action.kind == ActionKind.API && action.api?.method !in setOf("GET", "HEAD")) return "An API write can change or delete account data."
        if (action.kind in setOf(ActionKind.UI_CLICK, ActionKind.UI_SET_TEXT, ActionKind.UI_SCROLL)) {
            val target = matchedNodes(action, observation).joinToString(" ") { it.text + " " + it.description }
            if (sensitiveWords.containsMatchIn(target + " " + action.description + " " + (action.text ?: ""))) return "This screen operation may pay, delete data, or send a message."
            return "A screen operation can change app data; approve this exact target."
        }
        operationRisk(action)?.let { return it }
        if (action.kind == ActionKind.INTENT && action.intentAction in setOf("android.intent.action.DIAL", "android.intent.action.SENDTO", "android.intent.action.SEND", "android.intent.action.SEND_MULTIPLE")) return "Calling or messaging requires confirmation."
        if (action.kind == ActionKind.DEEP_LINK || (action.kind == ActionKind.INTENT && action.intentAction == "android.intent.action.VIEW")) {
            val scheme = action.uri?.let { runCatching { URI(it).scheme?.lowercase() }.getOrNull() }
            if (scheme !in setOf("https", "http", "geo")) return "An app-specific link may perform a sensitive operation."
        }
        return null
    }

    fun isSafelyRepeatable(action: Action): Boolean {
        if (action.sensitive) return false
        if (operationRisk(action) != null) return false
        return when (action.kind) {
            ActionKind.API -> action.api?.method in setOf("GET", "HEAD")
            ActionKind.SHELL, ActionKind.WAIT, ActionKind.GLOBAL_HOME -> true
            ActionKind.INTENT -> action.intentAction == "android.intent.action.MAIN" || action.intentAction?.startsWith("android.settings.") == true || (action.intentAction == "android.intent.action.VIEW" && safeNavigationUri(action.uri))
            ActionKind.DEEP_LINK -> safeNavigationUri(action.uri)
            else -> false
        }
    }

    fun validateUiTarget(action: Action, observation: Observation): Action {
        if (action.kind !in setOf(ActionKind.UI_CLICK, ActionKind.UI_SET_TEXT, ActionKind.UI_SCROLL)) return action
        require(observation.packageName.isNotBlank()) { "Foreground app is unknown" }
        action.packageName?.let { require(it == observation.packageName) { "Foreground app changed" } }
        if (action.kind == ActionKind.UI_SCROLL) {
            require(observation.nodes.isNotEmpty()) { "Cannot scroll an unknown screen" }
            return action.copy(packageName = observation.packageName)
        }
        if (action.x != null) require(action.x < observation.width && action.y!! < observation.height && observation.width > 0 && observation.height > 0) { "Coordinates fall outside the fresh screenshot" }
        val matches = matchedNodes(action, observation)
        require(matches.size == 1) { "UI target is missing or ambiguous (${matches.size} matches)" }
        val node = matches.single()
        require(node.enabled && !node.password) { "Target is disabled or contains credentials" }
        require(if (action.kind == ActionKind.UI_SET_TEXT) node.editable else node.clickable) { "Target does not support this operation" }
        require(node.bounds.right > node.bounds.left && node.bounds.bottom > node.bounds.top) { "Target bounds are empty" }
        return action.copy(packageName = observation.packageName)
    }

    fun fingerprint(observation: Observation): String {
        // Ignore timestamps/render noise. Coordinate actions must still resolve to a unique node.
        val stable = observation.copy(capturedAt = 0, screenshotBase64 = null, nodes = observation.nodes.sortedBy { it.nodeId })
        return hash(Json.encodeToString(stable))
    }

    fun actionFingerprint(action: Action): String = hash(Json.encodeToString(action))

    /** Descriptive model metadata cannot disguise an already completed side effect. */
    fun effectFingerprint(action: Action): String = actionFingerprint(action.copy(description = "", sensitive = false))

    private fun matchedNodes(action: Action, observation: Observation): List<UiNode> {
        val selector = action.selector
        if (selector != null) return observation.nodes.filter { node ->
            (selector.text == null || node.text == selector.text) &&
                (selector.viewId == null || node.viewId == selector.viewId) &&
                (selector.description == null || node.description == selector.description)
        }
        if (action.x != null && action.y != null) return observation.nodes.filter { node ->
            node.clickable && action.x >= node.bounds.left && action.x < node.bounds.right && action.y >= node.bounds.top && action.y < node.bounds.bottom
        }
        return emptyList()
    }

    private fun safeNavigationUri(uri: String?): Boolean = uri?.let { runCatching { URI(it).scheme?.lowercase() in setOf("http", "https", "geo") }.getOrDefault(false) } ?: false

    private fun operationRisk(action: Action): String? {
        if (sensitiveWords.containsMatchIn(listOfNotNull(action.intentAction, action.description).joinToString(" "))) return SENSITIVE_OPERATION
        for (rawUri in listOfNotNull(action.uri, action.api?.url)) {
            val canonical = canonicalUri(rawUri)
                ?: return "The encoded URL cannot be safely inspected; approve it before execution."
            if (sensitiveWords.containsMatchIn(canonical)) return SENSITIVE_OPERATION
            // HTTP verbs are not a guarantee: nonconforming endpoints can mutate through GET.
            // Explicit operation selectors and credentials are reviewed even without a known verb.
            val query = canonical.substringAfter('?', "").substringBefore('#')
            val keys = query.split('&', ';').map { it.substringBefore('=').trim().lowercase().replace('-', '_') }
            if (keys.any { it in operationQueryKeys }) return "The URL selects an operation whose effects need confirmation."
            if (keys.any { it in credentialQueryKeys }) return "The URL includes account credentials; approve the exact destination."
        }
        return null
    }

    /** Decode nested escaping a bounded number of times; malformed/remaining encoding fails closed. */
    private fun canonicalUri(raw: String): String? {
        if (raw.length > 8_000 || runCatching { URI(raw).isAbsolute }.getOrDefault(false).not()) return null
        var decoded = raw
        repeat(4) {
            val next = runCatching { URLDecoder.decode(decoded, Charsets.UTF_8.name()) }.getOrNull() ?: return null
            if (next.length > 8_000 || next.any { it.code < 32 || it == '\uFFFD' }) return null
            if (next == decoded) return next
            decoded = next
        }
        return decoded.takeUnless { '%' in it }
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        private const val SENSITIVE_OPERATION = "This operation may pay, delete data, or send a message."
        private val operationQueryKeys = setOf("action", "op", "operation", "command", "cmd", "method", "do", "execute", "submit", "mutation", "function")
        private val credentialQueryKeys = setOf("token", "access_token", "refresh_token", "auth_token", "id_token", "api_key", "apikey", "password", "secret", "credential", "credentials", "authorization", "auth", "session", "sessionid", "session_id", "jwt", "bearer")
        // Include endpoints and common English/Chinese labels; generic UI still always confirms.
        private val sensitiveWords = Regex("pay|payment|purchase|checkout|transfer|delete|remove|send|sms|message|mail|拨号|支付|付款|转账|购买|删除|清空|发送|消息|邮件", RegexOption.IGNORE_CASE)
    }
}
