package dev.phoneagent.app

import dev.phoneagent.core.ModelTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream

/** No model credentials are attached to tool/API requests. No redirect can forward the key. */
class OpenAiTransport(private val settings: AgentSettings) : ModelTransport {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    override suspend fun complete(system: String, user: String, screenshotBase64: String?): String = withContext(Dispatchers.IO) {
        check(settings.cloudEnabled) { "请先在模型设置中允许云端规划" }
        val key = settings.apiKey
        check(key.isNotBlank()) { "请配置自己的模型 API Key" }
        val content = if (screenshotBase64 != null) {
            check(settings.screenshotUpload) { "截图上传尚未开启" }
            buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", user) })
                add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$screenshotBase64") }) })
            }
        } else JsonPrimitive(user)
        val payload = buildJsonObject {
            put("model", settings.model)
            put("temperature", 0)
            put("max_tokens", 4096)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", content) })
            })
        }
        val request = Request.Builder().url(settings.endpoint)
            .header("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).awaitResponse().use { response ->
            check(response.isSuccessful) { "模型请求失败：HTTP ${response.code}，请检查地址、模型和 Key" }
            val stream = requireNotNull(response.body).byteStream()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= 1_048_576) {
                val count = stream.read(buffer, 0, minOf(buffer.size, 1_048_577 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            check(bytes.size <= 1_048_576) { "模型响应超出大小限制" }
            val root = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
            val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            requireNotNull(message?.get("content")?.jsonPrimitive?.contentOrNull) { "模型未返回有效的规划内容" }
        }
    }
}
