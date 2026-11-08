package com.tungsten.fcl.dfy

import com.google.gson.Gson
import com.tungsten.fclauncher.utils.FCLPath
import java.io.File

/**
 * AI 助手配置存储。
 *
 * 落盘文件：<FILES_DIR>/dfy-ai.json，只保存接口地址、模型名和“是否加密保存密钥”开关。
 * API Key 三种状态：
 *  1. saveKey = true  → 只存 Android Keystore 加密后的密文（最安全的持久化方式）；
 *  2. saveKey = false → 完全不落盘，只存在内存里，进程结束即消失（最严格）；
 *  3. 密文解不开（换机/清数据）→ 视为未配置，只提示重新填写，绝不回退明文。
 */
object DfyAiStore {

    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

    data class Data(
        var baseUrl: String = DEFAULT_BASE_URL,
        var model: String = "",
        var saveKey: Boolean = true,
        var keyCipher: String = ""
    )

    private val gson = Gson()
    private var cached: Data? = null

    @Volatile
    private var sessionKey: String? = null

    private fun file(): File = File(FCLPath.FILES_DIR, "dfy-ai.json")

    fun data(): Data {
        cached?.let { return it }
        val loaded = try {
            val f = file()
            if (f.exists()) gson.fromJson(f.readText(Charsets.UTF_8), Data::class.java) else null
        } catch (e: Exception) {
            null
        } ?: Data()
        if (loaded.baseUrl.isBlank()) loaded.baseUrl = DEFAULT_BASE_URL
        cached = loaded
        return loaded
    }

    fun save(data: Data) {
        cached = data
        try {
            val f = file()
            f.parentFile?.mkdirs()
            f.writeText(gson.toJson(data), Charsets.UTF_8)
        } catch (e: Exception) {
            // 配置写不进去不影响本次会话使用
        }
    }

    fun setBaseUrlAndModel(baseUrl: String, model: String) {
        val d = data()
        d.baseUrl = baseUrl
        d.model = model
        save(d)
    }

    /** 保存密钥：persist=true 走 Keystore 加密落盘，false 只留内存 */
    fun setKey(raw: String, persist: Boolean) {
        sessionKey = raw
        val d = data()
        d.saveKey = persist
        d.keyCipher = if (persist) DfyAiCrypto.encrypt(raw) else ""
        save(d)
    }

    fun key(): String {
        sessionKey?.let { if (it.isNotEmpty()) return it }
        val d = data()
        if (!d.saveKey || d.keyCipher.isEmpty()) return ""
        val plain = DfyAiCrypto.decrypt(d.keyCipher)
        if (plain.isNotEmpty()) sessionKey = plain
        return plain
    }

    fun hasKey(): Boolean = key().isNotEmpty()

    fun clearKey() {
        sessionKey = null
        val d = data()
        d.keyCipher = ""
        save(d)
    }
}
