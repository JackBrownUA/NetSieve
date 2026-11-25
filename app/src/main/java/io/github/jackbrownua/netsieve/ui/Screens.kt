package io.github.jackbrownua.netsieve.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jackbrownua.netsieve.core.DnsCheckResult
import io.github.jackbrownua.netsieve.core.DnsChecker
import io.github.jackbrownua.netsieve.core.DnsVerdict
import io.github.jackbrownua.netsieve.core.StrategyTestResult
import io.github.jackbrownua.netsieve.core.StrategyTester
import io.github.jackbrownua.netsieve.data.SplitMode
import io.github.jackbrownua.netsieve.vpn.VpnState
import io.github.jackbrownua.netsieve.vpn.VpnStatus
import io.github.jackbrownua.netsieve.vpn.VpnUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    uiState: VpnUiState,
    abi: String,
    onRadarClick: () -> Unit,
    onSelectPreset: (String) -> Unit,
    onUpdateCustomArgs: (String) -> Unit
) {
    val isDisconnected = uiState.status == VpnStatus.DISCONNECTED || uiState.status == VpnStatus.PAUSED
    val statusColor = when (uiState.status) {
        VpnStatus.CONNECTED -> NetSieveColors.Emerald
        VpnStatus.CONNECTING, VpnStatus.DISCONNECTING, VpnStatus.PAUSED -> NetSieveColors.Amber
        VpnStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RadarButton(
            status = uiState.status,
            onClick = onRadarClick
        )

        Spacer(modifier = Modifier.height(10.dp))

        GlassCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Состояние туннеля",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StatusPill(
                    text = when (uiState.status) {
                        VpnStatus.DISCONNECTED -> "Отключён"
                        VpnStatus.CONNECTING -> "Запуск..."
                        VpnStatus.CONNECTED -> "Активен"
                        VpnStatus.PAUSED -> "На паузе"
                        VpnStatus.DISCONNECTING -> "Остановка..."
                    },
                    color = statusColor
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when (uiState.status) {
                    VpnStatus.CONNECTED -> "127.0.0.1:1080 → TUN • DNS ${uiState.dnsPrimary}"
                    VpnStatus.PAUSED -> "На паузе • Нажмите радар для возобновления"
                    else -> "Локальный режим ($abi) • DNS ${uiState.dnsPrimary}"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            val splitSummary = when {
                uiState.selectedApps.isEmpty() -> "Все приложения"
                uiState.splitMode == SplitMode.INCLUDE_SELECTED -> "Только выбранные (${uiState.selectedApps.size})"
                else -> "Кроме выбранных (${uiState.selectedApps.size})"
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Split Tunneling: $splitSummary",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (uiState.errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
           StatBlock(
                    label = "Загрузка (↓ ${formatSpeed(uiState.rxSpeedBytesPerSec)})",
                    value = formatBytes(uiState.rxBytes)
                )
                StatBlock(
                    label = "Отдача (↑ ${formatSpeed(uiState.txSpeedBytesPerSec)})",
                    value = formatBytes(uiState.txBytes)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        GlassCard {
            Text(
                text = "Стратегия обхода DPI",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            VpnState.presets.forEach { preset ->
                val selected = uiState.selectedPresetId == preset.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = isDisconnected) { onSelectPreset(preset.id) }
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selected,
                        enabled = isDisconnected,
                        onClick = { onSelectPreset(preset.id) }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = preset.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = preset.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = isDisconnected) { onSelectPreset("custom") }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = uiState.selectedPresetId == "custom",
                    enabled = isDisconnected,
                    onClick = { onSelectPreset("custom") }
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Свои аргументы ByeDPI",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Ручная строка параметров командной строки",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (uiState.selectedPresetId == "custom") {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.customArgs,
                    onValueChange = onUpdateCustomArgs,
                    enabled = isDisconnected,
                    label = { Text("Аргументы (без -i и -p)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun StatBlock(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoPickScreen(
    uiState: VpnUiState,
    onApplyPreset: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var testDomain by remember { mutableStateOf("github.com") }
    var isRunning by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableStateOf(0) }
    var currentTitle by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<StrategyTestResult>>(emptyList()) }
    var testJob by remember { mutableStateOf<Job?>(null) }

    val vpnActive = uiState.status != VpnStatus.DISCONNECTED
    val bestResult = results.filter { it.success }.minByOrNull { it.latencyMs }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        GlassCard {
            Text(
                text = "Автоподбор рабочей стратегии",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "NetSieve по очереди проверит ${VpnState.presets.size} стратегий через локальный SOCKS5 и найдёт самую быструю для этой сети.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))
            Spacer(modifier = Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("GitHub" to "github.com", "MarineTraffic" to "www.marinetraffic.com", "YouTube" to "youtube.com", "Discord" to "discord.com", "Telegram" to "telegram.org").forEach { (label, host) ->
                    FilterChip(selected = testDomain.trim().lowercase().contains(host), onClick = { if (!isRunning) testDomain = host }, label = { Text(label) })
                    } }
            OutlinedTextField(
                value = testDomain,
                onValueChange = { testDomain = it },
                enabled = !isRunning,
                label = { Text("Целевой домен для проверки") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )

            if (vpnActive) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Сначала остановите VPN на Главной, чтобы автоподбор смог тестировать стратегии без конфликта.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NetSieveColors.Amber
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
             Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isRunning) {
                    Button(
                        onClick = {
                            results = emptyList()
                            currentIndex = 0
                            isRunning = true
                            testJob = scope.launch {
                                try {
                                    StrategyTester.runAll(
                                        rawDomain = testDomain,
                                        presets = VpnState.presets,
                                        onStepStart = { preset, idx ->
                                            currentIndex = idx
                                            currentTitle = preset.title
                                        },
                                        onStepResult = { res ->
                                            results = results + res
                                        }
                                    )
                                } finally {
                                    isRunning = false
                                }
                            }
                        },
                        enabled = !vpnActive,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Запустить тест", maxLines = 1)
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            StrategyTester.abortActive()
                            testJob?.cancel()
                            isRunning = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Остановить", maxLines = 1)
                    }
                }

                if (bestResult != null && !isRunning) {
                    val shortName = bestResult.preset.title.substringBefore(" (")
                    Button(
                        onClick = { onApplyPreset(bestResult.preset.id) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = "Применить: $shortName (${bestResult.latencyMs} мс)",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
             } 
      

            if (isRunning) {
                Spacer(modifier = Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { (currentIndex + 1).toFloat() / VpnState.presets.size.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Проверяю (${currentIndex + 1}/${VpnState.presets.size}): $currentTitle",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (results.isNotEmpty()) {
            GlassCard {
                Text(
                    text = "Результаты проверки",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                results.forEachIndexed { index, item ->
                    val isSelected = uiState.selectedPresetId == item.preset.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (item.success) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = if (item.success) NetSieveColors.Emerald else NetSieveColors.Crimson,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.preset.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (item.success) {
                                    "HTTP ${item.httpCode} • ${item.latencyMs} мс"
                                } else {
                                    item.error ?: "Нет ответа"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (item.success) {
                            TextButton(onClick = { onApplyPreset(item.preset.id) }) {
                                Text(if (isSelected) "Выбрана" else "Выбрать")
                            }
                        }
                    }
                    if (index < results.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DnsSettingsScreen(
    uiState: VpnUiState,
    onSaveDns: (String, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var primaryInput by remember(uiState.dnsPrimary) { mutableStateOf(uiState.dnsPrimary) }
    var secondaryInput by remember(uiState.dnsSecondary) { mutableStateOf(uiState.dnsSecondary) }
    var savedNotice by remember { mutableStateOf(false) }

    var checkDomain by remember { mutableStateOf("github.com") }
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<DnsCheckResult?>(null) }

    val dnsPresets = listOf(
        Triple("Cloudflare", "1.1.1.1", "1.0.0.1"),
        Triple("Google", "8.8.8.8", "8.8.4.4"),
        Triple("Quad9", "9.9.9.9", "149.112.112.112"),
        Triple("AdGuard", "94.140.14.14", "94.140.15.15")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        GlassCard {
            Text(
                text = "DNS-серверы для туннеля",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Применяются при следующем запуске VPN. Помогают обойти блокировку или подмену системного DNS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                dnsPresets.forEach { (name, p, s) ->
                    FilterChip(
                        selected = primaryInput.trim() == p,
                        onClick = {
                            primaryInput = p
                            secondaryInput = s
                            savedNotice = false
                        },
                        label = { Text("$name ($p)") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = primaryInput,
                onValueChange = {
                    primaryInput = it
                    savedNotice = false
                },
                label = { Text("Основной DNS (IPv4)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = secondaryInput,
                onValueChange = {
                    secondaryInput = it
                    savedNotice = false
                },
                label = { Text("Резервный DNS (опционально)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        onSaveDns(primaryInput, secondaryInput)
                        savedNotice = true
                    },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Сохранить DNS")
                }
                if (savedNotice) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Сохранено",
                        color = NetSieveColors.Emerald,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        GlassCard {
            Text(
                text = "Проверка подмены DNS (System vs DoH)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Сравнивает ответ системного DNS сети с зашифрованным DoH (Cloudflare / Google).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = checkDomain,
                onValueChange = { checkDomain = it },
                enabled = !checking,
                label = { Text("Домен для проверки") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    checking = true
                    scope.launch {
                        try {
                            checkResult = DnsChecker.check(checkDomain)
                        } finally {
                            checking = false
                        }
                    }
                },
                enabled = !checking,
                shape = RoundedCornerShape(14.dp)
            ) {
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Проверяю...")
                } else {
                    Text("Проверить DNS")
                }
            }

            val res = checkResult
            if (res != null) {
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(12.dp))

                val (verdictLabel, verdictColor, verdictIcon) = when (res.verdict) {
                    DnsVerdict.OK -> Triple("Подмены нет", NetSieveColors.Emerald, Icons.Rounded.CheckCircle)
                    DnsVerdict.SUSPICIOUS -> Triple("Подозрительно", NetSieveColors.Amber, Icons.Rounded.WarningAmber)
                    DnsVerdict.SPOOFED -> Triple("Подмена DNS!", NetSieveColors.Crimson, Icons.Rounded.ErrorOutline)
                    DnsVerdict.ERROR -> Triple("Ошибка сети", NetSieveColors.Crimson, Icons.Rounded.ErrorOutline)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(verdictIcon, contentDescription = null, tint = verdictColor)
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusPill(text = verdictLabel, color = verdictColor)
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = res.details,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Системный DNS (${res.systemLatencyMs} мс): ${res.systemIps.ifEmpty { listOf("нет ответа") }.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "${res.dohProvider} (${res.dohLatencyMs} мс): ${res.dohIps.ifEmpty { listOf("нет ответа") }.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

private data class InstalledAppEntry(
    val label: String,
    val packageName: String,
    val isSystem: Boolean
)

@Composable
fun AppPickerScreen(
    uiState: VpnUiState,
    onSetSplitMode: (SplitMode) -> Unit,
    onToggleApp: (String) -> Unit,
    onClearSelection: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var allApps by remember { mutableStateOf<List<InstalledAppEntry>>(emptyList()) }

    LaunchedEffect(Unit) {
        loading = true
        allApps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val myPkg = context.packageName
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != myPkg }
                .map { appInfo ->
                    val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                        .getOrDefault(appInfo.packageName)
                    val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    InstalledAppEntry(label = label, packageName = appInfo.packageName, isSystem = isSys)
                }
                .sortedBy { it.label.lowercase() }
        }
        loading = false
    }

    val filteredApps = remember(allApps, searchQuery, showSystemApps, uiState.selectedApps) {
        val q = searchQuery.trim().lowercase()
        allApps
            .filter { showSystemApps || !it.isSystem || it.packageName in uiState.selectedApps }
            .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
            .sortedWith(
                compareByDescending<InstalledAppEntry> { it.packageName in uiState.selectedApps }
                    .thenBy { it.label.lowercase() }
            )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassCard {
            Text(
                text = "Режим Split Tunneling",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = uiState.splitMode == SplitMode.EXCLUDE_SELECTED,
                    onClick = { onSetSplitMode(SplitMode.EXCLUDE_SELECTED) },
                    label = { Text("Кроме выбранных") }
                )
                FilterChip(
                    selected = uiState.splitMode == SplitMode.INCLUDE_SELECTED,
                    onClick = { onSetSplitMode(SplitMode.INCLUDE_SELECTED) },
                    label = { Text("Только выбранные") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text("Поиск приложения...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Системные",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = showSystemApps,
                        onCheckedChange = { showSystemApps = it }
                    )
                }
                if (uiState.selectedApps.isNotEmpty()) {
                    TextButton(onClick = onClearSelection) {
                        Text("Сбросить (${uiState.selectedApps.size})")
                    }
                }
            }
        }

        GlassCard(modifier = Modifier.weight(1f)) {
            if (loading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Загрузка списка приложений...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val checked = app.packageName in uiState.selectedApps
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleApp(app.packageName) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { onToggleApp(app.packageName) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    }
                }
            }
        }
    }
}

@Composable
fun LogsScreen(
    logs: List<String>,
    onClear: () -> Unit
) {
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Записей: ${logs.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row {
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(logs.joinToString("\n"))) },
                    enabled = logs.isNotEmpty()
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Копировать логи")
                }
                IconButton(onClick = onClear, enabled = logs.isNotEmpty()) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Очистить логи")
                }
            }
        }

        GlassCard(modifier = Modifier.weight(1f)) {
            if (logs.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Журнал событий пуст",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logs) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes Б"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format("%.1f КБ", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format("%.2f МБ", mb)
    val gb = mb / 1024.0
    return String.format("%.2f ГБ", gb)
}
private fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec < 1024L) return "$bytesPerSec Б/с"
    val kb = bytesPerSec / 1024.0
    if (kb < 1024.0) return String.format("%.0f КБ/с", kb)
    val mb = kb / 1024.0
    return String.format("%.1f МБ/с", mb)
}
