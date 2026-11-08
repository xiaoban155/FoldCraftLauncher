package com.tungsten.fcl.dfy

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 链路分阶段埋点。
 *
 * 目的：崩溃时 FCL 自带的崩溃页只显示被掩盖的 FileProvider IOException，
 * 看不到 AI 请求走到哪一步。这里把每个阶段直接追加到文件（不经 ContentProvider）：
 *   /sdcard/Download/DFY-crash/ai-trace.log
 * 便于判断是「请求中」「收流中」「解析中」还是「渲染中」出问题。
 *
 * 注意：只记录阶段与计数，绝不写入 API Key、请求头或完整响应正文。
 */
object DfyTrace {

    private const val MAX_BYTES = 256 * 1024L
    private const val FILE_NAME = "ai-trace.log"

    private fun target(context: Context): File? {
        val candidates = listOf(
            File("/sdcard/Download/DFY-crash"),
            File(context.getExternalFilesDir(null) ?: context.filesDir, "crash")
        )
        for (d in candidates) {
            try {
                if (d.exists() || d.mkdirs()) return File(d, FILE_NAME)
            } catch (ignored: Throwable) {
            }
        }
        return null
    }

    fun step(context: Context, tag: String, detail: String = "") {
        try {
            val f = target(context) ?: return
            if (f.exists() && f.length() > MAX_BYTES) f.delete()
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            f.appendText("$time [$tag] $detail\n", Charsets.UTF_8)
        } catch (ignored: Throwable) {
        }
    }
}
