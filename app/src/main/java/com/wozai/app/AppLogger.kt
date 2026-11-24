package com.wozai.app

import android.content.Context
import com.wozai.app.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志（正式版与测试版均启用）。
 *
 * 日志唯一数据源 = App 私有目录 files/applog.txt（隐私目录，其它 App 无法读取）。
 * - 每条日志以「追加」方式直接写文件，实时落盘，不依赖内存缓存；
 * - 「导出日志」= 从该隐私文件读出，另存一份 txt 到公共「下载」目录；
 * - 「清空日志」= 清空该隐私文件（下载目录里已导出的 txt 需自行删除）。
 *
 * 超过 MAX_CHARS 时只保留最近部分，滚动覆盖，避免无限占用存储。
 */
object AppLogger {

    private const val LOG_FILE = "applog.txt"
    private const val MAX_CHARS = 1_000_000          // 保留最近 100 万字符（约上万行）
    private const val TRIM_SLACK = 20_000L           // 超出上限这么多才修剪一次，避免每行都全量读写

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private var installed = false
    private var appContext: Context? = null
    private var estimatedChars = 0L

    /** 在 MainActivity.onCreate 最早调用；重复调用安全 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        appContext = context.applicationContext
        // 启动时读一次文件，初始化字符计数（唯一的一次全量读，之后靠估算）
        estimatedChars = readFileText().length.toLong()
        // 崩溃捕获：堆栈直接追加进隐私日志文件（append 即落盘），再交回系统默认处理
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = java.io.StringWriter()
            throwable.printStackTrace(java.io.PrintWriter(sw))
            log("CRASH", "线程=${thread.name}\n${sw.toString()}")
            previous?.uncaughtException(thread, throwable)
        }
        log("APP", "=== 启动 === version=${BuildConfig.VERSION_NAME} debug=${BuildConfig.DEBUG}")
    }

    /** 记一行日志：追加写到隐私目录文件，实时落盘 */
    @Synchronized
    fun log(tag: String, msg: String) {
        val line = "[${timeFmt.format(Date())}] $tag: $msg\n"
        appendToFile(line)
        estimatedChars += line.length
        if (estimatedChars > MAX_CHARS + TRIM_SLACK) trim()
    }

    /** 取日志全文：直接从隐私目录文件读取（导出用的就是这个） */
    @Synchronized
    fun getLogText(): String = readFileText()

    /** 清空日志：只清隐私目录下的日志文件 */
    @Synchronized
    fun clear() {
        writeFileText("")
        estimatedChars = 0L
        log("APP", "日志已清空")
    }

    private fun logFile(): File? = appContext?.let { File(it.filesDir, LOG_FILE) }

    /** 追加一行到文件末尾，不重写全文，量大也不卡 */
    private fun appendToFile(text: String) {
        try {
            logFile()?.let { f ->
                FileOutputStream(f, true).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }
        } catch (_: Exception) {
            // 写日志失败绝不能影响 App 主流程
        }
    }

    private fun readFileText(): String = try {
        logFile()?.takeIf { it.exists() }?.readText() ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun writeFileText(text: String) {
        try {
            logFile()?.writeText(text)
        } catch (_: Exception) {
        }
    }

    /** 超限修剪：保留最近 MAX_CHARS 个字符，丢弃更早的 */
    @Synchronized
    private fun trim() {
        val text = readFileText()
        if (text.length > MAX_CHARS) {
            val kept = text.takeLast(MAX_CHARS)
            writeFileText(kept)
            estimatedChars = kept.length.toLong()
        } else {
            estimatedChars = text.length.toLong()
        }
    }
}
