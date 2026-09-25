package com.gala.galaxybot

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/** Состояние соединения с сервером. */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,

    /** Соединение разорвано, через [GalaxyClient.RECONNECT_DELAY_MS] будет новая попытка. */
    WAITING_RECONNECT,
}

/** Фаза жизни одного WebSocket-соединения. */
private enum class Phase { CONNECTING, OPEN, CLOSING, CLOSED }

/**
 * Порт логики server.js на Android (WebSocket на OkHttp).
 *
 * Схема обмена с сервером:
 *
 * ```
 * >> :ru IDENT 352 -2 4030 1 2 :GALA
 * << HAAAPSI <seed>                     -> webhash = parse(seed)
 * >> RECOVER <код восстановления>
 * << REGISTER <id> <pass> <nick>
 * >> USER <id> <pass> <nick> <webhash>
 * << 999                                -> FWLISTVER, ADDONS, MYADDONS, PHONE, JOIN,
 *                                          через 2 с REMOVE 96, ещё через 3 с OBJ_ACT
 * << PING                               -> PONG
 * ```
 *
 * После разрыва соединение восстанавливается через 5 секунд.
 *
 * Все публичные методы нужно вызывать из главного потока. Колбэки OkHttp приходят
 * из фонового потока и сразу перебрасываются в главный через [handler], поэтому
 * состояние клиента никогда не меняется из нескольких потоков одновременно.
 */
class GalaxyClient(private val listener: Listener) {

    interface Listener {
        fun onLog(kind: LogKind, text: String)
        fun onStateChanged(state: ConnectionState)
    }

    companion object {
        const val WS_URL = "wss://cs.mobstudio.ru:6672/"

        /** Переподключение после отключения (server.js: DISCONNECT_RECONNECT_DELAY). */
        const val RECONNECT_DELAY_MS = 5_000L

        /** Пауза после JOIN перед REMOVE 96. */
        const val REMOVE_DELAY_MS = 2_000L

        /** Пауза после REMOVE 96 перед OBJ_ACT. */
        const val OBJ_ACT_DELAY_MS = 3_000L

        /** Сколько ждать корректного закрытия сокета, прежде чем оборвать его принудительно. */
        const val CLOSE_TIMEOUT_MS = 3_000L

        /** Не проверять сертификат сервера (server.js: rejectUnauthorized: false). */
        const val TRUST_ALL_CERTIFICATES = true

        private const val IDENT_COMMAND = ":ru IDENT 352 -2 4030 1 2 :GALA"
    }

    private val handler = Handler(Looper.getMainLooper())

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Для WebSocket read timeout должен быть отключён, иначе OkHttp разорвёт
        // соединение, если сервер молчит дольше 10 секунд (значение по умолчанию).
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .let { if (TRUST_ALL_CERTIFICATES) UnsafeTls.apply(it) else it }
        .build()

    /** Код восстановления, который отправляется в ответ на HAAAPSI. */
    var recoverCode: String = ""
        private set

    /** Переподключаться автоматически после разрыва соединения. */
    var autoReconnect: Boolean = true
        set(value) {
            field = value
            if (!value && state == ConnectionState.WAITING_RECONNECT) {
                // Пользователь выключил автопереподключение во время ожидания
                stoppedByUser = true
                cancelReconnect()
                log(LogKind.STATE, "Автопереподключение выключено")
                state = ConnectionState.DISCONNECTED
            }
        }

    var state: ConnectionState = ConnectionState.DISCONNECTED
        private set(value) {
            if (field != value) {
                field = value
                listener.onStateChanged(value)
            }
        }

    private var current: Session? = null
    private var connectionCounter = 0
    private var reconnectRunnable: Runnable? = null

    /** true, пока пользователь не нажал «Подключиться» или уже нажал «Отключиться». */
    private var stoppedByUser = true

    // ------------------------------------------------------------------ public API

    /** Подключиться с указанным RECOVER-кодом. */
    fun connect(code: String) {
        recoverCode = code.trim()
        stoppedByUser = false
        cancelReconnect()
        openConnection()
    }

