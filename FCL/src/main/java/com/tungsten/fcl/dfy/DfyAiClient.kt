package com.tungsten.fcl.dfy

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 与 OpenAI 兼容接口对话（/chat/completions，流式）。
 *
 * 健壮性对齐 PC 端：
 *  - 最多尝试 3 次；408 / 409 / 425 / 429 / 5xx 视为可重试，退避后重试；
 *  - 服务端不支持 SSE 时，退回整包 JSON 解析（不会静默卡住）；
 *  - 单行 SSE 解析失败不影响整体，只跳过该行。
 */
object DfyAiClient {

    private const val MAX_ATTEMPTS = 3
    private const val CONNECT_TIMEOUT = 20_000
    private const val READ_TIMEOUT = 90_000

    data class Msg(val role: String, val content: String)

    private fun isRetryable(code: Int): Boolean =
        code == 408 || code == 409 || code == 425 || code == 429 || code >= 500

    fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    fun modelListEndpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        return if (base.endsWith("/models")) base else "$base/models"
    }

    /** 拉取 /models，返回模型 id 列表；失败返回空列表 */
    suspend fun listModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val url = modelListEndpoint(baseUrl)
        if (url.isEmpty()) return@withContext emptyList()
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("Accept", "application/json")
                if (apiKey.isNotEmpty()) setRequestProperty("Authorization", "Bearer $apiKey")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@withContext emptyList()
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            val root = JsonParser.parseString(text).asJsonObject
            val arr = root.getAsJsonArray("data") ?: return@withContext emptyList()
            arr.mapNotNull { it.asJsonObject.get("id")?.takeIf { id -> !id.isJsonNull }?.asString }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<Msg>,
        onDelta: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val url = endpoint(baseUrl)
        if (url.isEmpty()) return@withContext Result.failure(IllegalArgumentException("接口地址为空"))
        if (model.isBlank()) return@withContext Result.failure(IllegalArgumentException("模型名为空"))

        var lastError = ""
        for (attempt in 1..MAX_ATTEMPTS) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT
                    readTimeout = READ_TIMEOUT
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "text/event-stream")
                    if (apiKey.isNotEmpty()) setRequestProperty("Authorization", "Bearer $apiKey")
                }
                val payload = JsonObject().apply {
                    addProperty("model", model)
                    addProperty("stream", true)
                    add("messages", JsonArray().apply {
                        messages.forEach { m ->
                            add(JsonObject().apply {
                                addProperty("role", m.role)
                                addProperty("content", m.content)
                            })
                        }
                    })
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code !in 200..299) {
                    val body = try {
                        (conn.errorStream ?: conn.inputStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    } catch (e: Exception) {
                        ""
                    }
                    lastError = "HTTP $code ${body.take(400)}"
                    conn.disconnect()
                    if (isRetryable(code) && attempt < MAX_ATTEMPTS) {
                        delay(700L * attempt)
                        continue
                    }
                    return@withContext Result.failure(IOException(lastError))
                }

                val builder = StringBuilder()
                val raw = StringBuilder()
                var sawSse = false
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        raw.append(line).append('\n')
                        val trimmed = line.trim()
                        if (trimmed.startsWith("data:")) {
                            sawSse = true
                            val data = trimmed.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            if (data.isNotEmpty()) {
                                val piece = try {
                                    extractDelta(data)
                                } catch (e: Exception) {
                                    ""
                                }
                                if (piece.isNotEmpty()) {
                                    builder.append(piece)
                                    onDelta(piece)
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }
                conn.disconnect()

                if (!sawSse) {
                    // 服务端忽略了 stream 参数，直接返回完整 JSON
                    val piece = try {
                        extractMessage(raw.toString())
                    } catch (e: Exception) {
                        ""
                    }
                    if (piece.isNotEmpty()) {
                        builder.append(piece)
                        onDelta(piece)
                    }
                }

                if (builder.isEmpty()) {
                    lastError = "接口返回内容为空（请检查模型名和接口地址）"
                    if (attempt < MAX_ATTEMPTS) {
                        delay(700L * attempt)
                        continue
                    }
                    return@withContext Result.failure(IOException(lastError))
                }
                return@withContext Result.success(builder.toString())
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                try {
                    conn?.disconnect()
                } catch (ignored: Exception) {
                }
                if (attempt < MAX_ATTEMPTS) {
                    delay(700L * attempt)
                    continue
                }
            }
        }
        Result.failure(IOException(lastError.ifEmpty { "请求失败" }))
    }

    private fun extractDelta(data: String): String {
        val obj = JsonParser.parseString(data).asJsonObject
        val choices = obj.getAsJsonArray("choices") ?: return ""
        if (choices.size() == 0) return ""
        val delta = choices[0].asJsonObject.getAsJsonObject("delta") ?: return ""
        val content = delta.get("content")
        if (content == null || content.isJsonNull) return ""
        return content.asString ?: ""
    }

    private fun extractMessage(body: String): String {
        val obj = JsonParser.parseString(body).asJsonObject
        val choices = obj.getAsJsonArray("choices") ?: return ""
        if (choices.size() == 0) return ""
        val message = choices[0].asJsonObject.getAsJsonObject("message") ?: return ""
        val content = message.get("content")
        if (content == null || content.isJsonNull) return ""
        return content.asString ?: ""
    }
}
