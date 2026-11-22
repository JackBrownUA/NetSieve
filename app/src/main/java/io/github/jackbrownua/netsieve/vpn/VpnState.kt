package io.github.jackbrownua.netsieve.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class VpnStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

data class StrategyPreset(
    val id: String,
    val title: String,
    val description: String,
    val args: List<String>
)

data class VpnUiState(
    val status: VpnStatus = VpnStatus.DISCONNECTED,
    val selectedPresetId: String = "balanced",
    val txBytes: Long = 0L,
    val rxBytes: Long = 0L,
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null
)

object VpnState {
    val presets: List<StrategyPreset> = listOf(
        StrategyPreset(
            id = "balanced",
            title = "Сбалансированная (HTTPS/TLS)",
            description = "Disorder + OOB + Split по SNI (подходит для большинства сетей)",
            args = listOf("-d", "1", "-o", "1+s", "-s", "2+s", "-a", "1")
        ),
        StrategyPreset(
            id = "aggressive",
            title = "Агрессивная (спутниковый / жёсткий DPI)",
            description = "Fake TTL 5 + Disorder + Split + TLS record split",
            args = listOf("-f", "-1", "-t", "5", "-d", "1", "-s", "1+s", "-r", "1+s", "-a", "1")
        ),
        StrategyPreset(
            id = "oob_only",
            title = "Лёгкая (OOB + Mod HTTP)",
            description = "Минимальное вмешательство без фейковых пакетов",
            args = listOf("-o", "1+s", "-M", "r,h,d", "-a", "1")
        )
    )

    private val _state = MutableStateFlow(VpnUiState())
    val state: StateFlow<VpnUiState> = _state.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun selectPreset(presetId: String) {
        if (presets.any { it.id == presetId }) {
            _state.update { it.copy(selectedPresetId = presetId) }
        }
    }

    fun currentPreset(): StrategyPreset {
        val id = _state.value.selectedPresetId
        return presets.firstOrNull { it.id == id } ?: presets.first()
    }

    fun setStatus(status: VpnStatus, error: String? = null) {
        _state.update {
            it.copy(
                status = status,
                errorMessage = error,
                txBytes = if (status == VpnStatus.DISCONNECTED) 0L else it.txBytes,
                rxBytes = if (status == VpnStatus.DISCONNECTED) 0L else it.rxBytes
            )
        }
    }

    fun updateStats(txBytes: Long, rxBytes: Long) {
        _state.update { it.copy(txBytes = txBytes, rxBytes = rxBytes) }
    }

    fun log(message: String) {
        val line = "[${timeFormat.format(Date())}] $message"
        _state.update { current ->
            val updated = (current.logs + line).takeLast(200)
            current.copy(logs = updated)
        }
    }

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }
}
