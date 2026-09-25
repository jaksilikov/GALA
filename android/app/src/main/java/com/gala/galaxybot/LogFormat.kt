package com.gala.galaxybot

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Форматирование строк лога (используется только в главном потоке). */
object LogFormat {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun time(millis: Long): String = timeFormat.format(Date(millis))

    /** Весь лог одним текстом — для копирования в буфер обмена. */
    fun toText(entries: List<LogEntry>): String =
        entries.joinToString(separator = "\n") { entry ->
            "${time(entry.timeMillis)} ${entry.prefix}${entry.text}"
        }
}
