package dev.phoneagent.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafetyPolicyTest {
    private val policy = SafetyPolicy()
    private val observation = Observation(packageName = "com.example.browser")

    @Test fun readVerbsCannotBypassSensitiveEndpointConfirmation() {
        listOf(
            "https://api.example.com/delete?id=1",
            "https://api.example.com/send?to=someone",
            "https://api.example.com/%64elete?id=1",
            "https://api.example.com/%73%65%6e%64?to=someone",
            "https://api.example.com/%2564elete?id=1",
            "https://api.example.com/%E5%88%A0%E9%99%A4?id=1"
        ).forEach { url ->
            listOf("GET", "HEAD").forEach { method ->
                val action = Action(ActionKind.API, api = ApiRequest(url, method), sensitive = false)
                assertNotNull(policy.confirmationReason(action, observation), "$method $url must require confirmation")
                assertFalse(policy.isSafelyRepeatable(action), "$method $url must not be retried")
            }
        }
    }

    @Test fun encodedSensitiveNavigationIsConfirmedAndCannotRepeat() {
        listOf(ActionKind.DEEP_LINK, ActionKind.INTENT).forEach { kind ->
            val action = Action(kind, uri = "https://example.com/%70ay?amount=10", intentAction = if (kind == ActionKind.INTENT) "android.intent.action.VIEW" else null)
            assertNotNull(policy.confirmationReason(action, observation))
            assertFalse(policy.isSafelyRepeatable(action))
        }
    }

    @Test fun operationAndCredentialQuerySelectorsAreConservativelyReviewed() {
        listOf(
            "https://api.example.com/rpc?%61ction=unknown",
            "https://api.example.com/rpc?%256fp=mystery",
            "https://api.example.com/rpc?operation=run",
            "https://api.example.com/data?access%5Ftoken=abc",
            "https://api.example.com/data?api-key=abc"
        ).forEach { url ->
            val action = Action(ActionKind.API, api = ApiRequest(url))
            assertNotNull(policy.confirmationReason(action, observation), url)
            assertFalse(policy.isSafelyRepeatable(action), url)
        }
    }

    @Test fun malformedOrExcessiveEncodingFailsClosedWithoutThrowing() {
        listOf(
            "https://api.example.com/%",
            "https://api.example.com/%GG",
            "https://api.example.com/%C0%AFroute",
            "https://api.example.com/%00route",
            "https://api.example.com/%2525252564elete"
        ).forEach { url ->
            val action = Action(ActionKind.API, api = ApiRequest(url))
            assertNotNull(policy.confirmationReason(action, observation), url)
            assertFalse(policy.isSafelyRepeatable(action), url)
        }
    }

    @Test fun ordinaryPublicNavigationAndReadQueriesRemainRepeatable() {
        listOf(
            Action(ActionKind.DEEP_LINK, uri = "https://example.org/news?q=Android+AI&page=2"),
            Action(ActionKind.INTENT, intentAction = "android.intent.action.VIEW", uri = "https://example.org/docs/%E6%96%87%E6%A1%A3"),
            Action(ActionKind.API, api = ApiRequest("https://api.example.org/weather?city=London&unit=celsius", "GET"))
        ).forEach { action ->
            assertNull(policy.confirmationReason(action, observation), action.toString())
            assertTrue(policy.isSafelyRepeatable(action), action.toString())
        }
    }

    @Test fun descriptiveSensitiveIntentIsStillReviewedOnOrdinaryUrl() {
        val action = Action(ActionKind.API, api = ApiRequest("https://api.example.com/resource"), description = "Delete the selected record")
        assertNotNull(policy.confirmationReason(action, observation))
        assertFalse(policy.isSafelyRepeatable(action))
    }
}
