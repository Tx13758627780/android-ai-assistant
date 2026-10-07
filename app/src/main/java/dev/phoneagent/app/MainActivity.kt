package dev.phoneagent.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.phoneagent.core.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val runtime get() = (application as PhoneAgentApplication).runtime
    private val accent = Color.rgb(101, 223, 198)
    private val bg = Color.rgb(10, 16, 24)
    private val cardBg = Color.rgb(19, 29, 41)
    private val muted = Color.rgb(152, 168, 188)
    private lateinit var page: LinearLayout
    private lateinit var taskInput: EditText
    private lateinit var statusView: TextView
    private lateinit var connectionView: TextView
    private lateinit var stepsView: LinearLayout
    private lateinit var detailView: TextView
    private var selectedTab = 0
    private var confirmationToken: String? = null
    private var confirmationDialog: AlertDialog? = null
    private var displayedSession: Session? = null

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) listen() else toast("需要麦克风权限才能输入语音") }
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { taskInput.setText(it) }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        buildScreen()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { runtime.engine.state.collect { session -> displayedSession = session; if (selectedTab == 0) renderSession(session); showConfirmationIfNeeded(session) } }
                launch { runtime.error.collect { it?.let { message -> toast(message); if (::detailView.isInitialized) detailView.text = message } } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (runtime.settings.privilegedMode == PrivilegedMode.SHIZUKU) runtime.shell.bindShizuku()
        updateConnection()
        showConfirmationIfNeeded(runtime.engine.state.value)
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(24), dp(22), dp(24)) }
        scroll.addView(page)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply { setPadding(dp(16), dp(6), dp(16), dp(10)); setBackgroundColor(cardBg) }
        listOf("任务", "连接", "记忆").forEachIndexed { index, title ->
            nav.addView(button(title, false) { selectedTab = index; buildPage() }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        root.addView(nav)
        setContentView(root)
        buildPage()
    }

    private fun buildPage() {
        page.removeAllViews()
        when (selectedTab) { 0 -> taskPage(); 1 -> connectionPage(); 2 -> historyPage() }
    }

    private fun header(kicker: String, title: String, description: String) {
        page.addView(label(kicker, 12, accent).apply { letterSpacing = .15f })
        page.addView(label(title, 30, Color.WHITE, true), margins(0, 12, 0, 12))
        page.addView(label(description, 14, muted), margins(0, 0, 0, 24))
    }

    private fun taskPage() {
        header("PHONE AGENT  /  01", "一句话，让手机行动", "先规划，再操作。每一步都检查结果。")
        connectionView = label("", 12, accent)
        page.addView(connectionView, margins(0, 0, 0, 18))
        updateConnection()
        val inputCard = card()
        inputCard.addView(label("你想完成什么？", 15, Color.WHITE, true))
        taskInput = EditText(this).apply {
            hint = "例如：打开设置，然后打开浏览器搜索天气"
            setHintTextColor(muted); setTextColor(Color.WHITE); textSize = 16f
            background = null; minLines = 3; maxLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(0, dp(12), 0, dp(12))
        }
        inputCard.addView(taskInput, LinearLayout.LayoutParams(-1, -2))
        val controls = LinearLayout(this)
        controls.addView(button("语音", false) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
            else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        controls.addView(button("开始执行  →", true) { startTask() }, LinearLayout.LayoutParams(0, dp(48), 2f))
        inputCard.addView(controls)
        page.addView(inputCard, margins(0, 0, 0, 16))
        val examples = LinearLayout(this)
        listOf("打开设置", "打开 WiFi 设置").forEach { example ->
            examples.addView(button(example, false) { taskInput.setText(example) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
        }
        page.addView(examples, margins(0, 0, 0, 24))
        val taskCard = card()
        statusView = label("待命", 20, Color.WHITE, true)
        taskCard.addView(statusView)
        detailView = label("输入任务后，这里会显示计划、执行记录和检查结果。", 13, muted)
        taskCard.addView(detailView, margins(0, 8, 0, 12))
        stepsView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        taskCard.addView(stepsView)
        taskCard.addView(button("停止当前任务", false) { displayedSession?.let { service(TaskRunnerService.CANCEL, it.id) } }, margins(0, 12, 0, 0, dp(44)))
        page.addView(taskCard)
        page.addView(label("确认保护已开启  ·  付款 / 删除 / 发送消息", 12, muted), margins(0, 18, 0, 0))
        renderSession(displayedSession)
    }

    private fun connectionPage() {
        header("DEVICE ACCESS  /  02", "连接与能力", "按需开启权限。普通任务不需要 Root。")
        val access = card()
        access.addView(label("屏幕操作", 18, Color.WHITE, true))
        connectionView = label("", 13, accent)
        access.addView(connectionView, margins(0, 8, 0, 12)); updateConnection()
        access.addView(label("读取当前界面、截图定位和自动点击需要无障碍服务。仅在你提交任务后操作。", 13, muted))
        access.addView(button("开启无障碍服务", true) { showAccessibilityDisclosure() }, margins(0, 16, 0, 0, dp(48)))
        page.addView(access, margins(0, 0, 0, 16))
        val model = card()
        model.addView(label("AI 规划器", 18, Color.WHITE, true))
        model.addView(label(if (runtime.settings.cloudEnabled) "云端 · ${runtime.settings.model}" else "离线规则 · 可验证的基础任务", 13, accent), margins(0, 8, 0, 8))
        model.addView(label("兼容 Qwen / OpenAI 格式的模型接口。你提供 Key；上传截图单独选择。", 13, muted))
        model.addView(button("配置模型与 API 白名单", false) { modelDialog() }, margins(0, 14, 0, 0, dp(48)))
        page.addView(model, margins(0, 0, 0, 16))
        val privileged = card()
        privileged.addView(label("系统控制", 18, Color.WHITE, true))
        privileged.addView(label("当前模式：${runtime.settings.privilegedMode.name}", 13, accent), margins(0, 8, 0, 8))
        privileged.addView(label("仅执行固定命令：WiFi、亮度、音量、系统设置。授权后也要遵守确认规则。", 13, muted))
        privileged.addView(button("选择 Root / Shizuku", false) {
            AlertDialog.Builder(this).setTitle("系统控制模式").setSingleChoiceItems(arrayOf("普通模式", "Root（会请求 su 授权）", "Shizuku（需先启动服务）"), runtime.settings.privilegedMode.ordinal) { dialog, index ->
                runtime.settings.privilegedMode = PrivilegedMode.entries[index]
                if (index == 2) runCatching { runtime.shell.requestShizukuPermission(100); runtime.shell.bindShizuku() }.onFailure { toast(it.message ?: "Shizuku 不可用") }
                dialog.dismiss(); buildPage()
            }.setNegativeButton("取消", null).show()
        }, margins(0, 14, 0, 0, dp(48)))
        page.addView(privileged)
    }

    private fun historyPage() {
        header("TASK MEMORY  /  03", "每次行动，都有记录", "保存步骤和检查结果。中断后先核对状态，再继续。")
        lifecycleScope.launch {
            val sessions = runtime.store.list()
            if (selectedTab != 2) return@launch
            if (sessions.isEmpty()) page.addView(label("还没有任务记录。", 15, muted))
            sessions.forEach { session ->
                val item = card()
                item.addView(label(session.task.take(100), 16, Color.WHITE, true))
                item.addView(label("${statusLabel(session.status)} · ${session.nextStep}/${session.plan?.steps?.size ?: 0} 步", 12, accent), margins(0, 8, 0, 8))
                item.addView(label(session.message, 12, muted))
                item.addView(button("查看执行记录", false) {
                    val text = session.trace.joinToString("\n\n") { "${it.stepId} · ${it.action.kind}\n${it.detail}\n${if (it.verified) "已验证" else "未验证"}" }.ifBlank { "尚未执行操作" }
                    AlertDialog.Builder(this@MainActivity).setTitle("任务记录").setMessage(text).setPositiveButton("关闭", null).show()
                }, margins(0, 10, 0, 0, dp(42)))
                if (session.status !in setOf(SessionStatus.SUCCEEDED, SessionStatus.CANCELLED, SessionStatus.FAILED)) {
                    item.addView(button("核对并继续", true) {
                        displayedSession = session; selectedTab = 0; buildPage()
                        service(TaskRunnerService.RESUME, session.id)
                    }, margins(0, 10, 0, 0, dp(42)))
                }
                page.addView(item, margins(0, 0, 0, 12))
            }
        }
    }

    private fun modelDialog() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
        fun field(hintText: String, value: String, secret: Boolean = false): EditText = EditText(this).apply {
            hint = hintText; setText(value); textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        val endpoint = field("HTTPS chat/completions 地址", runtime.settings.endpoint)
        val model = field("模型名称", runtime.settings.model)
        val key = field("API Key（留空保留，填 CLEAR 清除）", "", true)
        val hosts = field("工具 API 域名白名单，逗号分隔", runtime.settings.apiHosts.joinToString(","))
        val cloud = CheckBox(this).apply { text = "允许云端 AI 规划（上传任务和屏幕文字）"; isChecked = runtime.settings.cloudEnabled; content.addView(this) }
        val screenshot = CheckBox(this).apply { text = "允许向模型上传当前截图（可能含隐私）"; isChecked = runtime.settings.screenshotUpload; content.addView(this) }
        content.addView(label("模型可能收到当前 App 的页面内容。关闭云端后使用离线规则；截图默认关闭。Key 通过 Android Keystore 加密保存。", 12, muted))
        val scroll = ScrollView(this).apply { addView(content) }
        val dialog = AlertDialog.Builder(this).setTitle("模型设置").setView(scroll).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val hostSet = hosts.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    val endpointValue = endpoint.text.toString().trim()
                    val endpointUri = java.net.URI(endpointValue)
                    require(endpointUri.scheme == "https" && !endpointUri.host.isNullOrBlank() && endpointUri.userInfo == null && endpointUri.fragment == null) { "请填写有效的 HTTPS 模型地址" }
                    require(model.text.isNotBlank()) { "请填写模型名称" }
                    val newKey = key.text.toString().trim()
                    val effectiveKey = when (newKey) { "CLEAR" -> ""; "" -> runtime.settings.apiKey; else -> newKey }
                    require(!cloud.isChecked || effectiveKey.isNotBlank()) { "启用云端前请填写 API Key；清除 Key 时请同时关闭云端规划" }
                    require(hostSet.all { it.matches(Regex("[a-zA-Z0-9.-]+")) && it.contains('.') }) { "API 白名单只填写域名" }
                    runtime.settings.endpoint = endpointValue
                    runtime.settings.model = model.text.toString().trim()
                    if (newKey == "CLEAR") runtime.settings.apiKey = "" else if (newKey.isNotBlank()) runtime.settings.apiKey = newKey
                    runtime.settings.apiHosts = hostSet
                    runtime.settings.screenshotUpload = screenshot.isChecked
                    runtime.settings.cloudEnabled = cloud.isChecked
                    dialog.dismiss(); buildPage()
                } catch (error: Exception) { toast(error.message ?: "设置保存失败") }
            }
        }
        dialog.show()
    }

    private fun showAccessibilityDisclosure() {
        AlertDialog.Builder(this).setTitle("允许屏幕操作")
            .setMessage("舟行 AI 将读取前台 App 的界面文字，并在你发起任务后点击、滚动或填写。截图仅在需要定位时获取；只有开启模型截图选项后才上传。付款、删除、发送消息和不确定操作会等待确认。你可以通过通知停止任务，也可以随时在系统设置关闭此服务。")
            .setPositiveButton("前往系统设置") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.setNegativeButton("取消", null).show()
    }

    private fun startTask() {
        val task = taskInput.text.toString().trim()
        if (task.isEmpty()) { toast("先输入一个任务"); return }
        if (task.length > 2000) { toast("任务请控制在 2000 字内"); return }
        if (PhoneAccessibilityService.instance == null) { showAccessibilityDisclosure(); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        service(TaskRunnerService.START, task = task)
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN").putExtra(RecognizerIntent.EXTRA_PROMPT, "说出你想完成的任务")
        runCatching { speech.launch(intent) }.onFailure { toast("此设备没有语音识别服务，请安装支持的服务或使用文字输入") }
    }

    private fun service(action: String, id: String? = null, task: String? = null, token: String? = null) {
        val intent = Intent(this, TaskRunnerService::class.java).setAction(action).putExtra("id", id).putExtra("task", task).putExtra("token", token)
        runCatching { startForegroundService(intent) }.onFailure { toast(it.message ?: "无法启动任务服务") }
    }

    private fun renderSession(session: Session?) {
        if (!::statusView.isInitialized || selectedTab != 0) return
        statusView.text = session?.let { statusLabel(it.status) } ?: "待命"
        detailView.text = session?.message?.ifBlank { session.plan?.summary ?: "正在分析任务" } ?: "输入任务后，这里会显示计划、执行记录和检查结果。"
        stepsView.removeAllViews()
        session?.plan?.steps?.forEachIndexed { index, step ->
            val done = index < session.nextStep
            val title = "${if (done) "✓" else "${index + 1}."}  ${step.title}"
            stepsView.addView(label(title, 14, if (done) accent else Color.WHITE), margins(0, 8, 0, 4))
            val entry = session.trace.lastOrNull { it.stepId == step.id }
            entry?.let { stepsView.addView(label(it.detail.take(240), 12, muted), margins(20, 0, 0, 8)) }
        }
    }

    private fun showConfirmationIfNeeded(session: Session?) {
        val pending = session?.approval?.takeIf { session.status == SessionStatus.AWAITING_CONFIRMATION }
        if (pending == null) { confirmationDialog?.dismiss(); confirmationDialog = null; confirmationToken = null; return }
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || confirmationToken == pending.token) return
        confirmationDialog?.dismiss(); confirmationToken = pending.token
        val action = pending.action
        val target = action.selector?.let { listOfNotNull(it.text, it.description, it.viewId).joinToString(" / ") } ?: action.uri ?: action.api?.url ?: action.packageName ?: action.shellOp?.name ?: "当前屏幕"
        val payload = buildString {
            action.packageName?.let { append("App：$it\n") }
            if (action.x != null) append("点击坐标：(${action.x}, ${action.y})\n")
            action.intentAction?.let { append("Intent：$it\n") }
            action.text?.let { append("填写内容：$it\n") }
            action.api?.let { append("HTTP 方法：${it.method}\n请求内容：${it.body ?: "（无）"}\n") }
            action.shellOp?.let { append("系统命令：$it  值：${action.value ?: "（无）"}\n") }
        }
        val screenContext = pending.screenSummary.takeIf { it.isNotBlank() }?.let { "\n实际页面文字：\n$it\n" }.orEmpty()
        val details = "${pending.reason}\n\n操作：${action.kind}\n目标：$target\n$payload\n规划说明：${action.description}\n$screenContext\n确认仅适用于这一步；目标页面变化会重新检查。"
        confirmationDialog = AlertDialog.Builder(this).setTitle("确认手机操作").setMessage(details).setCancelable(false)
            .setPositiveButton("确认这一步") { _, _ ->
                moveTaskToBack(true)
                service(TaskRunnerService.APPROVE, session.id, token = pending.token)
                confirmationToken = null
            }.setNegativeButton("拒绝并停止") { _, _ -> service(TaskRunnerService.REJECT, session.id); confirmationToken = null }.show()
    }

    private fun updateConnection() {
        if (::connectionView.isInitialized) connectionView.text = "●  ${if (PhoneAccessibilityService.instance != null) "屏幕操作已连接" else "等待开启无障碍"}  ·  ${if (runtime.settings.cloudEnabled) "AI 规划" else "离线规则"}"
    }

    private fun statusLabel(status: SessionStatus): String = when (status) {
        SessionStatus.CREATED -> "任务已创建"; SessionStatus.PLANNING -> "正在规划"; SessionStatus.RUNNING -> "正在操作手机"
        SessionStatus.AWAITING_CONFIRMATION -> "等待你的确认"; SessionStatus.PAUSED -> "任务已暂停"; SessionStatus.SUCCEEDED -> "已验证，任务完成"
        SessionStatus.FAILED -> "任务未完成"; SessionStatus.CANCELLED -> "任务已停止"
    }
    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)); background = shape(cardBg, 18) }
    private fun shape(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); setLineSpacing(dp(3).toFloat(), 1f)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private fun button(value: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = value; textSize = 13f; isAllCaps = false; gravity = Gravity.CENTER
        setTextColor(if (primary) bg else Color.WHITE); background = shape(if (primary) accent else Color.rgb(30, 44, 60), 10)
        setPadding(dp(10), 0, dp(10), 0); setOnClickListener { click() }
    }
    private fun margins(left: Int, top: Int, right: Int, bottom: Int, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) = LinearLayout.LayoutParams(-1, height).apply { setMargins(dp(left), dp(top), dp(right), dp(bottom)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
}
