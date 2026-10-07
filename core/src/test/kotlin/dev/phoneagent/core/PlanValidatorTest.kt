package dev.phoneagent.core

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class PlanValidatorTest {
    private val validator = PlanValidator()

    @Test fun rejectsUnboundedPlansAndRetries() {
        assertFailsWith<IllegalArgumentException> { validator.validate(Plan("empty", emptyList())) }
        assertFailsWith<IllegalArgumentException> { validator.validate(Plan("too many", (1..25).map { step(it.toString()) })) }
        assertFailsWith<IllegalArgumentException> { validator.validate(Plan("retry forever", listOf(step("retry").copy(maxAttempts = 4)))) }
        assertFailsWith<IllegalArgumentException> { validator.validate(Plan("duplicate ids", listOf(step("same"), step("same")))) }
    }

    @Test fun rejectsUnsafeUriAndApiShapes() {
        listOf("javascript:alert(1)", "file:///etc/passwd", "content://contacts", "intent://bypass").forEach { uri ->
            assertFailsWith<IllegalArgumentException> { validator.validateAction(Action(ActionKind.DEEP_LINK, uri = uri)) }
        }
        listOf("http://api.example.com/items", "https://localhost/items", "https://127.0.0.1/items", "https://api.example.com:8443/items", "https://user:pass@api.example.com/items").forEach { url ->
            assertFailsWith<IllegalArgumentException> { validator.validateAction(Action(ActionKind.API, api = ApiRequest(url))) }
        }
        assertFailsWith<IllegalArgumentException> { validator.validateAction(Action(ActionKind.API, api = ApiRequest("https://api.example.com/items", "GET", "payload"))) }
    }

    @Test fun rejectsImpossibleUiSelectorsAndShellFields() {
        listOf(
            Action(ActionKind.UI_CLICK),
            Action(ActionKind.UI_CLICK, selector = Selector()),
            Action(ActionKind.UI_CLICK, x = 10),
            Action(ActionKind.UI_CLICK, selector = Selector(text = "OK"), x = 10, y = 10),
            Action(ActionKind.UI_SET_TEXT, x = 10, y = 10, text = "hello"),
            Action(ActionKind.SHELL, shellOp = ShellOp.BRIGHTNESS, value = 999),
            Action(ActionKind.SHELL, shellOp = ShellOp.WIFI_ON, uri = "sh:rm")
        ).forEach { assertFailsWith<IllegalArgumentException> { validator.validateAction(it) } }
    }

    @Test fun sortsOfficialInterfacesAheadOfUi() {
        val actions = listOf(Action(ActionKind.UI_CLICK, packageName = "com.example.first", selector = Selector(text = "OK")), Action(ActionKind.SHELL, shellOp = ShellOp.WIFI_ON), Action(ActionKind.API, api = ApiRequest("https://api.example.com/items")), Action(ActionKind.DEEP_LINK, uri = "https://example.com"), Action(ActionKind.INTENT, intentAction = "android.settings.SETTINGS"))
        val result = validator.validate(Plan("ordered", listOf(step("one").copy(alternatives = actions))))
        assertEquals(listOf(ActionKind.INTENT, ActionKind.DEEP_LINK, ActionKind.API, ActionKind.SHELL, ActionKind.UI_CLICK), result.steps.single().alternatives.map { it.kind })
    }

    private fun step(id: String) = PlanStep(id, "Open settings", listOf(Action(ActionKind.INTENT, intentAction = "android.settings.SETTINGS")), Verification(VerificationKind.FOREGROUND_PACKAGE, "com.android.settings"))
}
