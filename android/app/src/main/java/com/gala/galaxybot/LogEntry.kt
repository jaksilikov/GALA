package com.gala.galaxybot

/** Тип строки лога. */
enum class LogKind {
    /** Сообщение, пришедшее от сервера (`<<`). */
    IN,

    /** Сообщение, отправленное серверу (`>>`). */
    OUT,

    /** Информационная строка (webhash, REGISTER, «JOIN sent» и т.п.). */
    INFO,

    /** Смена состояния соединения (подключение, разрыв, переподключение). */
    STATE,

    /** Ошибка. */
    ERROR,
}

/** Одна строка лога. */
data class LogEntry(
    val id: Long,
    val timeMillis: Long,
    val kind: LogKind,
    val text: String,
) {
    /** Префикс в стиле server.js: `<<` для входящих, `>>` для исходящих. */
    val prefix: String
        get() = when (kind) {
            LogKind.IN -> "<< "
            LogKind.OUT -> ">> "
            LogKind.ERROR -> "!! "
            else -> ""
        }
}
