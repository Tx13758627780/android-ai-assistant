package dev.phoneagent.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlannerTest {
    @Test fun `offline splits Chinese cross app task and requires evidence for both steps`() = runTest {
        val plan = RulePlanner().plan("打开设置，然后打开浏览器搜索天气", Observation(), emptyMap())
        assertEquals(2, plan.steps.size)
        assertEquals("android.settings.SETTINGS", plan.steps[0].alternatives.single().intentAction)
        assertEquals(Verification(VerificationKind.FOREGROUND_PACKAGE, "com.android.settings"), plan.steps[0].verification)
        assertTrue(plan.steps[1].alternatives.single().uri!!.startsWith("https://www.google.com/search?q="))
        assertEquals(Verification(VerificationKind.TEXT_PRESENT, "天气"), plan.steps[1].verification)
        assertEquals(2, RulePlanner().plan("打开设置，再打开浏览器搜索天气", Observation(), emptyMap()).steps.size)
    }

    @Test fun `offline hints accept spaces between Chinese and WiFi setting words`() = runTest {
        val plan = RulePlanner().plan("打开 WiFi 设置，然后打开 显示 设置", Observation(), emptyMap())
        assertEquals(listOf("android.settings.WIFI_SETTINGS", "android.settings.DISPLAY_SETTINGS"),
            plan.steps.map { it.alternatives.single().intentAction })
    }

    @Test fun `app packages come from installed apps and are not guessed`() = runTest {
        val planner = RulePlanner()
        val apps = mapOf("installed_apps" to "{\"微信\":\"com.tencent.mm\",\"Firefox\":\"org.mozilla.firefox\"}")
        val plan = planner.plan("打开微信，然后打开浏览器", Observation(), apps)
        assertEquals(listOf("com.tencent.mm", "org.mozilla.firefox"), plan.steps.map { it.alternatives.single().packageName })
        assertFailsWith<IllegalArgumentException> { planner.plan("打开未安装的应用", Observation(), apps) }
        assertFailsWith<IllegalArgumentException> { planner.plan("打开浏览器", Observation(), emptyMap()) }
    }

    @Test fun `offline refuses unsupported payment and messaging rather than completing nothing`() = runTest {
        val planner = RulePlanner()
        assertFailsWith<IllegalArgumentException> { planner.plan("给张三发送消息你好", Observation(), emptyMap()) }
        assertFailsWith<IllegalArgumentException> { planner.plan("支付十元", Observation(), emptyMap()) }
        assertFailsWith<IllegalArgumentException> { planner.plan("删掉所有照片", Observation(), emptyMap()) }
    }

    @Test fun `privileged commands require explicit mode and stay typed and bounded`() = runTest {
        assertFailsWith<IllegalArgumentException> { RulePlanner().plan("关闭 WiFi", Observation(), emptyMap()) }
        val enabled = RulePlanner(privilegedEnabled = { true })
        val plan = enabled.plan("开启WiFi，然后把亮度调到50%", Observation(), emptyMap())
        assertEquals(ShellOp.WIFI_ON, plan.steps[0].alternatives.single().shellOp)
        assertEquals(Verification(VerificationKind.SYSTEM_SETTING, "1", "wifi_on"), plan.steps[0].verification)
        assertEquals(128, plan.steps[1].alternatives.single().value)
        assertEquals(Verification(VerificationKind.SYSTEM_SETTING, "128", "screen_brightness"), plan.steps[1].verification)
        assertFailsWith<IllegalArgumentException> { enabled.plan("亮度101%", Observation(), emptyMap()) }
    }

    @Test fun `URL completion requires page evidence`() = runTest {
        val plan = RulePlanner().plan("打开https://example.org/docs", Observation(), emptyMap())
        assertEquals(Verification(VerificationKind.TEXT_PRESENT, "example.org"), plan.steps.single().verification)
        assertEquals(ActionKind.DEEP_LINK, plan.steps.single().alternatives.single().kind)
    }

    @Test fun `strict codec allows fenced JSON but rejects extra fields and unknown enums`() {
        val encoded = ModelJsonCodec.json.encodeToString(settingsPlan())
        assertEquals(settingsPlan(), ModelJsonCodec.decodePlan("```json\n$encoded\n```"))
        assertFailsWith<SerializationException> { ModelJsonCodec.decodePlan(encoded.replace("\"summary\":", "\"executeRawShell\":\"rm -rf /\",\"summary\":")) }
        assertFailsWith<SerializationException> { ModelJsonCodec.decodePlan(encoded.replace("INTENT", "RAW_SHELL")) }
        assertFailsWith<SerializationException> { ModelJsonCodec.decodePlan("Here is the plan: $encoded") }
    }

    @Test fun `screenshots default to off and passwords and secrets never enter provider input`() = runTest {
        val transport = FakeTransport(ModelJsonCodec.json.encodeToString(settingsPlan()))
        val observation = Observation(packageName = "com.example.app", width = 1080, height = 1920,
            screenshotBase64 = "PRIVATE_SCREENSHOT",
            nodes = listOf(UiNode("password", "PRIVATE_PASSWORD", "PRIVATE_PASSWORD", bounds = Bounds(0, 0, 10, 10), password = true)))
        ModelPlanner(transport).plan("打开设置", observation, mapOf("api_key" to "PRIVATE_API_KEY", "last_package" to "com.example.app"))
        assertNull(transport.screenshot)
        assertFalse(transport.user.contains("PRIVATE_SCREENSHOT"))
        assertFalse(transport.user.contains("PRIVATE_PASSWORD"))
        assertFalse(transport.user.contains("PRIVATE_API_KEY"))
        assertTrue(transport.user.contains("com.example.app"))
    }

    @Test fun `consented screenshots are sent separately from structured user JSON`() = runTest {
        val transport = FakeTransport(ModelJsonCodec.json.encodeToString(settingsPlan()))
        ModelPlanner(transport, screenshotUploadEnabled = { true }).plan("打开设置",
            Observation(width = 100, height = 200, screenshotBase64 = "CONSENTED"), emptyMap())
        assertEquals("CONSENTED", transport.screenshot)
        assertFalse(transport.user.contains("CONSENTED"))
        assertTrue(transport.user.contains("\"screenshotAttached\":true"))
    }

    @Test fun `model coordinates need current consented screenshot and must stay in bounds`() = runTest {
        val clickPlan = settingsPlan().copy(steps = listOf(PlanStep("click", "打开设置",
            listOf(Action(ActionKind.UI_CLICK, packageName = "com.example.app", x = 100, y = 30)), Verification(VerificationKind.TEXT_PRESENT, "设置"))))
        val transport = FakeTransport(ModelJsonCodec.json.encodeToString(clickPlan))
        val observation = Observation(packageName = "com.example.app", width = 100, height = 200, screenshotBase64 = "image")
        assertFailsWith<IllegalArgumentException> { ModelPlanner(transport).plan("打开设置", observation, emptyMap()) }
        assertFailsWith<IllegalArgumentException> { ModelPlanner(transport, screenshotUploadEnabled = { true }).plan("打开设置", observation, emptyMap()) }
        transport.response = ModelJsonCodec.json.encodeToString(clickPlan.copy(steps = clickPlan.steps.map { it.copy(alternatives = listOf(it.alternatives.single().copy(x = 99))) }))
        assertNotNull(ModelPlanner(transport, screenshotUploadEnabled = { true }).plan("打开设置", observation, emptyMap()))
        var consent = false
        val changedConsent = object : ModelTransport {
            override suspend fun complete(system: String, user: String, screenshotBase64: String?): String {
                assertNull(screenshotBase64)
                consent = true
                return transport.response
            }
        }
        assertFailsWith<IllegalArgumentException> { ModelPlanner(changedConsent) { consent }.plan("打开设置", observation, emptyMap()) }
    }

    @Test fun `grounding preserves requested input and cannot weaken sensitivity`() = runTest {
        val requested = Action(ActionKind.UI_SET_TEXT, packageName = "com.example.app", text = "指定的消息", selector = Selector(text = "旧输入框"), sensitive = true, description = "填写指定草稿")
        val proposed = requested.copy(text = "模型擅自修改", selector = Selector(viewId = "com.example.app:id/message"), sensitive = false, description = "改变任务")
        val transport = FakeTransport(ModelJsonCodec.json.encodeToString(proposed))
        val observation = Observation(packageName = "com.example.app", nodes = listOf(UiNode("input", viewId = "com.example.app:id/message",
            bounds = Bounds(0, 0, 100, 100), editable = true)))
        val actual = ModelPlanner(transport).ground(requested, observation)
        assertEquals("指定的消息", actual.text)
        assertTrue(actual.sensitive)
        assertEquals("填写指定草稿", actual.description)
        assertEquals(proposed.selector, actual.selector)
    }

    @Test fun `grounding rejects fabricated and ambiguous nodes`() = runTest {
        val requested = Action(ActionKind.UI_CLICK, packageName = "com.example.app", selector = Selector(text = "旧按钮"))
        val transport = FakeTransport(ModelJsonCodec.json.encodeToString(requested.copy(selector = Selector(text = "不存在"))))
        val observation = Observation(packageName = "com.example.app", nodes = listOf(UiNode("1", "保存", bounds = Bounds(0, 0, 10, 10), clickable = true)))
        assertFailsWith<IllegalArgumentException> { ModelPlanner(transport).ground(requested, observation) }
        transport.response = ModelJsonCodec.json.encodeToString(requested.copy(selector = Selector(text = "保存")))
        assertFailsWith<IllegalArgumentException> { ModelPlanner(transport).ground(requested, observation.copy(nodes = observation.nodes + observation.nodes[0].copy(nodeId = "2"))) }
    }

    @Test fun `empty remote plans surface an honest capability failure`() = runTest {
        val transport = FakeTransport("{\"summary\":\"无法操作：缺少授权\",\"steps\":[]}")
        val error = assertFailsWith<IllegalArgumentException> { ModelPlanner(transport).plan("支付", Observation(), emptyMap()) }
        assertEquals("无法操作：缺少授权", error.message)
    }

    private fun settingsPlan() = Plan("打开设置", listOf(PlanStep("open_settings", "打开设置",
        listOf(Action(ActionKind.INTENT, intentAction = "android.settings.SETTINGS", description = "打开设置")),
        Verification(VerificationKind.FOREGROUND_PACKAGE, "com.android.settings"))))

    private class FakeTransport(var response: String) : ModelTransport {
        var user = ""
        var screenshot: String? = null
        override suspend fun complete(system: String, user: String, screenshotBase64: String?): String {
            this.user = user
            this.screenshot = screenshotBase64
            return response
        }
    }
}
