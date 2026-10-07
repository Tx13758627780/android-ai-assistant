package dev.phoneagent.app

import android.content.Intent
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.phoneagent.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android integration; does not require a cloud account or perform real sensitive effects. */
@RunWith(AndroidJUnit4::class)
class DeviceFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val runtime get() = (context.applicationContext as PhoneAgentApplication).runtime
    private val device = run {
        // UiAutomator otherwise suppresses the very Accessibility service this test exercises.
        Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        Configurator.getInstance().setWaitForIdleTimeout(1_000)
        UiDevice.getInstance(instrumentation)
    }

    @Before fun prepare() {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        // Instrumentation can kill the target process and leave its prior service marked crashed.
        // Only the explicitly authorized device-smoke script opts into secure-settings changes.
        val previousService = if (InstrumentationRegistry.getArguments().getString("phoneagent.rebind_accessibility") == "true") {
            PhoneAccessibilityService.instance.also { rebindOwnedAccessibilityService() }
        } else null
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while ((PhoneAccessibilityService.instance == null || PhoneAccessibilityService.instance === previousService) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertNotNull("Enable accessibility using scripts/device-smoke.sh first", PhoneAccessibilityService.instance)
        runtime.settings.cloudEnabled = false
        runtime.settings.privilegedMode = PrivilegedMode.NONE
        openAssistant()
    }

    private fun rebindOwnedAccessibilityService() {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }
        val agent = "${context.packageName}/${PhoneAccessibilityService::class.java.name}"
        val others = shell("settings get secure enabled_accessibility_services").split(':')
            .filter { it.isNotBlank() && it != "null" && it != agent }.distinct()
        val componentPattern = Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")
        require((others + agent).all(componentPattern::matches)) { "Test-device accessibility component names must contain only safe characters" }
        // UiAutomation tokenizes directly using Runtime.exec; quotes become literal setting data.
        if (others.isEmpty()) shell("settings delete secure enabled_accessibility_services")
        else shell("settings put secure enabled_accessibility_services ${others.joinToString(":")}")
        val detachDeadline = SystemClock.elapsedRealtime() + 3_000
        while (PhoneAccessibilityService.instance != null && SystemClock.elapsedRealtime() < detachDeadline) SystemClock.sleep(50)
        // Let the manager observe the disabled value before writing the restored list.
        SystemClock.sleep(150)
        shell("settings put secure accessibility_enabled 1")
        shell("settings put secure enabled_accessibility_services ${(others + agent).joinToString(":")}")
    }

    @Test fun offlineTaskLaunchesAndVerifiesSettingsTwice() {
        repeat(2) {
            openAssistant()
            val previousId = runtime.engine.state.value?.id
            val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 15_000)
            assertNotNull(input)
            input.text = "打开设置"
            device.findObject(By.text("开始执行  →")).click()
            assertTrue("Settings must actually become foreground", device.wait(Until.hasObject(By.pkg("com.android.settings")), 20_000))
            waitState(SessionStatus.SUCCEEDED, differentFrom = previousId)
            assertEquals(1, runtime.engine.state.value?.nextStep)
            assertTrue(runtime.engine.state.value!!.trace.single().verified)
        }
    }

    @Test fun crossAppPlanPersistsBothVerifiedSteps() {
        val packageName = "com.android.deskclock"
        val clock = context.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("AOSP emulator must have the Clock app", clock)
        val label = context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
        val previousId = runtime.engine.state.value?.id
        val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 15_000)!!
        input.text = "打开设置，然后打开$label"
        device.findObject(By.text("开始执行  →")).click()
        waitState(SessionStatus.SUCCEEDED, differentFrom = previousId)
        val session = runtime.engine.state.value!!
        assertEquals(2, session.nextStep)
        assertEquals(listOf("com.android.settings", packageName), session.trace.map { it.action.packageName ?: "com.android.settings" })
        assertTrue(session.trace.all { it.verified })
        assertEquals(session, runBlocking { runtime.store.load(session.id) })
        assertEquals(packageName, runBlocking { runtime.gateway.observe() }.packageName)
    }

    @Test fun accessibilityActionWaitsForConfirmationThenVerifiesTargetScreen() {
        // Fixture supplies a fixed plan; the real engine, service, screen reader, Activity confirmation,
        // click and completion verifier all run on device. No model request is mocked as successful.
        val plan = Plan("Open Display settings through Accessibility", listOf(
            PlanStep("open", "打开设置", listOf(Action(ActionKind.INTENT, intentAction = "android.settings.SETTINGS")), Verification(VerificationKind.FOREGROUND_PACKAGE, "com.android.settings")),
            PlanStep("display", "进入显示设置", listOf(Action(ActionKind.UI_CLICK, packageName = "com.android.settings", selector = Selector(text = "Display"), description = "Open the Display settings row")), Verification(VerificationKind.TEXT_PRESENT, "Brightness level"), maxAttempts = 1)
        ))
        val session = Session("device_confirmation_fixture", "打开设置中的显示页面", status = SessionStatus.PAUSED, plan = plan)
        runBlocking { runtime.store.save(session) }
        instrumentation.runOnMainSync {
            context.startForegroundService(Intent(context, TaskRunnerService::class.java).setAction(TaskRunnerService.RESUME).putExtra("id", session.id))
        }
        waitState(SessionStatus.AWAITING_CONFIRMATION)
        assertEquals(1, runtime.engine.state.value!!.nextStep)
        assertEquals(1, runtime.engine.state.value!!.trace.size)
        openAssistant()
        val approve = device.wait(Until.findObject(By.text("确认这一步")), 15_000)
        assertNotNull("Confirmation dialog must show the exact action", approve)
        approve.click()
        waitState(SessionStatus.SUCCEEDED)
        assertEquals(2, runtime.engine.state.value!!.nextStep)
        assertTrue(device.wait(Until.hasObject(By.text("Brightness level")), 10_000))
    }

    private fun openAssistant() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 15_000))
        device.waitForIdle(1_000)
    }

    private fun waitState(expected: SessionStatus, differentFrom: String? = null) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = runtime.engine.state.value
            if (current?.id != differentFrom) {
                if (current?.status == expected) return
                if (current?.status in setOf(SessionStatus.FAILED, SessionStatus.CANCELLED)) fail("Unexpected ${current?.status}: ${current?.message}")
            }
            SystemClock.sleep(150)
        }
        fail("Timed out waiting for $expected; actual=${runtime.engine.state.value}")
    }
}
