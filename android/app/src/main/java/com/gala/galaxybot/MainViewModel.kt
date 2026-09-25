package com.gala.galaxybot

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Держит [GalaxyClient] и лог. Живёт дольше Activity, поэтому соединение
 * переживает поворот экрана.
 */
class MainViewModel(application: Application) : AndroidViewModel(application), GalaxyClient.Listener {

    companion object {
        private const val PREFS = "galaxybot"
        private const val KEY_RECOVER_CODE = "recover_code"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"

        /** Максимум строк в логе; старые строки удаляются. */
        private const val MAX_LOG_LINES = 3000
    }

    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val client = GalaxyClient(this)

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var nextLogId = 0L

    /** Последний использованный RECOVER-код. */
    val savedRecoverCode: String
        get() = prefs.getString(KEY_RECOVER_CODE, "") ?: ""

    /** Автопереподключение после разрыва (сохраняется между запусками). */
    var autoReconnect: Boolean
        get() = client.autoReconnect
        set(value) {
            client.autoReconnect = value
            prefs.edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()
        }

    init {
        client.autoReconnect = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
    }

    fun connect(code: String) {
        prefs.edit().putString(KEY_RECOVER_CODE, code).apply()
        client.connect(code)
    }

    fun disconnect() {
        client.disconnect()
    }

    fun sendCommand(text: String) {
        client.send(text)
    }

    fun clearLog() {
        _logs.value = emptyList()
    }

    // ------------------------------------------------------------ GalaxyClient.Listener

    override fun onLog(kind: LogKind, text: String) {
        val entry = LogEntry(nextLogId++, System.currentTimeMillis(), kind, text)
        val old = _logs.value
        _logs.value = if (old.size >= MAX_LOG_LINES) {
            old.drop(old.size - MAX_LOG_LINES + 1) + entry
        } else {
            old + entry
        }
    }

    override fun onStateChanged(state: ConnectionState) {
        _state.value = state
    }

    override fun onCleared() {
        client.shutdown()
    }
}
