package dev.phoneagent.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.Settings
import dev.phoneagent.core.Action
import dev.phoneagent.core.ActionKind
import dev.phoneagent.core.DeviceGateway
import dev.phoneagent.core.ExecutionResult
import dev.phoneagent.core.Observation
import dev.phoneagent.core.ShellOp
import dev.phoneagent.core.SafetyPolicy
import dev.phoneagent.core.Verification
import dev.phoneagent.core.VerificationKind
import dev.phoneagent.core.VerificationResult
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Execution adapter; the TaskEngine remains the sole owner of confirmation and retries. */
class AndroidDeviceGateway(
    context: Context,
    private val privilegedShell: PrivilegedShell,
    private val apiHosts: () -> Set<String>
) : DeviceGateway {
    private val context = context.applicationContext
    private val executionMutex = Mutex()
    private val safetyPolicy = SafetyPolicy()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return GatewayPolicy.publicDnsAddresses(Dns.SYSTEM.lookup(hostname))
            }
        })
        .build()

    override suspend fun observe(withScreenshot: Boolean): Observation =
        PhoneAccessibilityService.instance?.observation(withScreenshot) ?: Observation()

    override suspend fun execute(action: Action): ExecutionResult = executionMutex.withLock {
        try {
            when (action.kind) {
                ActionKind.INTENT -> executeIntent(action)
                ActionKind.DEEP_LINK -> executeDeepLink(action)
                ActionKind.API -> executeApi(action)
                ActionKind.SHELL -> {
                    if (action.shellOp == ShellOp.VOLUME) {
                        val max = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        if (action.value !in 0..max) return@withLock ExecutionResult(false, "Volume must be within this device's range 0–$max", false)
                    }
                    privilegedShell.execute(action)
                }
                ActionKind.UI_CLICK, ActionKind.UI_SET_TEXT, ActionKind.UI_SCROLL, ActionKind.GLOBAL_BACK, ActionKind.GLOBAL_HOME ->
                    if (action.kind in setOf(ActionKind.UI_CLICK, ActionKind.UI_SET_TEXT, ActionKind.UI_SCROLL) && action.packageName.isNullOrBlank()) {
                        ExecutionResult(false, "Screen actions require a foreground-bound target package", false)
                    } else {
                        PhoneAccessibilityService.instance?.executeUi(action)
                            ?: ExecutionResult(false, "Enable Phone Agent's accessibility service first", false)
                    }
                ActionKind.WAIT -> {
                    val duration = action.value ?: return@withLock ExecutionResult(false, "Missing wait duration", false)
                    if (duration !in 1..5_000) return@withLock ExecutionResult(false, "Wait duration is outside 1–5000 ms", false)
                    delay(duration.toLong())
                    ExecutionResult(true, "Wait elapsed; completion requires verification")
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            // Exception strings may contain URLs, account data or model-supplied bodies.
            val potentiallyDispatched = action.kind in setOf(ActionKind.UI_CLICK, ActionKind.UI_SET_TEXT, ActionKind.UI_SCROLL, ActionKind.GLOBAL_BACK, ActionKind.GLOBAL_HOME)
            ExecutionResult(potentiallyDispatched, "Device operation failed (${error.javaClass.simpleName}); ${if (potentiallyDispatched) "effect is uncertain" else "not dispatched"}", retryable = !potentiallyDispatched)
        }
    }

    private suspend fun executeIntent(action: Action): ExecutionResult {
        val requested = action.intentAction ?: return ExecutionResult(false, "Missing Intent action", false)
        if (requested == Intent.ACTION_VIEW) return executeDeepLink(action)
        if (requested in setOf(Intent.ACTION_DIAL, Intent.ACTION_SENDTO)) {
            val raw = action.uri ?: return ExecutionResult(false, "Draft Intent requires a recipient URI", false)
            if (!GatewayPolicy.isAllowedDraftIntent(requested, raw)) return ExecutionResult(false, "Unsafe dial or message draft URI", false)
            val intent = Intent(requested, Uri.parse(raw))
            action.packageName?.let(intent::setPackage)
            // DIAL / SENDTO open a visible draft only; no ACTION_CALL or direct send is accepted.
            return resolveAndLaunch(intent)
        }
        if (requested == Intent.ACTION_MAIN) {
            val target = action.packageName ?: return ExecutionResult(false, "App launch requires an exact package", false)
            val intent = context.packageManager.getLaunchIntentForPackage(target)
                ?: return ExecutionResult(false, "No launchable activity for $target", false)
            return launchAndObserve(intent, target)
        }
        if (!GatewayPolicy.isAllowedSettingsIntent(requested)) {
            return ExecutionResult(false, "Intent action is outside the fixed settings/launcher allowlist", false)
        }
        val intent = Intent(requested)
        if (action.uri != null) {
            if (requested != Settings.ACTION_APPLICATION_DETAILS_SETTINGS ||
                !action.uri!!.matches(Regex("package:[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
                return ExecutionResult(false, "Only an app-details settings Intent may contain a package URI", false)
            }
            intent.data = Uri.parse(action.uri)
        }
        action.packageName?.let(intent::setPackage)
        return resolveAndLaunch(intent)
    }

    private suspend fun executeDeepLink(action: Action): ExecutionResult {
        val raw = action.uri ?: return ExecutionResult(false, "Missing deep link URI", false)
        if (!GatewayPolicy.isAllowedDeepLink(raw, action.packageName)) return ExecutionResult(false, "Unsafe or untargeted deep link rejected", false)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(raw)).addCategory(Intent.CATEGORY_BROWSABLE)
        action.packageName?.let(intent::setPackage)
        return resolveAndLaunch(intent)
    }

    private suspend fun resolveAndLaunch(intent: Intent): ExecutionResult {
        @Suppress("DEPRECATION")
        val info = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?: return ExecutionResult(false, "No default app can resolve this action", false)
        val target = info.activityInfo?.packageName
            ?: return ExecutionResult(false, "Resolved action has no foreground app", false)
        if (target in setOf("android", "com.android.intentresolver") || info.activityInfo.name.contains("ResolverActivity")) {
            return ExecutionResult(false, "Choose a default application first; a chooser is not task completion", false)
        }
        intent.setPackage(target)
        return launchAndObserve(intent, target)
    }

    private suspend fun launchAndObserve(intent: Intent, expectedPackage: String): ExecutionResult {
        withContext(Dispatchers.Main.immediate) {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        try {
            repeat(10) {
                delay(250)
                val observed = observe()
                if (observed.packageName == expectedPackage) {
                    return ExecutionResult(true, "Intent dispatched; independently observed foreground $expectedPackage")
                }
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return ExecutionResult(true, "Intent dispatched but foreground observation failed; effect is uncertain", retryable = false)
        }
        // Avoid falling through to another strategy when a dispatched Intent may still take effect.
        return ExecutionResult(true, "Intent dispatched, but foreground $expectedPackage is unverified; final verification must decide", retryable = false)
    }

    private suspend fun executeApi(action: Action): ExecutionResult = withContext(Dispatchers.IO) {
        val api = action.api ?: return@withContext ExecutionResult(false, "Missing API request", false)
        val allowed = apiHosts().map { it.trim().lowercase() }.toSet()
        val uri = GatewayPolicy.allowedApiUri(api.url, allowed)
            ?: return@withContext ExecutionResult(false, "API URL must use HTTPS port 443 on an explicitly allowed public host", false)
        if (api.method !in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")) {
            return@withContext ExecutionResult(false, "Unsupported API method", false)
        }
        if (api.body != null && api.body!!.length > 32_768) return@withContext ExecutionResult(false, "API body is too large", false)
        if (api.method in setOf("GET", "HEAD") && api.body != null) return@withContext ExecutionResult(false, "Read requests cannot contain a body", false)
        // No global API key or model-provider credential is forwarded to task endpoints.
        val body = if (api.method in setOf("POST", "PUT", "PATCH")) (api.body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType())
            else api.body?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(api.url).method(api.method, body).build()
        val safelyRepeatable = safetyPolicy.isSafelyRepeatable(action)
        try {
            http.newCall(request).awaitResponse().use { response ->
                ExecutionResult(true, "HTTPS response received from ${uri.host}: HTTP ${response.code}", retryable = safelyRepeatable, apiStatus = response.code)
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            val uncertainEffect = !safelyRepeatable
            // A sensitive GET can also commit an effect before its response is lost.
            ExecutionResult(uncertainEffect, "API ${if (uncertainEffect) "operation outcome is uncertain" else "read failed"} (${error.javaClass.simpleName})", retryable = safelyRepeatable)
        }
    }

    override suspend fun verify(verification: Verification, observation: Observation, result: ExecutionResult): VerificationResult {
        return when (verification.kind) {
            VerificationKind.FOREGROUND_PACKAGE -> VerificationResult(
                observation.packageName == verification.expected,
                "Observed foreground=${observation.packageName.ifBlank { "unknown" }}; expected=${verification.expected}"
            )
            VerificationKind.TEXT_PRESENT, VerificationKind.TEXT_ABSENT -> {
                fun check(snapshot: Observation): VerificationResult {
                    if (snapshot.packageName.isBlank() || snapshot.nodes.isEmpty()) return VerificationResult(false, "No observable foreground content; absence cannot be proven")
                    if (verification.kind == VerificationKind.TEXT_ABSENT && snapshot.nodes.any { it.viewId == PhoneAccessibilityService.INCOMPLETE_VIEW_ID }) {
                        return VerificationResult(false, "Accessibility tree is incomplete; absence cannot be proven")
                    }
                    val exists = snapshot.nodes.any { !it.password && (it.text.contains(verification.expected, ignoreCase = true) || it.description.contains(verification.expected, ignoreCase = true)) }
                    val satisfied = if (verification.kind == VerificationKind.TEXT_PRESENT) exists else !exists
                    return VerificationResult(satisfied, "Fresh accessibility content ${if (exists) "contains" else "does not contain"} requested text")
                }
                var evidence = check(observation)
                var previousAbsence = evidence.satisfied && verification.kind == VerificationKind.TEXT_ABSENT
                repeat(8) {
                    if (evidence.satisfied && verification.kind == VerificationKind.TEXT_PRESENT) return evidence
                    delay(250)
                    evidence = check(observe())
                    if (verification.kind == VerificationKind.TEXT_ABSENT && evidence.satisfied && previousAbsence) return evidence
                    previousAbsence = evidence.satisfied
                }
                if (verification.kind == VerificationKind.TEXT_ABSENT && evidence.satisfied) {
                    VerificationResult(false, "Text absence has not stabilized across two fresh observations")
                } else evidence
            }
            VerificationKind.SYSTEM_SETTING -> withContext(Dispatchers.IO) {
                suspend fun readActual(): Int? = runCatching {
                    when (verification.key) {
                        "wifi_on" -> {
                            val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                            if (wifi.isWifiEnabled) 1 else 0
                        }
                        "bluetooth_on" -> Settings.Global.getInt(context.contentResolver, "bluetooth_on")
                        "screen_brightness" -> Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                        "volume_music" -> (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamVolume(AudioManager.STREAM_MUSIC)
                        else -> null
                    }
                }.getOrNull()
                var value = readActual()
                // WiFi and OEM settings services apply asynchronously; read real state for up to 2 s.
                repeat(8) {
                    if (value?.toString() == verification.expected) return@withContext VerificationResult(true, "Read setting ${verification.key}: $value; expected=${verification.expected}")
                    delay(250)
                    value = readActual()
                }
                VerificationResult(value != null && value.toString() == verification.expected, "Read setting ${verification.key}: ${value ?: "unavailable"}; expected=${verification.expected}")
            }
            VerificationKind.API_STATUS -> VerificationResult(
                result.apiStatus != null && result.apiStatus.toString() == verification.expected,
                "Actual HTTP status=${result.apiStatus ?: "unavailable"}; expected=${verification.expected}"
            )
        }
    }
}

/** Pure policies have JVM tests; Android invocation remains in the adapter. */
internal object GatewayPolicy {
    private val settingsActions = setOf(
        "android.settings.SETTINGS", "android.settings.WIFI_SETTINGS", "android.settings.WIRELESS_SETTINGS",
        "android.settings.BLUETOOTH_SETTINGS", "android.settings.DISPLAY_SETTINGS", "android.settings.SOUND_SETTINGS",
        "android.settings.ACCESSIBILITY_SETTINGS", "android.settings.APPLICATION_DETAILS_SETTINGS",
        "android.settings.APPLICATION_SETTINGS", "android.settings.LOCALE_SETTINGS", "android.settings.DATE_SETTINGS",
        "android.settings.LOCATION_SOURCE_SETTINGS", "android.settings.BATTERY_SAVER_SETTINGS",
        "android.settings.INTERNAL_STORAGE_SETTINGS", "android.settings.NOTIFICATION_SETTINGS"
    )
    fun isAllowedSettingsIntent(action: String): Boolean = action in settingsActions

    fun isAllowedDraftIntent(action: String, raw: String): Boolean {
        if (raw.length > 8_000 || raw.any { it.code < 32 }) return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (action == "android.intent.action.DIAL") {
            return scheme == "tel" && uri.rawSchemeSpecificPart.matches(Regex("[+0-9(). -]{1,80}"))
        }
        if (action != "android.intent.action.SENDTO") return false
        if (scheme in setOf("sms", "smsto")) {
            val recipient = uri.schemeSpecificPart.substringBefore('?')
            val query = uri.rawSchemeSpecificPart.substringAfter('?', "")
            return recipient.matches(Regex("[+0-9,;(). -]{1,160}")) &&
                (query.isEmpty() || query.split('&').all { it.substringBefore('=') == "body" })
        }
        if (scheme == "mailto") {
            val recipient = uri.schemeSpecificPart.substringBefore('?')
            val query = uri.rawSchemeSpecificPart.substringAfter('?', "")
            return recipient.isNotBlank() && recipient.length <= 320 && '@' in recipient && recipient.none { it.code < 32 } &&
                !recipient.contains('/') && (query.isEmpty() || query.split('&').all { it.substringBefore('=').lowercase() in setOf("subject", "body", "cc", "bcc") })
        }
        return false
    }

    fun isAllowedDeepLink(raw: String, packageName: String?): Boolean {
        if (raw.length > 8_000 || raw.any { it.code < 32 }) return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme in setOf("intent", "file", "content", "data", "javascript", "package", "tel", "sms", "smsto", "mailto")) return false
        if (scheme in setOf("http", "https")) return !uri.host.isNullOrBlank() && uri.userInfo == null
        if (scheme == "geo") return true
        return !packageName.isNullOrBlank() && scheme.matches(Regex("[a-z][a-z0-9+.-]{1,32}"))
    }

    fun allowedApiUri(raw: String, allowedHosts: Set<String>): URI? {
        if (raw.length > 8_000 || raw.any { it.code < 32 }) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.scheme != "https" || uri.port !in setOf(-1, 443) || uri.userInfo != null || uri.fragment != null) return null
        if (host !in allowedHosts || host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") || host.matches(Regex("[0-9.]+")) || ':' in host) return null
        return uri
    }

    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        if (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) return false
        if (bytes.size == 4 && (bytes[0].toInt() and 255) == 100 && (bytes[1].toInt() and 255) in 64..127) return false
        return true
    }

    @Throws(UnknownHostException::class)
    fun publicDnsAddresses(addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
            // OkHttp routes IOExceptions to onFailure; unchecked exceptions can crash its worker.
            throw UnknownHostException("API DNS resolved to an empty, private, or local address set")
        }
        return addresses
    }
}
