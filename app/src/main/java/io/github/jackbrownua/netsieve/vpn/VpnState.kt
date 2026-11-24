package io.github.jackbrownua.netsieve.vpn

import android.content.Context
import io.github.jackbrownua.netsieve.data.SettingsStore
import io.github.jackbrownua.netsieve.data.SplitMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

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
    val isAutoTesting: Boolean = false,
    val selectedPresetId: String = "balanced",
    val customArgs: String = "-d 1 -o 1+s -s 2+s -a 1",
    val dnsPrimary: String = "1.1.1.1",
    val dnsSecondary: String = "8.8.8.8",
    val splitMode: SplitMode = SplitMode.EXCLUDE_SELECTED,
    val selectedApps: Set<String> = emptySet(),
    val darkThemeOverride: Boolean? = null,
    val txBytes: Long = 0L,
    val rxBytes: Long = 0L,
    val txSpeedBytesPerSec: Long = 0L,
    val rxSpeedBytesPerSec: Long = 0L,
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null
)

object VpnState {
    val presets: List<StrategyPreset> = listOf(
        StrategyPreset(
            id = "auto_cascade",
title = "Умный авто-каскад",
description = "Автопереключение на лету: Сбалансированная → Агрессивная → DisOOB",
args = listOf(
                    "-T", "2.5",
                "-d", "1", "-o", "1+s", "-s", "2+s",
                "-A", "t,r,s,c", "-u", "3600",
                "-f", "-1", "-t", "5", "-d", "1", "-s", "1+s", "-r", "1+s",
                "-A", "t,r,s,c", "-u", "3600",
                "-q", "1+s", "-d", "2+s", "-M", "r,h,d",
                "-a", "1"
            )
        ),
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

    private val blockedNoArgFlags = setOf("-D", "--daemon", "-E", "--transparent", "-h", "--help", "-v", "--version")
    private val blockedWithArgFlags = setOf("-i", "--ip", "-p", "--port", "-w", "--pidfile")

    private var store: SettingsStore? = null

    private val _state = MutableStateFlow(VpnUiState())
    val state: StateFlow<VpnUiState> = _state.asStateFlow()

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

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

    fun setAutoTesting(active: Boolean) {
        _state.update { it.copy(isAutoTesting = active) }
    }

    fun currentPreset(): StrategyPreset {
        val id = _state.value.selectedPresetId
        return presets.firstOrNull { it.id == id } ?: presets.first()
    }

    fun effectiveArgs(): List<String> {
        val cur = _state.value
        if (cur.selectedPresetId == "custom") {
            val sanitized = sanitizeCustomArgs(cur.customArgs)
            if (sanitized.isNotEmpty()) return sanitized
        }
        return currentPreset().args
    }

    fun effectiveTitle(): String {
        val cur = _state.value
        return if (cur.selectedPresetId == "custom") "Пользовательская стратегия" else currentPreset().title
    }

    fun setStatus(status: VpnStatus, error: String? = null) {
        _state.update {
            val reset = status == VpnStatus.DISCONNECTED
            it.copy(
                status = status,
                errorMessage = error,
                txBytes = if (reset) 0L else it.txBytes,
                rxBytes = if (reset) 0L else it.rxBytes,
                txSpeedBytesPerSec = if (reset) 0L else it.txSpeedBytesPerSec,
                rxSpeedBytesPerSec = if (reset) 0L else it.rxSpeedBytesPerSec
            )
        }
    }

    fun updateStats(txBytes: Long, rxBytes: Long, txSpeed: Long = 0L, rxSpeed: Long = 0L) {
        _state.update {
            it.copy(
                txBytes = txBytes,
                rxBytes = rxBytes,
                txSpeedBytesPerSec = txSpeed,
                rxSpeedBytesPerSec = rxSpeed
            )
        }
    }

    fun log(message: String) {
        val line = "[${LocalTime.now().format(timeFormatter)}] $message"
        _state.update { current ->
            val updated = (current.logs + line).takeLast(250)
            current.copy(logs = updated)
        }
    }

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }

    private fun sanitizeCustomArgs(raw: String): List<String> {
        val tokens = tokenizeArgs(raw)
        val result = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val key = token.substringBefore("=")
            when {
                key in blockedNoArgFlags -> {
                    skipped.add(token)
                    i++
                }
                key in blockedWithArgFlags -> {
                    skipped.add(token)
                    i += if (token.contains("=")) 1 else 2
                }
                token.startsWith("-i") && token.length > 2 -> {
                    skipped.add(token)
                    i++
                }
                token.startsWith("-p") && token.length > 2 -> {
                    skipped.add(token)
                    i++
                }
                token.startsWith("-w") && token.length > 2 -> {
                    skipped.add(token)
                    i++
                }
                else -> {
                    result.add(token)
                    i++
                }
            }
        }
        if (skipped.isNotEmpty()) {
            log("Заблокированы небезопасные флаги в ручных аргументах: ${skipped.joinToString(", ")}")
        }
        return result
    }

    private fun tokenizeArgs(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (ch in input.trim()) {
            when {
                quote != null -> {
                    if (ch == quote) quote = null else current.append(ch)
                }
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }
        return tokens
    }
}
