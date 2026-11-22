package io.github.jackbrownua.netsieve.vpn

import android.content.Context
import io.github.jackbrownua.netsieve.data.SettingsStore
import io.github.jackbrownua.netsieve.data.SplitMode
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
    val customArgs: String = "-d 1 -o 1+s -s 2+s -a 1",
    val dnsPrimary: String = "1.1.1.1",
    val dnsSecondary: String = "8.8.8.8",
    val splitMode: SplitMode = SplitMode.EXCLUDE_SELECTED,
    val selectedApps: Set<String> = emptySet(),
    val darkThemeOverride: Boolean? = null,
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
            description = "Disorder + OOB + Split по SNI (универсальный режим)",
            args = listOf("-d", "1", "-o", "1+s", "-s", "2+s", "-a", "1")
        ),
        StrategyPreset(
            id = "aggressive",
            title = "Агрессивная (спутниковый / жёсткий DPI)",
            description = "Fake TTL 5 + Disorder + Split + TLS record split",
            args = listOf("-f", "-1", "-t", "5", "-d", "1", "-s", "1+s", "-r", "1+s", "-a", "1")
        ),
        StrategyPreset(
            id = "tls_record",
            title = "TLS Record Split",
            description = "Разбиение TLS-записи + Disorder (корпоративные сети)",
            args = listOf("-r", "1+s", "-s", "1+s", "-d", "1", "-a", "1")
        ),
        StrategyPreset(
            id = "fake_ttl8",
            title = "Fake SNI (TTL 8)",
            description = "Фейковый пакет с подменой SNI + Split",
            args = listOf("-f", "-1", "-t", "8", "-n", "www.google.com", "-s", "1+s", "-a", "1")
        ),
        StrategyPreset(
            id = "disoob",
            title = "DisOOB + HTTP Mod",
            description = "Обратный OOB + модификация HTTP-заголовков",
            args = listOf("-q", "1+s", "-d", "2+s", "-M", "r,h,d", "-a", "1")
        ),
        StrategyPreset(
            id = "oob_only",
            title = "Лёгкая (OOB + Mod HTTP)",
            description = "Минимальное вмешательство без фейковых пакетов",
            args = listOf("-o", "1+s", "-M", "r,h,d", "-a", "1")
        )
    )

    private var store: SettingsStore? = null

    private val _state = MutableStateFlow(VpnUiState())
    val state: StateFlow<VpnUiState> = _state.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun init(context: Context) {
        if (store != null) return
        val s = SettingsStore(context)
        store = s
        _state.update {
            it.copy(
                selectedPresetId = s.selectedPresetId,
                customArgs = s.customArgs,
                dnsPrimary = s.dnsPrimary,
                dnsSecondary = s.dnsSecondary,
                splitMode = s.splitMode,
                selectedApps = s.selectedApps,
                darkThemeOverride = s.darkThemeOverride
            )
        }
    }

    fun selectPreset(presetId: String) {
        if (presetId == "custom" || presets.any { it.id == presetId }) {
            store?.selectedPresetId = presetId
            _state.update { it.copy(selectedPresetId = presetId) }
        }
    }

    fun updateCustomArgs(raw: String) {
        store?.customArgs = raw
        _state.update { it.copy(customArgs = raw) }
    }

    fun updateDns(primary: String, secondary: String) {
        val p = primary.trim().ifEmpty { "1.1.1.1" }
        val s = secondary.trim()
        store?.dnsPrimary = p
        store?.dnsSecondary = s
        _state.update { it.copy(dnsPrimary = p, dnsSecondary = s) }
        log("DNS обновлён: $p" + if (s.isNotEmpty()) ", $s" else "")
    }

    fun setSplitMode(mode: SplitMode) {
        store?.splitMode = mode
        _state.update { it.copy(splitMode = mode) }
    }

    fun toggleAppSelection(packageName: String) {
        _state.update { current ->
            val next = if (packageName in current.selectedApps) {
                current.selectedApps - packageName
            } else {
                current.selectedApps + packageName
            }
            store?.selectedApps = next
            current.copy(selectedApps = next)
        }
    }

    fun clearSelectedApps() {
        store?.selectedApps = emptySet()
        _state.update { it.copy(selectedApps = emptySet()) }
    }

    fun setDarkTheme(dark: Boolean) {
        store?.darkThemeOverride = dark
        _state.update { it.copy(darkThemeOverride = dark) }
    }

    fun currentPreset(): StrategyPreset {
        val id = _state.value.selectedPresetId
        return presets.firstOrNull { it.id == id } ?: presets.first()
    }

    fun effectiveArgs(): List<String> {
        val cur = _state.value
        if (cur.selectedPresetId == "custom") {
            val parsed = cur.customArgs.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (parsed.isNotEmpty()) return parsed
        }
        return currentPreset().args
    }

    fun effectiveTitle(): String {
        val cur = _state.value
        return if (cur.selectedPresetId == "custom") "Пользовательская стратегия" else currentPreset().title
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
            val updated = (current.logs + line).takeLast(250)
            current.copy(logs = updated)
        }
    }

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }
}
