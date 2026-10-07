package dev.phoneagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.phoneagent.core.Session
import dev.phoneagent.core.SessionStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps user-started tasks alive while other applications are in the foreground. */
class TaskRunnerService : Service() {
    private val runtime get() = (application as PhoneAgentApplication).runtime
    private var work: Job? = null
    private var watch: Job? = null
    private var started = false
    private var expectedId: String? = null
    private var ignoreOldId: String? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "手机任务", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = notification(null)
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, notification)
        watch = runtime.scope.launch(Dispatchers.Main.immediate) {
            runtime.engine.state.collect { session ->
                if (!started || session == null || session.id == ignoreOldId) return@collect
                if (expectedId == null) expectedId = session.id
                if (session.id != expectedId) return@collect
                manager.notify(ID, notification(session))
                if (session?.status in TERMINAL) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val sessionId = intent.getStringExtra("id")
        val action = intent.action
        if (action == CANCEL) {
            work?.cancel()
            runtime.scope.launch { sessionId?.let { runtime.engine.cancel(it) }; stopSelf() }
            return START_NOT_STICKY
        }
        if (work?.isActive == true) { runtime.reportError("已有任务正在执行，请先停止当前任务"); return START_NOT_STICKY }
        started = true
        expectedId = if (action == START) null else sessionId
        ignoreOldId = if (action == START) runtime.engine.state.value?.id else null
        runtime.reportError(null)
        work = runtime.scope.launch {
            try {
                when (action) {
                    START -> runtime.engine.start(intent.getStringExtra("task").orEmpty())
                    RESUME -> sessionId?.let { runtime.engine.resume(it) }
                    APPROVE -> {
                        // Activity returns the previous app to the foreground before confirming.
                        delay(700)
                        sessionId?.let { runtime.engine.approve(it, intent.getStringExtra("token").orEmpty()) }
                    }
                    REJECT -> sessionId?.let { runtime.engine.reject(it) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { runtime.reportError(error.message ?: "任务执行失败"); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun notification(session: Session?): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, TaskRunnerService::class.java).setAction(CANCEL).putExtra("id", session?.id)
        val stop = PendingIntent.getService(this, 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val status = when (session?.status) {
            SessionStatus.AWAITING_CONFIRMATION -> "需要确认 · 点击查看具体操作"
            SessionStatus.SUCCEEDED -> "任务已完成 · 已检查结果"
            SessionStatus.FAILED -> "任务未完成 · 查看原因"
            SessionStatus.CANCELLED -> "任务已停止"
            SessionStatus.PAUSED -> "任务已暂停 · 查看状态"
            else -> "正在执行您发起的手机任务"
        }
        return Notification.Builder(this, CHANNEL).setSmallIcon(dev.phoneagent.app.R.drawable.ic_launcher)
            .setContentTitle("舟行 AI · $status").setContentText(session?.message?.take(120) ?: "规划与执行中")
            .setContentIntent(open).setOngoing(session?.status !in TERMINAL)
            .addAction(Notification.Action.Builder(null, "停止任务", stop).build()).build()
    }

    override fun onDestroy() {
        watch?.cancel()
        if (runtime.engine.state.value?.status !in TERMINAL) work?.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val START = "dev.phoneagent.START"
        const val RESUME = "dev.phoneagent.RESUME"
        const val APPROVE = "dev.phoneagent.APPROVE"
        const val REJECT = "dev.phoneagent.REJECT"
        const val CANCEL = "dev.phoneagent.CANCEL"
        private const val CHANNEL = "phone_tasks"
        private const val ID = 100
        private val TERMINAL = setOf(SessionStatus.SUCCEEDED, SessionStatus.FAILED, SessionStatus.CANCELLED, SessionStatus.PAUSED)
    }
}
