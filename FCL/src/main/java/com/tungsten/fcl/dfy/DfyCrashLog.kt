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
 * 崩溃日志直写工具。
 *
 * 目的：FCL 自带的崩溃上报组件会经由 FileProvider 读取 crash_report 文件，
 * 在部分机型/多开环境下会被系统以「provider 未导出、未 grantUriPermission」拒绝，
 * 从而把真正的崩溃堆栈掩盖成一个 IOException。
 *
 * 这里绕开 ContentProvider，直接把未捕获异常写到应用外部私有目录
 * (/Android/data/<包名>/files/crash) —— 普通文件管理器就能打开，方便用户把文件发出来定位。
 */
object DfyCrashLog {

    private const val DIR_NAME = "crash"

    /** 安装兜底的未捕获异常记录器；保留原处理器（FCL 崩溃页仍会正常弹出） */
    @JvmStatic
    fun install(context: Context) {
        val appContext = context.applicationContext
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(appContext, thread, throwable)
            } catch (ignored: Throwable) {
                // 记录本身失败也不能影响原有崩溃流程
            }
            old?.uncaughtException(thread, throwable)
        }
    }

    private fun crashDir(context: Context): File? {
        val base = context.getExternalFilesDir(null) ?: File(context.filesDir, "external")
        val dir = File(base, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) return null
        return dir
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashDir(context) ?: return
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            pw.println("thread: ${thread.name}")
            pw.println("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            pw.println("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            pw.println("package: ${context.packageName}")
            pw.println("----")
            throwable.printStackTrace(pw)
        }
        File(dir, "dfy-crash-$stamp.txt").writeText(sw.toString(), Charsets.UTF_8)
    }

    /** 最近一次崩溃文件（可能为空） */
    @JvmStatic
    fun latest(context: Context): File? {
        val dir = crashDir(context) ?: return null
        return dir.listFiles()?.filter { it.name.startsWith("dfy-crash-") }?.maxByOrNull { it.name }
    }
}
