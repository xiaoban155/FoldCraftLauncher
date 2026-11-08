package com.tungsten.fcl.dfy

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 与 OpenAI 兼容接口对话（/chat/completions，流式 + function calling）。
 *
 * 健壮性对齐 PC 端：
 *  - 最多尝试 3 次；408 / 409 / 425 / 429 / 5xx 视为可重试，退避后重试；
 *  - 服务端不支持 SSE 时，退回整包 JSON 解析（不会静默卡住）；
 *  - 单行 SSE 解析失败不影响整体，只跳过该行；
 *  - 流式里的 tool_calls 分片按 index 拼接，拼完再交给上层执行。
 */
object DfyAiClient {

    private const val MAX_ATTEMPTS = 3
    private const val CONNECT_TIMEOUT = 20_000
    private const val READ_TIMEOUT = 90_000

    data class ToolCall(val id: String, val name: String, val args: String)

    data class Msg(
        val role: String,
        val content: String? = null,
        val toolCalls: List<ToolCall> = emptyList(),
        val toolCallId: String? = null
    )

    data class Reply(val content: String, val toolCalls: List<ToolCall>)

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

    /** 纯对话（不带工具） */
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<Msg>,
        onDelta: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        chatWithTools(baseUrl, apiKey, model, messages, null, onDelta).map { it.content }
    }

    /**
     * 一轮对话：支持 tools 描述，返回本轮文本 + 模型请求的工具调用。
     * onDelta 会在 IO 线程逐块回调，调用方自己切主线程。
     */
    suspend fun chatWithTools(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<Msg>,
        tools: JsonArray?,
        onDelta: (String) -> Unit
    ): Result<Reply> = withContext(Dispatchers.IO) {
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
                    add("messages", JsonArray().apply { messages.forEach { add(msgToJson(it)) } })
                    if (tools != null && tools.size() > 0) {
                        add("tools", tools)
                        addProperty("tool_choice", "auto")
                    }
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
                val toolAcc = LinkedHashMap<Int, ToolCallAcc>()
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
                                try {
                                    val obj = JsonParser.parseString(data).asJsonObject
                                    val choices = obj.getAsJsonArray("choices")
                                    if (choices != null && choices.size() > 0) {
                                        val delta = choices[0].asJsonObject.getAsJsonObject("delta")
                                        if (delta != null) {
                                            val content = delta.get("content")
                                            if (content != null && !content.isJsonNull) {
                                                val piece = content.asString ?: ""
                                                if (piece.isNotEmpty()) {
                                                    builder.append(piece)
                                                    onDelta(piece)
                                                }
                                            }
                                            val tcs = delta.getAsJsonArray("tool_calls")
                                            if (tcs != null) accumulateToolCalls(tcs, toolAcc)
                                        }
                                    }
                                } catch (e: Exception) {
                                    // 单行坏了就跳过，不影响整体
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }
                conn.disconnect()

                if (!sawSse) {
                    // 服务端忽略了 stream 参数，直接返回完整 JSON
                    try {
                        val obj = JsonParser.parseString(raw.toString()).asJsonObject
                        val choices = obj.getAsJsonArray("choices")
                        if (choices != null && choices.size() > 0) {
                            val message = choices[0].asJsonObject.getAsJsonObject("message")
                            if (message != null) {
                                val content = message.get("content")
                                if (content != null && !content.isJsonNull) {
                                    val piece = content.asString ?: ""
                                    if (piece.isNotEmpty()) {
                                        builder.append(piece)
                                        onDelta(piece)
                                    }
                                }
                                val tcs = message.getAsJsonArray("tool_calls")
                                if (tcs != null) accumulateToolCalls(tcs, toolAcc)
                            }
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                }

                val calls = toolAcc.values.map { it.build() }
                if (builder.isEmpty() && calls.isEmpty()) {
                    lastError = "接口返回内容为空（请检查模型名和接口地址）"
                    if (attempt < MAX_ATTEMPTS) {
                        delay(700L * attempt)
                        continue
                    }
                    return@withContext Result.failure(IOException(lastError))
                }
                return@withContext Result.success(Reply(builder.toString(), calls))
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

    private class ToolCallAcc {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()

        fun build(): ToolCall = ToolCall(id.ifEmpty { "call_${name.hashCode()}" }, name, args.toString())
    }

    private fun accumulateToolCalls(arr: JsonArray, acc: MutableMap<Int, ToolCallAcc>) {
        for (i in 0 until arr.size()) {
            try {
                val item = arr[i].asJsonObject
                val index = item.get("index")?.takeIf { !it.isJsonNull }?.asInt ?: i
                val slot = acc.getOrPut(index) { ToolCallAcc() }
                item.get("id")?.takeIf { !it.isJsonNull }?.asString?.let { if (it.isNotEmpty()) slot.id = it }
                val fn = item.getAsJsonObject("function") ?: continue
                fn.get("name")?.takeIf { !it.isJsonNull }?.asString?.let { if (it.isNotEmpty()) slot.name = it }
                fn.get("arguments")?.takeIf { !it.isJsonNull }?.asString?.let { slot.args.append(it) }
            } catch (e: Exception) {
                // 跳过坏分片
            }
        }
    }

    private fun msgToJson(m: Msg): JsonObject = JsonObject().apply {
        addProperty("role", m.role)
        when {
            m.role == "tool" -> {
                addProperty("tool_call_id", m.toolCallId ?: "")
                addProperty("content", m.content ?: "")
            }
            m.toolCalls.isNotEmpty() -> {
                if (m.content == null) add("content", JsonNull.INSTANCE) else addProperty("content", m.content)
                add("tool_calls", JsonArray().apply {
                    m.toolCalls.forEach { tc ->
                        add(JsonObject().apply {
                            addProperty("id", tc.id)
                            addProperty("type", "function")
                            add("function", JsonObject().apply {
                                addProperty("name", tc.name)
                                addProperty("arguments", tc.args)
                            })
                        })
                    }
                })
            }
            else -> addProperty("content", m.content ?: "")
        }
    }
}
