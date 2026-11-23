package io.github.jackbrownua.netsieve.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import hev.htproxy.TProxyService
import io.github.jackbrownua.netsieve.MainActivity
import io.github.jackbrownua.netsieve.R
import io.github.jackbrownua.netsieve.core.NativeBridge
import io.github.jackbrownua.netsieve.data.SplitMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

class NetSieveVpnService : VpnService() {

    companion object {
        const val ACTION_START = "io.github.jackbrownua.netsieve.START"
        const val ACTION_STOP = "io.github.jackbrownua.netsieve.STOP"
        private const val CHANNEL_ID = "netsieve_vpn_channel"
        private const val NOTIFICATION_ID = 1001
        private const val SOCKS_HOST = "127.0.0.1"
        private const val SOCKS_PORT = 1080
        private const val TUN_MTU = 8500
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var tunInterface: ParcelFileDescriptor? = null

    @Volatile
    private var byeDpiThread: Thread? = null

    @Volatile
    private var tproxyThread: Thread? = null

    @Volatile
    private var isStopping = false

    private var statsJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        VpnState.init(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                if (VpnState.state.value.status == VpnStatus.DISCONNECTED) {
                    startVpn()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        VpnState.log("VPN-разрешение отозвано системой")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpnInternal()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startVpn() {
        isStopping = false
        VpnState.setStatus(VpnStatus.CONNECTING)
        VpnState.log("Запуск NetSieve...")

        val notification = buildNotification("NetSieve", "Подключение...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        serviceScope.launch {
            try {
                if (!NativeBridge.ensureLoaded()) {
                    throw IllegalStateException("Не удалось загрузить libnetsieve.so")
                }

                val strategyArgs = VpnState.effectiveArgs()
                val strategyTitle = VpnState.effectiveTitle()
                val shortStrategy = strategyTitle.substringBefore(" (")
                val args = (listOf("-i", SOCKS_HOST, "-p", SOCKS_PORT.toString()) + strategyArgs)
                    .toTypedArray()

                VpnState.log("Запуск ByeDPI ($strategyTitle): ${strategyArgs.joinToString(" ")}")

                val bdThread = Thread({
                    val rc = NativeBridge.runByeDpi(args)
                    VpnState.log("ByeDPI завершил работу (rc=$rc)")
                    if (!isStopping) {
                        stopVpn()
                    }
                }, "NetSieve-ByeDPI")
                byeDpiThread = bdThread
                bdThread.start()

                if (!waitForSocksReady(SOCKS_HOST, SOCKS_PORT)) {
                    throw IllegalStateException("Таймаут запуска локального прокси $SOCKS_HOST:$SOCKS_PORT")
                }
                VpnState.log("Локальный прокси $SOCKS_HOST:$SOCKS_PORT готов")

                val pfd = buildTunInterface()
                    ?: throw IllegalStateException("VpnService.Builder.establish() вернул null")
                tunInterface = pfd

                val configFile = writeTProxyConfig()
                val fd = pfd.fd

                val tpThread = Thread({
                    VpnState.log("Запуск hev-socks5-tunnel (fd=$fd)")
                    TProxyService.TProxyStartService(configFile.absolutePath, fd)
                    VpnState.log("hev-socks5-tunnel остановлен")
                }, "NetSieve-Tun2Socks")
                tproxyThread = tpThread
                tpThread.start()

                delay(250)
                if (isStopping) return@launch

                VpnState.setStatus(VpnStatus.CONNECTED)
                VpnState.log("Защита активна")
                updateNotification("NetSieve • ↓ 0 Б/с ↑ 0 Б/с", "$shortStrategy • 0 Б")
                startStatsLoop(shortStrategy)
            } catch (t: Throwable) {
                VpnState.log("Ошибка запуска: ${t.message ?: t.javaClass.simpleName}")
                VpnState.setStatus(VpnStatus.DISCONNECTED, t.message ?: "Ошибка запуска VPN")
                stopVpnInternal()
                stopSelf()
            }
        }
    }

    private fun buildTunInterface(): ParcelFileDescriptor? {
        val current = VpnState.state.value
        val builder = Builder()
            .setSession("NetSieve")
            .setMtu(TUN_MTU)
            .addAddress("10.10.10.1", 24)
            .addRoute("0.0.0.0", 0)

        val primaryDns = current.dnsPrimary.trim().ifEmpty { "1.1.1.1" }
        runCatching { builder.addDnsServer(primaryDns) }.onFailure {
            VpnState.log("Некорректный первичный DNS ($primaryDns), использую 1.1.1.1")
            builder.addDnsServer("1.1.1.1")
        }

        val secondaryDns = current.dnsSecondary.trim()
        if (secondaryDns.isNotEmpty()) {
            runCatching { builder.addDnsServer(secondaryDns) }.onFailure {
                VpnState.log("Некорректный вторичный DNS ($secondaryDns), пропущен")
            }
        }

        val selected = current.selectedApps.filter { it != packageName }
        if (current.splitMode == SplitMode.INCLUDE_SELECTED && selected.isNotEmpty()) {
            var added = 0
            selected.forEach { pkg ->
                runCatching {
                    builder.addAllowedApplication(pkg)
                    added++
                }
            }
            VpnState.log("Split Tunneling: только выбранные ($added прил.)")
            if (added == 0) {
                runCatching { builder.addDisallowedApplication(packageName) }
            }
        } else {
            runCatching { builder.addDisallowedApplication(packageName) }
            var excluded = 0
            selected.forEach { pkg ->
                runCatching {
                    builder.addDisallowedApplication(pkg)
                    excluded++
                }
            }
            if (excluded > 0) {
                VpnState.log("Split Tunneling: исключено $excluded прил.")
            }
        }

        return builder.establish()
    }

    private fun writeTProxyConfig(): File {
        val confFile = File(cacheDir, "tproxy.conf")
        val content = """
            tunnel:
              mtu: $TUN_MTU
            socks5:
              port: $SOCKS_PORT
              address: $SOCKS_HOST
              udp: 'udp'
            misc:
              task-stack-size: 81920
              log-level: warn
        """.trimIndent()
        confFile.writeText(content)
        return confFile
    }

    private suspend fun waitForSocksReady(host: String, port: Int): Boolean {
        repeat(25) {
            if (isStopping) return false
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 100)
                }
                return true
            } catch (_: Throwable) {
                delay(100)
            }
        }
        return false
    }