    /** Отключиться и больше не переподключаться (до следующего [connect]). */
    fun disconnect() {
        stoppedByUser = true
        cancelReconnect()

        val session = current
        if (session == null || session.phase == Phase.CLOSED) {
            current = null
            state = ConnectionState.DISCONNECTED
            return
        }
        if (session.phase == Phase.CLOSING) return // уже закрываемся

        log(LogKind.STATE, "[${session.id}] Отключение по запросу пользователя")
        state = ConnectionState.DISCONNECTING

        val socket = session.socket
        if (socket == null) {
            onSessionEnded(session, null, null)
            return
        }

        if (session.phase == Phase.OPEN) {
            session.phase = Phase.CLOSING
            socket.close(1000, "bye")
        } else {
            socket.cancel()
        }

        // Если сервер не ответит на close-фрейм — обрываем принудительно,
        // чтобы состояние «Отключение…» не зависло.
        handler.postDelayed({
            if (session.phase != Phase.CLOSED) {
                socket.cancel()
                onSessionEnded(session, null, "close timeout")
            }
        }, CLOSE_TIMEOUT_MS)
    }

    /** Отправить произвольную команду серверу (ручной ввод). */
    fun send(text: String) {
        val session = current
        if (session == null) {
            log(LogKind.ERROR, "WebSocket is not open: $text")
            return
        }
        send(session, text)
    }

