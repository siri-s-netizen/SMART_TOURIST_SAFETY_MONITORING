package com.touristsafety.app.diagnostics

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remembers the last uncaught exception so a crash seen on a phone can still be diagnosed.
 *
 * Without this, "the app closed by itself" is unreproducible: there is no logcat on a student's phone,
 * and a crash leaves no trace the user can hand over. This writes the stack trace to a small file in
 * the app's private storage; the next launch offers to show and copy it.
 *
 * It is deliberately local-only and never reports anything anywhere on its own — the text is shown to
 * the user, and they decide whether to send it. Storage is bounded so a crash loop cannot fill the disk.
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"

    /** Newest-first, and truncated: a crash loop must not grow this file without limit. */
    private const val MAX_CHARS = 40_000

    /** Installs the handler. Must be called from `Application.onCreate`, before anything can crash. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { append(app, thread, error) }
            // Always hand control back to the platform handler, which is what actually terminates the
            // process and produces the usual system crash dialog.
            previous?.uncaughtException(thread, error)
        }
    }

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** The recorded crash text, or `null` when the last run ended normally. */
    fun read(context: Context): String? = runCatching {
        val file = file(context)
        if (!file.isFile) return@runCatching null
        file.readText().trim().ifEmpty { null }
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun append(context: Context, thread: Thread, error: Throwable) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        val stack = StringWriter().also { writer ->
            error.printStackTrace(PrintWriter(writer))
        }.toString()
        val entry = "=== $stamp  ·  thread ${thread.name} ===\n$stack\n"

        val file = file(context)
        val existing = if (file.isFile) runCatching { file.readText() }.getOrDefault("") else ""
        file.writeText((entry + existing).take(MAX_CHARS))
    }
}