    private fun startStatsLoop(shortStrategy: String) {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            var prevTx = 0L
            var prevRx = 0L
            var prevMs = System.currentTimeMillis()
            while (isActive && !isStopping) {
                try {
                    val stats = TProxyService.TProxyGetStats()
                    if (stats.size >= 4) {
                        val tx = stats[1]
                        val rx = stats[3]
                        val now = System.currentTimeMillis()
                        val dt = (now - prevMs).coerceAtLeast(250L)
                        val txSpeed = ((tx - prevTx).coerceAtLeast(0L) * 1000L) / dt
                        val rxSpeed = ((rx - prevRx).coerceAtLeast(0L) * 1000L) / dt
                        prevTx = tx
                        prevRx = rx
                        prevMs = now

                        VpnState.updateStats(txBytes = tx, rxBytes = rx)
                        updateNotification(
                            title = "NetSieve • ↓ ${formatSpeed(rxSpeed)}  ↑ ${formatSpeed(txSpeed)}",
                            text = "$shortStrategy • ↓ ${formatCompactBytes(rx)}  ↑ ${formatCompactBytes(tx)}"
                        )
                    }
                } catch (_: Throwable) {
                }
                delay(1000)
            }
        }
    }

    private fun stopVpn() {
        if (isStopping) return
        isStopping = true
        VpnState.setStatus(VpnStatus.DISCONNECTING)
        VpnState.log("Остановка NetSieve...")

        serviceScope.launch {
            stopVpnInternal()
            VpnState.setStatus(VpnStatus.DISCONNECTED)
            VpnState.log("Отключено")
            stopSelf()
        }
    }

    @Synchronized
    private fun stopVpnInternal() {
        isStopping = true
        statsJob?.cancel()
        statsJob = null

        try {
            if (TProxyService.TProxyIsRunning()) {
                TProxyService.TProxyStopService()
            }
        } catch (_: Throwable) {
        }

        try {
            tunInterface?.close()
        } catch (_: Throwable) {
        }
        tunInterface = null

        try {
            NativeBridge.stopByeDpi()
        } catch (_: Throwable) {
        }

        try {
            tproxyThread?.join(1000)
        } catch (_: InterruptedException) {
        }
        tproxyThread = null

        try {
            byeDpiThread?.join(1000)
        } catch (_: InterruptedException) {
        }
        byeDpiThread = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NetSieve VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Статус локального обхода DPI"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, NetSieveVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_netsieve)
            .setColor(0xFF0284C7.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stopIntent)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec < 1024L) return "$bytesPerSec Б/с"
        val kb = bytesPerSec / 1024.0
        if (kb < 1024.0) return String.format("%.0f КБ/с", kb)
        val mb = kb / 1024.0
        return String.format("%.1f МБ/с", mb)
    }

    private fun formatCompactBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes Б"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format("%.1f КБ", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format("%.1f МБ", mb)
        val gb = mb / 1024.0
        return String.format("%.2f ГБ", gb)
    }
}
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import hev.htproxy.TProxyService
import io.github.jackbrownua.netsieve.MainActivity
import io.github.jackbrownua.netsieve.core.NativeBridge
import io.github.jackbrownua.netsieve.data.SplitMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

