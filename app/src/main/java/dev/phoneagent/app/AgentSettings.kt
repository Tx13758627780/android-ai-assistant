package dev.phoneagent.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API credentials remain in private storage, encrypted with a non-exportable device key. */
class AgentSettings(context: Context) {
    private val prefs = context.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
    var cloudEnabled: Boolean
        get() = prefs.getBoolean("cloud", false)
        set(value) { prefs.edit().putBoolean("cloud", value).apply() }
    var screenshotUpload: Boolean
        get() = prefs.getBoolean("screenshots", false)
        set(value) { prefs.edit().putBoolean("screenshots", value).apply() }
    var endpoint: String
        get() = prefs.getString("endpoint", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")!!
        set(value) {
            val uri = URI(value)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "模型地址必须是有效的 HTTPS 地址" }
            prefs.edit().putString("endpoint", value).apply()
        }
    var model: String
        get() = prefs.getString("model", "qwen-vl-max")!!
        set(value) { require(value.isNotBlank()); prefs.edit().putString("model", value).apply() }
    var privilegedMode: PrivilegedMode
        get() = runCatching { PrivilegedMode.valueOf(prefs.getString("privileged", "NONE")!!) }.getOrDefault(PrivilegedMode.NONE)
        set(value) { prefs.edit().putString("privileged", value.name).apply() }
    var apiHosts: Set<String>
        get() = prefs.getStringSet("api_hosts", emptySet())!!.toSet()
        set(value) {
            require(value.all { it.matches(Regex("[a-zA-Z0-9.-]+")) && it.contains('.') }) { "API 白名单须填写域名，不能带路径或协议" }
            prefs.edit().putStringSet("api_hosts", value.map { it.lowercase() }.toSet()).apply()
        }
    var apiKey: String
        get() {
            val encrypted = prefs.getString("credential", null) ?: return ""
            return runCatching {
                val fields = encrypted.split(':')
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(fields[0], Base64.NO_WRAP)))
                String(cipher.doFinal(Base64.decode(fields[1], Base64.NO_WRAP)), Charsets.UTF_8)
            }.getOrDefault("")
        }
        set(value) {
            if (value.isEmpty()) { prefs.edit().remove("credential").apply(); return }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString("credential", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
        }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("phone_agent_api", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("phone_agent_api", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
