package io.github.jackbrownua.netsieve

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.jackbrownua.netsieve.core.NativeBridge
import io.github.jackbrownua.netsieve.ui.AppPickerScreen
import io.github.jackbrownua.netsieve.ui.AutoPickScreen
import io.github.jackbrownua.netsieve.ui.DnsSettingsScreen
import io.github.jackbrownua.netsieve.ui.HomeScreen
import io.github.jackbrownua.netsieve.ui.LogsScreen
import io.github.jackbrownua.netsieve.ui.NetSieveTheme
import io.github.jackbrownua.netsieve.vpn.NetSieveVpnService
import io.github.jackbrownua.netsieve.vpn.VpnState
import io.github.jackbrownua.netsieve.vpn.VpnStatus
import kotlinx.coroutines.launch

enum class DrawerSection(val title: String, val icon: ImageVector) {
    HOME("Главная", Icons.Rounded.Home),
    AUTO_PICK("Автоподбор стратегии", Icons.Rounded.AutoFixHigh),
    DNS("Настройки DNS", Icons.Rounded.Dns),
    APPS("Выбор приложений", Icons.Rounded.Apps),
    LOGS("Логи", Icons.Rounded.Terminal)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        VpnState.init(applicationContext)
        val abi = NativeBridge.abi()

        setContent {
            val uiState by VpnState.state.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val isDarkTheme = uiState.darkThemeOverride ?: systemDark

            NetSieveTheme(darkTheme = isDarkTheme) {
                NetSieveApp(
                    abi = abi,
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = { VpnState.setDarkTheme(!isDarkTheme) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetSieveApp(
    abi: String,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    val context = LocalContext.current
    val uiState by VpnState.state.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentSection by remember { mutableStateOf(DrawerSection.HOME) }

    val startVpnService: () -> Unit = {
        val intent = Intent(context, NetSieveVpnService::class.java).apply {
            action = NetSieveVpnService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    val stopVpnService: () -> Unit = {
        val intent = Intent(context, NetSieveVpnService::class.java).apply {
            action = NetSieveVpnService.ACTION_STOP
        }
        runCatching { context.startService(intent) }.onFailure {
            runCatching { context.stopService(Intent(context, NetSieveVpnService::class.java)) }
        }
    }

    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            VpnState.log("Пользователь отклонил разрешение VPN")
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            vpnPrepareLauncher.launch(prepareIntent)
        } else {
            startVpnService()
        }
    }

    val handleRadarClick: () -> Unit = {
        if (!uiState.isAutoTesting) {
            if (uiState.status == VpnStatus.CONNECTED) {
                stopVpnService()
            } else if (uiState.status == VpnStatus.DISCONNECTED) {
                val needNotifPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED

                if (needNotifPerm) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    val prepareIntent = VpnService.prepare(context)
                    if (prepareIntent != null) {
                        vpnPrepareLauncher.launch(prepareIntent)
                    } else {
                        startVpnService()
                    }
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
                    Text(
                        text = "NetSieve",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Локальный обход DPI • $abi • v0.4.0",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(8.dp))

                DrawerSection.entries.forEach { section ->
                    NavigationDrawerItem(
                        icon = { Icon(section.icon, contentDescription = null) },
                        label = { Text(section.title) },
                        selected = currentSection == section,
                        onClick = {
                            currentSection = section
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                Text(
                    text = "Crafted by JackBrownUA • Powered by Gemini",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                )
            }
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = if (currentSection == DrawerSection.HOME) "NetSieve" else currentSection.title,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Rounded.Menu, contentDescription = "Меню")
                        }
                    },
                    actions = {
                        IconButton(onClick = onToggleTheme) {
                            Icon(
                                imageVector = if (isDarkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                                contentDescription = "Сменить тему"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        ) { innerPadding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                color = MaterialTheme.colorScheme.background
            ) {
                when (currentSection) {
                    DrawerSection.HOME -> HomeScreen(
                        uiState = uiState,
                        abi = abi,
                        onRadarClick = handleRadarClick,
                        onSelectPreset = { VpnState.selectPreset(it) },
                        onUpdateCustomArgs = { VpnState.updateCustomArgs(it) }
                    )
                    DrawerSection.AUTO_PICK -> AutoPickScreen(
                        uiState = uiState,
                        onApplyPreset = {
                            VpnState.selectPreset(it)
                            currentSection = DrawerSection.HOME
                        }
                    )
                    DrawerSection.DNS -> DnsSettingsScreen(
                        uiState = uiState,
                        onSaveDns = { p, s -> VpnState.updateDns(p, s) }
                    )
                    DrawerSection.APPS -> AppPickerScreen(
                        uiState = uiState,
                        onSetSplitMode = { VpnState.setSplitMode(it) },
                        onToggleApp = { VpnState.toggleAppSelection(it) },
                        onClearSelection = { VpnState.clearSelectedApps() }
                    )
                    DrawerSection.LOGS -> LogsScreen(
                        logs = uiState.logs,
                        onClear = { VpnState.clearLogs() }
                    )
                }
            }
        }
    }
}