class NetSieveVpnService : VpnService() {

    companion object {
        const val ACTION_START = "io.github.jackbrownua.netsieve.START"
        const val ACTION_STOP = "io.github.jackbrownua.netsieve.STOP"
        private const val CHANNEL_ID = "netsieve_vpn_channel"
        private const val NOTIFICATION_ID = 1001
        private const val SOCKS_HOST = "127.0.0.1"
        private const val SOCKS_PORT = 1080
        private const val TUN_MTU = 8500
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var tunInterface: ParcelFileDescriptor? = null

    @Volatile
    private var byeDpiThread: Thread? = null

    @Volatile
    private var tproxyThread: Thread? = null

    @Volatile
    private var isStopping = false

    private var statsJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        VpnState.init(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                if (VpnState.state.value.status == VpnStatus.DISCONNECTED) {
                    startVpn()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        VpnState.log("VPN-разрешение отозвано системой")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpnInternal()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startVpn() {
        isStopping = false
        VpnState.setStatus(VpnStatus.CONNECTING)
        VpnState.log("Запуск NetSieve...")

        val notification = buildNotification("Подключение локального туннеля...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        serviceScope.launch {
            try {
                if (!NativeBridge.ensureLoaded()) {
                    throw IllegalStateException("Не удалось загрузить libnetsieve.so")
                }

                val strategyArgs = VpnState.effectiveArgs()
                val strategyTitle = VpnState.effectiveTitle()
                val args = (listOf("-i", SOCKS_HOST, "-p", SOCKS_PORT.toString()) + strategyArgs)
                    .toTypedArray()

                VpnState.log("Запуск ByeDPI ($strategyTitle): ${strategyArgs.joinToString(" ")}")

                val bdThread = Thread({
                    val rc = NativeBridge.runByeDpi(args)
                    VpnState.log("ByeDPI завершил работу (rc=$rc)")
                    if (!isStopping) {
                        stopVpn()
                    }
                }, "NetSieve-ByeDPI")
                byeDpiThread = bdThread
                bdThread.start()

                if (!waitForSocksReady(SOCKS_HOST, SOCKS_PORT)) {
                    throw IllegalStateException("Таймаут запуска локального прокси $SOCKS_HOST:$SOCKS_PORT")
                }
                VpnState.log("Локальный прокси $SOCKS_HOST:$SOCKS_PORT готов")

                val pfd = buildTunInterface()
                    ?: throw IllegalStateException("VpnService.Builder.establish() вернул null")
                tunInterface = pfd

                val configFile = writeTProxyConfig()
                val fd = pfd.fd

                val tpThread = Thread({
                    VpnState.log("Запуск hev-socks5-tunnel (fd=$fd)")
                    TProxyService.TProxyStartService(configFile.absolutePath, fd)
                    VpnState.log("hev-socks5-tunnel остановлен")
                }, "NetSieve-Tun2Socks")
                tproxyThread = tpThread
                tpThread.start()

                delay(250)
                if (isStopping) return@launch

                VpnState.setStatus(VpnStatus.CONNECTED)
                VpnState.log("Защита активна")
                updateNotification("Активно • $strategyTitle")
                startStatsLoop()
            } catch (t: Throwable) {
                VpnState.log("Ошибка запуска: ${t.message ?: t.javaClass.simpleName}")
                VpnState.setStatus(VpnStatus.DISCONNECTED, t.message ?: "Ошибка запуска VPN")
                stopVpnInternal()
                stopSelf()
            }
        }
    }

    private fun buildTunInterface(): ParcelFileDescriptor? {
        val current = VpnState.state.value
        val builder = Builder()
            .setSession("NetSieve")
            .setMtu(TUN_MTU)
            .addAddress("10.10.10.1", 24)
            .addRoute("0.0.0.0", 0)

        val primaryDns = current.dnsPrimary.trim().ifEmpty { "1.1.1.1" }
        runCatching { builder.addDnsServer(primaryDns) }.onFailure {
            VpnState.log("Некорректный первичный DNS ($primaryDns), использую 1.1.1.1")
            builder.addDnsServer("1.1.1.1")
        }

        val secondaryDns = current.dnsSecondary.trim()
        if (secondaryDns.isNotEmpty()) {
            runCatching { builder.addDnsServer(secondaryDns) }.onFailure {
                VpnState.log("Некорректный вторичный DNS ($secondaryDns), пропущен")
            }
        }

        val selected = current.selectedApps.filter { it != packageName }
        if (current.splitMode == SplitMode.INCLUDE_SELECTED && selected.isNotEmpty()) {
            var added = 0
            selected.forEach { pkg ->
                runCatching {
                    builder.addAllowedApplication(pkg)
                    added++
                }
            }
            VpnState.log("Split Tunneling: только выбранные ($added прил.)")
            if (added == 0) {
                runCatching { builder.addDisallowedApplication(packageName) }
            }
        } else {
            runCatching { builder.addDisallowedApplication(packageName) }
            var excluded = 0
            selected.forEach { pkg ->
                runCatching {
                    builder.addDisallowedApplication(pkg)
                    excluded++
                }
            }
            if (excluded > 0) {
                VpnState.log("Split Tunneling: исключено $excluded прил.")
            }
        }

        return builder.establish()
    }

    private fun writeTProxyConfig(): File {
        val confFile = File(cacheDir, "tproxy.conf")
        val content = """
            tunnel:
              mtu: $TUN_MTU
            socks5:
              port: $SOCKS_PORT
              address: $SOCKS_HOST
              udp: 'udp'
            misc:
              task-stack-size: 81920
              log-level: warn
        """.trimIndent()
        confFile.writeText(content)
        return confFile
    }

    private suspend fun waitForSocksReady(host: String, port: Int): Boolean {
        repeat(25) {
            if (isStopping) return false
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 100)
                }
                return true
            } catch (_: Throwable) {
                delay(100)
            }
        }
        return false
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive && !isStopping) {
                try {
                    val stats = TProxyService.TProxyGetStats()
                    if (stats.size >= 4) {
                        VpnState.updateStats(txBytes = stats[1], rxBytes = stats[3])
                    }
                } catch (_: Throwable) {
                }
                delay(1000)
            }
        }
    }

    private fun stopVpn() {
        if (isStopping) return
        isStopping = true
        VpnState.setStatus(VpnStatus.DISCONNECTING)
        VpnState.log("Остановка NetSieve...")

        serviceScope.launch {
            stopVpnInternal()
            VpnState.setStatus(VpnStatus.DISCONNECTED)
            VpnState.log("Отключено")
            stopSelf()
        }
    }

    @Synchronized
    private fun stopVpnInternal() {
        isStopping = true
        statsJob?.cancel()
        statsJob = null

        try {
            if (TProxyService.TProxyIsRunning()) {
                TProxyService.TProxyStopService()
            }
        } catch (_: Throwable) {
        }

        try {
            tunInterface?.close()
        } catch (_: Throwable) {
        }
        tunInterface = null

        try {
            NativeBridge.stopByeDpi()
        } catch (_: Throwable) {
        }

        try {
            tproxyThread?.join(1000)
        } catch (_: InterruptedException) {
        }
        tproxyThread = null

        try {
            byeDpiThread?.join(1000)
        } catch (_: InterruptedException) {
        }
        byeDpiThread = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NetSieve VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Статус локального обхода DPI"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, NetSieveVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("NetSieve")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
