package dev.enginehost

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Captures logcat output for a process, trimmed to recent lines
 * for bug report inclusion.
 */
object LogcatCapture {
    /**
     * Captures the last [count] lines of logcat for the current
     * process, filtered to the given tags and system tags.
     * Only available when [includeLogs] is true (the person's
     * own switch in the report screen).
     */
    fun capture(
        context: Context,
        count: Int = 300,
        includeLogs: Boolean
    ): String {
        if (!includeLogs) return ""

        val packageName = context.packageName
        val tags = listOf(
            "enginehost",
            "AndroidRuntime",
            "DEBUG",
            "libc",
            "ActivityManager",
            "PackageManager"
        )

        val tagFilter = tags.joinToString(" ") { tag ->
            "$tag:*"
        }

        return runCatching {
            val cmd = arrayOf(
                "logcat",
                "-d",
                "-t", count.toString(),
                "-s", "$packageName:V",
                *tags.flatMap { listOf(it, "V") }.toTypedArray()
            )
            val process = ProcessBuilder(cmd).start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val lines = reader.lineSequence().take(count).toList()
            process.waitFor()
            lines.joinToString(sep = System.lineSeparator())
        }.getOrElse { "" }
    }
}