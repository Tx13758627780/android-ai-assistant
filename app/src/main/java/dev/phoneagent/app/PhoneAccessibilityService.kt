package dev.phoneagent.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.phoneagent.core.Action
import dev.phoneagent.core.ActionKind
import dev.phoneagent.core.Bounds
import dev.phoneagent.core.ExecutionResult
import dev.phoneagent.core.Observation
import dev.phoneagent.core.Selector
import dev.phoneagent.core.UiNode
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Public Android APIs only. Node handles are never kept between observations and actions. */
class PhoneAccessibilityService : AccessibilityService() {
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        if (instance === this) instance = null
        screenshotExecutor.shutdown()
        super.onDestroy()
    }

    suspend fun observation(withScreenshot: Boolean): Observation {
        val snapshot = withContext(Dispatchers.Main.immediate) {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val root = activeAppRoot()
            if (root == null) {
                Observation(width = metrics.widthPixels, height = metrics.heightPixels)
            } else {
                withNodes(root) { records ->
                    Observation(
                        packageName = root.packageName?.toString().orEmpty(),
                        width = metrics.widthPixels,
                        height = metrics.heightPixels,
                        nodes = records.filter { it.node.isVisibleToUser }.map { record ->
                            val node = record.node
                            val rect = Rect().also(node::getBoundsInScreen)
                            UiNode(
                                nodeId = record.path,
                                text = if (node.isPassword) "[redacted]" else node.text?.toString().orEmpty().take(MAX_TEXT),
                                description = if (node.isPassword) "[redacted]" else node.contentDescription?.toString().orEmpty().take(MAX_TEXT),
                                viewId = node.viewIdResourceName.orEmpty(),
                                bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
                                clickable = ancestor(record, records) { it.isClickable } != null,
                                editable = node.isEditable,
                                enabled = node.isEnabled,
                                password = node.isPassword
                            )
                        } + if (isComplete(records)) emptyList() else listOf(
                            UiNode("__snapshot_incomplete", viewId = INCOMPLETE_VIEW_ID, bounds = Bounds(0, 0, 0, 0), enabled = false)
                        )
                    )
                }
            }
        }
        if (!withScreenshot) return snapshot
        // Never expose password-bearing screens to the remote vision planner.
        if (snapshot.nodes.any { it.password }) return snapshot
        val screenshot = screenshot() ?: return snapshot
        val after = observation(withScreenshot = false)
        if (after.packageName != snapshot.packageName || after.nodes != snapshot.nodes || after.nodes.any { it.password }) return after
        return snapshot.copy(screenshotBase64 = screenshot.base64, width = screenshot.width, height = screenshot.height)
    }

    suspend fun executeUi(action: Action): ExecutionResult = when (action.kind) {
        ActionKind.GLOBAL_BACK -> global(GLOBAL_ACTION_BACK)
        ActionKind.GLOBAL_HOME -> global(GLOBAL_ACTION_HOME)
        ActionKind.UI_CLICK, ActionKind.UI_SET_TEXT, ActionKind.UI_SCROLL -> nodeAction(action)
        else -> ExecutionResult(false, "Unsupported accessibility action", retryable = false)
    }

    private suspend fun global(code: Int): ExecutionResult = withContext(Dispatchers.Main.immediate) {
        ExecutionResult(performGlobalAction(code), "Accessibility global action dispatched; completion requires verification")
    }

    private suspend fun nodeAction(action: Action): ExecutionResult {
        // Resolve on the current tree immediately before the operation; planner node IDs are not handles.
        val prepared = withContext(Dispatchers.Main.immediate) {
            val root = activeAppRoot() ?: return@withContext Prepared.Failed("No active accessibility window")
            withNodes(root) { records ->
                if (!isComplete(records)) return@withNodes Prepared.Failed("Accessibility tree exceeded safety bounds; cannot prove a unique target", false)
                val foreground = root.packageName?.toString().orEmpty()
                if (action.packageName != null && foreground != action.packageName) {
                    return@withNodes Prepared.Failed("Foreground changed: expected ${action.packageName}, observed $foreground")
                }
                val selector = action.selector
                if (selector == null && action.kind == ActionKind.UI_CLICK && action.x != null && action.y != null) {
                    val x = action.x!!; val y = action.y!!
                    val screen = Rect().also(root::getBoundsInScreen)
                    if (!screen.contains(x, y)) return@withNodes Prepared.Failed("Tap point is outside active window", false)
                    val candidates = records.filter { record ->
                        val n = record.node
                        n.isVisibleToUser && n.isEnabled && n.isClickable && !n.isPassword &&
                            Rect().also(n::getBoundsInScreen).contains(x, y)
                    }
                    if (candidates.size != 1) return@withNodes Prepared.Failed("Tap requires one uniquely identifiable clickable control")
                    return@withNodes Prepared.Tap(x.toFloat(), y.toFloat())
                }
                if (selector == null && action.kind == ActionKind.UI_SCROLL) {
                    val scrollables = records.filter { it.node.isVisibleToUser && it.node.isEnabled && it.node.isScrollable }
                    val topLevel = scrollables.filter { candidate ->
                        var parent = records.firstOrNull { it.path == candidate.parentPath }
                        var nested = false
                        while (parent != null) {
                            if (parent.node.isScrollable) { nested = true; break }
                            parent = records.firstOrNull { it.path == parent!!.parentPath }
                        }
                        !nested
                    }
                    if (topLevel.size != 1) return@withNodes Prepared.Failed("Scroll requires one unambiguous visible scrolling container (${topLevel.size} found)")
                    val code = if (action.text == "backward") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    return@withNodes Prepared.Done(topLevel.single().node.performAction(code), "Scroll dispatched")
                }
                if (selector == null || !hasSelector(selector)) return@withNodes Prepared.Failed("An exact selector is required", false)
                val matched = records.filter { matches(it.node, selector) }
                if (matched.isEmpty()) return@withNodes Prepared.Failed("No visible node exactly matches the selector")
                if (matched.size != 1) return@withNodes Prepared.Failed("Selector matches ${matched.size} nodes; refusing ambiguous action")
                val record = matched.single()
                if (record.node.isPassword) return@withNodes Prepared.Failed("Password controls cannot be automated", false)
                when (action.kind) {
                    ActionKind.UI_SET_TEXT -> {
                        if (!record.node.isEditable) return@withNodes Prepared.Failed("Target is not editable", false)
                        val value = action.text ?: return@withNodes Prepared.Failed("Missing text", false)
                        if (value.length > MAX_INPUT) return@withNodes Prepared.Failed("Text exceeds $MAX_INPUT characters", false)
                        val args = android.os.Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                        }
                        Prepared.Done(record.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args), "Set text dispatched")
                    }
                    ActionKind.UI_SCROLL -> {
                        val target = ancestor(record, records) { it.isScrollable }
                            ?: return@withNodes Prepared.Failed("Target has no scrollable ancestor")
                        val backwards = action.text == "backward"
                        val code = if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        Prepared.Done(target.node.performAction(code), "Scroll dispatched")
                    }
                    ActionKind.UI_CLICK -> {
                        val target = ancestor(record, records) { it.isClickable }
                            ?: return@withNodes Prepared.Failed("Target has no clickable ancestor")
                        if (target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            Prepared.Done(true, "Exact accessibility click dispatched")
                        } else {
                            val rect = Rect().also(target.node::getBoundsInScreen)
                            if (rect.isEmpty) Prepared.Failed("Target has empty bounds")
                            else Prepared.Tap(rect.exactCenterX(), rect.exactCenterY())
                        }
                    }
                    else -> Prepared.Failed("Unsupported UI operation", false)
                }
            }
        }
        return when (prepared) {
            is Prepared.Failed -> ExecutionResult(false, prepared.reason, prepared.retryable)
            is Prepared.Done -> ExecutionResult(prepared.success, "${prepared.detail}; completion requires verification")
            is Prepared.Tap -> tap(prepared.x, prepared.y)
        }
    }

    private suspend fun tap(x: Float, y: Float): ExecutionResult {
        val dispatched = withTimeoutOrNull(2_500) {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 80))
                        .build()
                    val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(true)
                        }
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }, null)
                    if (!accepted && continuation.isActive) continuation.resume(false)
                }
            }
        } ?: false
        return ExecutionResult(dispatched, if (dispatched) "Gesture finished; completion requires verification" else "Gesture rejected, cancelled, or timed out")
    }

    private data class Screenshot(val base64: String, val width: Int, val height: Int)

    private suspend fun screenshot(): Screenshot? {
        if (Build.VERSION.SDK_INT < 30) return null
        return withTimeoutOrNull(4_500) {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { continuation ->
                    try {
                        takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
                            override fun onSuccess(result: ScreenshotResult) {
                                val buffer = result.hardwareBuffer
                                var hardware: Bitmap? = null
                                var software: Bitmap? = null
                                try {
                                    if (!continuation.isActive) return
                                    hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                    software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                                    val bitmap = software
                                    val screenshot = bitmap?.let {
                                        val bytes = ByteArrayOutputStream().use { output ->
                                            it.compress(Bitmap.CompressFormat.JPEG, 65, output)
                                            output.toByteArray()
                                        }
                                        if (bytes.size > MAX_SCREENSHOT_BYTES) null
                                        else Screenshot(Base64.encodeToString(bytes, Base64.NO_WRAP), it.width, it.height)
                                    }
                                    if (continuation.isActive) continuation.resume(screenshot)
                                } catch (_: Exception) {
                                    if (continuation.isActive) continuation.resume(null)
                                } finally {
                                    software?.recycle(); hardware?.recycle(); buffer.close()
                                }
                            }
                            override fun onFailure(errorCode: Int) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        })
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
        }
    }

    private data class Record(val path: String, val parentPath: String?, val node: AccessibilityNodeInfo)

    private fun activeAppRoot(): AccessibilityNodeInfo? {
        val currentWindows = windows
        return try {
            // The IME and accessibility overlays must not become the task's foreground app.
            currentWindows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isActive }
                    .thenByDescending { it.isFocused }.thenByDescending { it.layer })
                .firstOrNull()?.root ?: rootInActiveWindow
        } finally {
            @Suppress("DEPRECATION")
            currentWindows.forEach { it.recycle() }
        }
    }

    private fun <T> withNodes(root: AccessibilityNodeInfo, block: (List<Record>) -> T): T {
        val records = mutableListOf<Record>()
        fun visit(node: AccessibilityNodeInfo, path: String, parent: String?, depth: Int) {
            records += Record(path, parent, node)
            if (depth >= MAX_DEPTH) return
            for (index in 0 until node.childCount.coerceAtMost(MAX_CHILDREN)) {
                if (records.size >= MAX_NODES) break
                val child = node.getChild(index) ?: continue
                visit(child, "$path.$index", path, depth + 1)
            }
        }
        return try {
            visit(root, "0", null, 0)
            block(records)
        } finally {
            @Suppress("DEPRECATION")
            records.forEach { it.node.recycle() }
        }
    }

    private fun ancestor(record: Record, records: List<Record>, predicate: (AccessibilityNodeInfo) -> Boolean): Record? {
        var current: Record? = record
        repeat(6) {
            val candidate = current ?: return null
            if (candidate.node.isEnabled && candidate.node.isVisibleToUser && !candidate.node.isPassword && predicate(candidate.node)) return candidate
            current = records.firstOrNull { it.path == candidate.parentPath }
        }
        return null
    }

    private fun matches(node: AccessibilityNodeInfo, selector: Selector): Boolean =
        node.isVisibleToUser && node.isEnabled &&
            (selector.text == null || node.text?.toString() == selector.text) &&
            (selector.viewId == null || node.viewIdResourceName == selector.viewId) &&
            (selector.description == null || node.contentDescription?.toString() == selector.description)

    private fun hasSelector(selector: Selector) = listOf(selector.text, selector.viewId, selector.description).any { !it.isNullOrBlank() }

    private fun isComplete(records: List<Record>): Boolean = records.size < MAX_NODES && records.none {
        it.node.childCount > MAX_CHILDREN || (it.path.count { character -> character == '.' } >= MAX_DEPTH && it.node.childCount > 0)
    }

    private sealed interface Prepared {
        data class Failed(val reason: String, val retryable: Boolean = true) : Prepared
        data class Done(val success: Boolean, val detail: String) : Prepared
        data class Tap(val x: Float, val y: Float) : Prepared
    }

    companion object {
        @Volatile var instance: PhoneAccessibilityService? = null
            private set
        private const val MAX_NODES = 350
        private const val MAX_DEPTH = 18
        private const val MAX_CHILDREN = 100
        private const val MAX_TEXT = 2_000
        private const val MAX_INPUT = 20_000
        private const val MAX_SCREENSHOT_BYTES = 4 * 1024 * 1024
        const val INCOMPLETE_VIEW_ID = "__phoneagent_snapshot_incomplete__"
    }
}
