package com.gala.galaxybot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.gala.galaxybot.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val adapter = LogAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Отступы под системные панели и клавиатуру (edge-to-edge)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        setupControls(savedInstanceState)
        setupLog()
        observeViewModel()
    }

    // ------------------------------------------------------------------ UI setup

    private fun setupControls(savedInstanceState: Bundle?) {
        // При первом запуске подставляем последний использованный код;
        // после поворота экрана EditText восстанавливает текст сам.
        if (savedInstanceState == null) {
            binding.recoverCodeInput.setText(viewModel.savedRecoverCode)
        }

        binding.recoverCodeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                onConnectClicked()
                true
            } else {
                false
            }
        }

        binding.autoReconnectSwitch.isChecked = viewModel.autoReconnect
        binding.autoReconnectSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.autoReconnect = isChecked
        }

        binding.connectButton.setOnClickListener { onConnectClicked() }

        binding.sendButton.setOnClickListener { sendCommand() }
        binding.commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCommand()
                true
            } else {
                false
            }
        }

        binding.copyButton.setOnClickListener { copyLog() }
        binding.clearButton.setOnClickListener { viewModel.clearLog() }
    }

    private fun setupLog() {
        binding.logList.layoutManager = LinearLayoutManager(this)
        binding.logList.adapter = adapter
        binding.logList.itemAnimator = null
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.logs.collect { entries -> renderLog(entries) }
                }
                launch {
                    viewModel.state.collect { state -> renderState(state) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ actions

    private fun onConnectClicked() {
        when (viewModel.state.value) {
            ConnectionState.DISCONNECTED -> {
                val code = binding.recoverCodeInput.text?.toString()?.trim().orEmpty()
                if (code.isEmpty()) {
                    binding.recoverCodeLayout.error = getString(R.string.error_code_required)
                    binding.recoverCodeInput.requestFocus()
                    return
                }
                binding.recoverCodeLayout.error = null
                hideKeyboard()
                viewModel.connect(code)
            }

            ConnectionState.DISCONNECTING -> Unit

            else -> viewModel.disconnect()
        }
    }

    private fun sendCommand() {
        val text = binding.commandInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        viewModel.sendCommand(text)
        binding.commandInput.text?.clear()
    }

    private fun copyLog() {
        val text = LogFormat.toText(viewModel.logs.value)
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    // ------------------------------------------------------------------ rendering

    private fun renderLog(entries: List<LogEntry>) {
        // Автопрокрутка, только если пользователь и так был внизу списка
        val wasAtBottom = !binding.logList.canScrollVertically(1)
        adapter.submit(entries)
        if (wasAtBottom && entries.isNotEmpty()) {
            binding.logList.scrollToPosition(entries.size - 1)
        }
        binding.logEmptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderState(state: ConnectionState) {
        val textRes: Int
        val colorRes: Int
        when (state) {
            ConnectionState.DISCONNECTED -> {
                textRes = R.string.status_disconnected
                colorRes = R.color.status_off
            }
            ConnectionState.CONNECTING -> {
                textRes = R.string.status_connecting
                colorRes = R.color.status_pending
            }
            ConnectionState.CONNECTED -> {
                textRes = R.string.status_connected
                colorRes = R.color.status_on
            }
            ConnectionState.DISCONNECTING -> {
                textRes = R.string.status_disconnecting
                colorRes = R.color.status_pending
            }
            ConnectionState.WAITING_RECONNECT -> {
                textRes = R.string.status_waiting_reconnect
                colorRes = R.color.status_pending
            }
        }

        binding.statusText.setText(textRes)
        binding.statusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))

        val disconnected = state == ConnectionState.DISCONNECTED
        binding.connectButton.setText(
            if (disconnected) R.string.action_connect else R.string.action_disconnect
        )
        binding.connectButton.isEnabled = state != ConnectionState.DISCONNECTING
        binding.recoverCodeLayout.isEnabled = disconnected
        binding.sendButton.isEnabled = state == ConnectionState.CONNECTED
    }
}