    /** Полная остановка (при уничтожении ViewModel). */
    fun shutdown() {
        stoppedByUser = true
        cancelReconnect()
        handler.removeCallbacksAndMessages(null)
        current?.socket?.cancel()
        current = null
        state = ConnectionState.DISCONNECTED
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    // ------------------------------------------------------------------ connection

    private fun openConnection() {
        val existing = current
        if (existing != null && existing.phase != Phase.CLOSED) {
            log(LogKind.INFO, "WebSocket already connected or connecting")
            return
        }

        connectionCounter++
        val session = Session(connectionCounter)
        current = session
        state = ConnectionState.CONNECTING

        log(LogKind.STATE, "================================")
        log(LogKind.STATE, "WebSocket connection #${session.id}")
        log(LogKind.STATE, "Connecting to $WS_URL")
        log(LogKind.STATE, "================================")

        val request = Request.Builder().url(WS_URL).build()
        session.socket = httpClient.newWebSocket(request, session)
    }

    private fun onSocketOpen(session: Session) {
        if (current !== session || session.phase != Phase.CONNECTING) return

        session.phase = Phase.OPEN
        state = ConnectionState.CONNECTED
        log(LogKind.STATE, "[${session.id}] Connected")

        // IDENT
        send(session, IDENT_COMMAND)
    }

    private fun onSocketMessage(session: Session, raw: String) {
        if (current !== session) return
        if (session.phase != Phase.OPEN && session.phase != Phase.CLOSING) return

        // Один WebSocket-фрейм может содержать несколько строк
        val lines = raw.split("\r\n", "\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            log(LogKind.IN, raw.trim())
            return
        }

        for (line in lines) {
            log(LogKind.IN, line.trimEnd())
            // Пока сокет закрывается, входящие только логируем, не отвечая на них
            if (session.phase == Phase.OPEN) handleLine(session, line)
        }
    }

    /** Обработка одной строки от сервера — 1:1 с обработчиком `message` в server.js. */
    private fun handleLine(session: Session, line: String) {
        val ts = line.split(' ').map { it.trim() }

        when (ts[0]) {

            // HAAAPSI <seed>  ->  webhash, затем RECOVER <код>
            "HAAAPSI" -> {
                val hash = WebHash.compute(ts.getOrNull(1))
                if (hash == null) {
                    log(LogKind.ERROR, "Invalid input for parse function")
                    return
                }

                session.webhash = hash
                log(LogKind.INFO, "webhash: $hash")

                if (recoverCode.isEmpty()) {
                    log(LogKind.ERROR, "RECOVER-код не указан")
                } else {
                    send(session, "RECOVER $recoverCode")
                }
            }

            // REGISTER <id> <pass> <nick>  ->  USER <id> <pass> <nick> <webhash>
            "REGISTER" -> {
                session.myId = ts.getOrElse(1) { "" }
                session.myPass = ts.getOrElse(2) { "" }
                session.myNick = ts.getOrElse(3) { "" }

                log(LogKind.INFO, "REGISTER: ID=${session.myId} PASS=${session.myPass} NICK=${session.myNick}")

                val complete = session.myId.isNotEmpty() &&
                    session.myPass.isNotEmpty() &&
                    session.myNick.isNotEmpty() &&
                    session.webhash.isNotEmpty()

                if (complete) {
                    send(
                        session,
                        "USER ${session.myId} ${session.myPass} ${session.myNick} ${session.webhash.trim()}"
                    )
                } else {
                    log(LogKind.ERROR, "Missing required parameters")
                }
            }

            // PING -> PONG
            "PING" -> send(session, "PONG")

            // 999 -> авторизация завершена
            "999" -> {
                send(session, "FWLISTVER 311")
                send(session, "ADDONS 251824 1")
                send(session, "MYADDONS 251824 1")
                send(session, "PHONE 1920 1080 0 2 :chrome 151.0.0.0")
                send(session, "JOIN ")
                log(LogKind.INFO, "JOIN sent")

                // 2 секунды после JOIN
                postDelayed(session, REMOVE_DELAY_MS) {
                    send(session, "REMOVE 96")
                    log(LogKind.INFO, "REMOVE 96 sent")

                    // Ещё 3 секунды
                    postDelayed(session, OBJ_ACT_DELAY_MS) {
                        send(session, "OBJ_ACT 5 15170420 1 go_to_bed")
                        log(LogKind.INFO, "OBJ_ACT sent")
                    }
                }

                log(LogKind.INFO, "WebSocket authentication completed")
            }
        }
    }

    private fun onSessionEnded(session: Session, code: Int?, reason: String?) {
        if (session.phase == Phase.CLOSED) return
        session.phase = Phase.CLOSED

        // Отменяем отложенные REMOVE / OBJ_ACT этой сессии
        handler.removeCallbacksAndMessages(session)

        val details = buildString {
            if (code != null) append(" (code $code)")
            if (!reason.isNullOrBlank()) append(", reason: ").append(reason)
        }
        log(LogKind.STATE, "[${session.id}] WebSocket disconnected$details")

        if (current !== session) return
        current = null

        if (!stoppedByUser && autoReconnect) {
            scheduleReconnect()
        } else {
            state = ConnectionState.DISCONNECTED
        }
    }

    private fun scheduleReconnect() {
        cancelReconnect()
        state = ConnectionState.WAITING_RECONNECT
        log(LogKind.STATE, "Переподключение через ${RECONNECT_DELAY_MS / 1000} с...")

        val runnable = Runnable {
            reconnectRunnable = null
            log(LogKind.STATE, "Auto reconnecting WebSocket...")
            openConnection()
        }
        reconnectRunnable = runnable
        handler.postDelayed(runnable, RECONNECT_DELAY_MS)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    // ------------------------------------------------------------------ helpers

    /** Отправка команды: как и в server.js, к тексту добавляется `" \r\n"`. */
    private fun send(session: Session, text: String) {
        val socket = session.socket
        val sent = session.phase == Phase.OPEN && socket != null && socket.send("$text \r\n")

        if (sent) {
            log(LogKind.OUT, text)
        } else {
            log(LogKind.ERROR, "WebSocket is not open: $text")
        }
    }

    /** Отложенное действие, привязанное к сессии: выполняется, только если сокет ещё открыт. */
    private fun postDelayed(session: Session, delayMs: Long, action: () -> Unit) {
        handler.postAtTime(
            { if (current === session && session.phase == Phase.OPEN) action() },
            session,
            SystemClock.uptimeMillis() + delayMs,
        )
    }

    private fun log(kind: LogKind, text: String) {
        listener.onLog(kind, text)
    }

    // ------------------------------------------------------------------ session

    /**
     * Одно WebSocket-соединение. В server.js webhash / MyID / MyPass / MyNick — глобальные
     * переменные, которые очищаются при каждом подключении; здесь они живут в сессии.
     */
    private inner class Session(val id: Int) : WebSocketListener() {

        var socket: WebSocket? = null
        var phase: Phase = Phase.CONNECTING

        var webhash = ""
        var myId = ""
        var myPass = ""
        var myNick = ""

        override fun onOpen(webSocket: WebSocket, response: Response) {
            handler.post { onSocketOpen(this) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handler.post { onSocketMessage(this, text) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handler.post { onSocketMessage(this, bytes.utf8()) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Сервер прислал close-фрейм: подтверждаем закрытие, чтобы OkHttp вызвал onClosed.
            webSocket.close(1000, null)
            handler.post {
                log(LogKind.STATE, "[$id] Сервер закрывает соединение: $code $reason".trimEnd())
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handler.post { onSessionEnded(this, code, reason) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handler.post {
                if (phase != Phase.CLOSED) {
                    val details = buildString {
                        append(t.javaClass.simpleName)
                        t.message?.let { append(": ").append(it) }
                        if (response != null) append(" (HTTP ").append(response.code).append(")")
                    }
                    val kind = if (stoppedByUser) LogKind.INFO else LogKind.ERROR
                    log(kind, "[$id] WebSocket error: $details")
                }
                onSessionEnded(this, null, null)
            }
        }
    }
}
