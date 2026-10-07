package dev.phoneagent.app

import dev.phoneagent.core.ShellOp
import java.net.InetAddress
import java.net.UnknownHostException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class AdapterSecurityTest {
    @Test fun shellOperationsAreTypedAndBounded() {
        assertNull(AllowedShellCommands.forOperation(ShellOp.BRIGHTNESS, -1))
        assertNull(AllowedShellCommands.forOperation(ShellOp.BRIGHTNESS, 256))
        assertNull(AllowedShellCommands.forOperation(ShellOp.VOLUME, 16))
        val commands = AllowedShellCommands.forOperation(ShellOp.BRIGHTNESS, 128)!!
        assertEquals("0", commands.first().last())
        assertEquals("128", commands.last().last())
        for (op in ShellOp.entries) {
            val tokens = AllowedShellCommands.forOperation(op, 1)!!.flatten()
            assertTrue(tokens.none { it.contains(';') || it.contains('$') || it.contains('\n') })
            assertTrue(tokens.first().startsWith("/system/bin/"))
        }
    }

    @Test fun settingsIntentUsesExactWhitelist() {
        assertTrue(GatewayPolicy.isAllowedSettingsIntent("android.settings.WIFI_SETTINGS"))
        assertFalse(GatewayPolicy.isAllowedSettingsIntent("android.settings.FACTORY_RESET"))
        assertFalse(GatewayPolicy.isAllowedSettingsIntent("android.intent.action.CALL"))
        assertFalse(GatewayPolicy.isAllowedSettingsIntent("android.intent.action.SEND"))
    }

    @Test fun dangerousUrisAndUntargetedCustomSchemesAreRejected() {
        for (uri in listOf("intent://x#Intent;end", "file:///sdcard/token", "content://contacts", "javascript:alert(1)", "tel:12345", "sms:12345", "package:dev.target")) {
            assertFalse(uri, GatewayPolicy.isAllowedDeepLink(uri, "dev.target"))
        }
        assertFalse(GatewayPolicy.isAllowedDeepLink("myapp://open", null))
        assertTrue(GatewayPolicy.isAllowedDeepLink("myapp://open", "dev.target"))
        assertFalse(GatewayPolicy.isAllowedDeepLink("https://secret@example.com", null))
        assertTrue(GatewayPolicy.isAllowedDeepLink("https://example.com/search?q=test", null))
    }

    @Test fun dialAndMessagingOnlyOpenKnownDraftUris() {
        assertTrue(GatewayPolicy.isAllowedDraftIntent("android.intent.action.DIAL", "tel:+86123456789"))
        assertFalse(GatewayPolicy.isAllowedDraftIntent("android.intent.action.DIAL", "tel:*%2306%23"))
        assertFalse(GatewayPolicy.isAllowedDraftIntent("android.intent.action.CALL", "tel:12345"))
        assertTrue(GatewayPolicy.isAllowedDraftIntent("android.intent.action.SENDTO", "smsto:12345?body=hello"))
        assertFalse(GatewayPolicy.isAllowedDraftIntent("android.intent.action.SENDTO", "smsto:12345?send=true"))
        assertTrue(GatewayPolicy.isAllowedDraftIntent("android.intent.action.SENDTO", "mailto:person@example.com?subject=hello&body=test"))
        assertFalse(GatewayPolicy.isAllowedDraftIntent("android.intent.action.SENDTO", "https://example.com/send"))
    }

    @Test fun apiHostMustMatchExactlyAndCannotCarryCredentials() {
        val hosts = setOf("api.example.com", "127.0.0.1", "localhost", "service.internal")
        assertNotNull(GatewayPolicy.allowedApiUri("https://api.example.com/v1", hosts))
        for (uri in listOf(
            "http://api.example.com/v1", "https://api.example.com:8443/v1", "https://api.example.com.evil.org/v1",
            "https://evil.org/api.example.com", "https://user:pass@api.example.com/v1", "https://127.0.0.1/",
            "https://localhost/", "https://service.internal/", "https://api.example.com/v1#token"
        )) assertNull(uri, GatewayPolicy.allowedApiUri(uri, hosts))
    }

    @Test fun privateAndLocalDnsAnswersAreRejected() {
        for (ip in listOf("127.0.0.1", "0.0.0.0", "10.1.2.3", "172.20.1.2", "192.168.1.1", "169.254.169.254", "100.64.0.1", "::1", "fe80::1", "fc00::1")) {
            assertFalse(ip, GatewayPolicy.isPublicAddress(InetAddress.getByName(ip)))
        }
        assertTrue(GatewayPolicy.isPublicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(GatewayPolicy.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test fun blockedDnsAnswersUseIoFailureWithoutCrashingAsyncWorker() {
        val public = listOf(InetAddress.getByName("8.8.8.8"))
        val private = listOf(InetAddress.getByName("127.0.0.1"))
        for (blocked in listOf(emptyList(), private, public + private)) {
            val failure = assertThrows(IOException::class.java) { GatewayPolicy.publicDnsAddresses(blocked) }
            assertTrue(failure is UnknownHostException)
        }
        assertEquals(public, GatewayPolicy.publicDnsAddresses(public))
    }
}
