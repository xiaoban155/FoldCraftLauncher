package com.tungsten.fcl.dfy

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志直写工具（不经过 ContentProvider）。
 *
 * FCL 自带的崩溃上报组件会经 FileProvider 读取 crash_report 文件，
 * 在部分系统上会被拒绝（provider 未导出 / 未 grantUriPermission），
 * 把真正的崩溃堆栈掩盖成一个 IOException。
 *
 * 这里把未捕获异常直接写到应用外部私有目录：
 *   /Android/data/<包名>/files/crash/dfy-crash-<时间>.txt
 * 普通文件管理器即可打开，方便用户把文件发出来定位。
 * 同时写一个 boot.txt 启动标记，用于判断「应用到底有没有走到 Application.onCreate」。
 */
object DfyCrashLog {

    private const val DIR_NAME = "crash"

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun dir(context: Context): File? {
        val candidates = listOfNotNull(
            context.getExternalFilesDir(null),
            context.filesDir,
            // 兜底：部分 ROM 的 /Android/data 目录被文件管理器屏蔽，放到公共下载目录更易取出
            File("/sdcard/Download/DFY-crash")
        )
        for (base in candidates) {
            try {
                val d = if (base.name == "DFY-crash") base else File(base, DIR_NAME)
                if (d.exists() || d.mkdirs()) return d
            } catch (ignored: Throwable) {
            }
        }
        return null
    }

    private fun writeFile(context: Context, name: String, text: String) {
        try {
            dir(context)?.let { File(it, name).writeText(text, Charsets.UTF_8) }
        } catch (ignored: Throwable) {
        }
    }

    /** 安装兜底的未捕获异常记录器（最早时机调用；任何失败都不得影响启动） */
    @JvmStatic
    fun install(context: Context) {
        try {
            val appContext = context.applicationContext ?: context
            writeFile(appContext, "boot.txt", buildString {
                append("time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
                append("package: ").append(appContext.packageName).append('\n')
                append("process: ").append(currentProcessName()).append('\n')
                append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                append("android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            })

            val old = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val sw = StringWriter()
                    PrintWriter(sw).use { pw ->
                        pw.println("time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                        pw.println("thread: " + thread.name)
                        pw.println("process: " + currentProcessName())
                        pw.println("device: " + Build.MANUFACTURER + " " + Build.MODEL)
                        pw.println("android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")")
                        pw.println("package: " + appContext.packageName)
                        pw.println("----")
                        throwable.printStackTrace(pw)
                    }
                    writeFile(appContext, "dfy-crash-" + stamp() + ".txt", sw.toString())
                } catch (ignored: Throwable) {
                }
                old?.uncaughtException(thread, throwable)
            }
        } catch (ignored: Throwable) {
            // 记录器自身失败绝不影响启动
        }
    }

    /** 主动记录一条（非崩溃场景的诊断） */
    @JvmStatic
    fun log(context: Context, tag: String, throwable: Throwable? = null) {
        try {
            val sw = StringWriter()
            PrintWriter(sw).use { pw ->
                pw.println("time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                pw.println("tag: " + tag)
                throwable?.printStackTrace(pw)
            }
            writeFile(context, "dfy-log-" + stamp() + ".txt", sw.toString())
        } catch (ignored: Throwable) {
        }
    }

    /** 最近一次崩溃文件（可能为空） */
    @JvmStatic
    fun latest(context: Context): File? {
        val d = dir(context) ?: return null
        return try {
            d.listFiles()?.filter { it.name.startsWith("dfy-crash-") }?.maxByOrNull { it.name }
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun currentProcessName(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.app.Application.getProcessName() ?: "unknown"
        } else {
            "unknown"
        }
    } catch (ignored: Throwable) {
        "unknown"
    }
}
