package io.github.jackbrownua.netsieve.vpn

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import io.github.jackbrownua.netsieve.MainActivity

class NetSieveTileService : TileService() {

    companion object {
        fun requestSync(context: Context) {
            runCatching {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, NetSieveTileService::class.java)
                )
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        VpnState.init(applicationContext)
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        VpnState.init(applicationContext)
        val current = VpnState.state.value
        if (current.isAutoTesting) {
            updateTile()
            return
        }

        when (current.status) {
            VpnStatus.CONNECTED -> {
                val stopIntent = Intent(this, NetSieveVpnService::class.java).apply {
                    action = NetSieveVpnService.ACTION_STOP
                }
                runCatching { startService(stopIntent) }.onFailure {
                    runCatching { stopService(Intent(this, NetSieveVpnService::class.java)) }
                }
            }
            VpnStatus.DISCONNECTED -> {
                val prepareIntent = VpnService.prepare(this)
                if (prepareIntent != null) {
                    val launchIntent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val pi = PendingIntent.getActivity(
                            this,
                            2,
                            launchIntent,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                        startActivityAndCollapse(pi)
                    } else {
                        @Suppress("DEPRECATION")
                        startActivityAndCollapse(launchIntent)
                    }
                } else {
                    val startIntent = Intent(this, NetSieveVpnService::class.java).apply {
                        action = NetSieveVpnService.ACTION_START
                    }
                    ContextCompat.startForegroundService(this, startIntent)
                }
            }
            else -> Unit
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val state = VpnState.state.value
        val shortStrategy = VpnState.effectiveTitle().substringBefore(" (")

        tile.state = when {
            state.isAutoTesting -> Tile.STATE_UNAVAILABLE
            state.status == VpnStatus.CONNECTED -> Tile.STATE_ACTIVE
            state.status == VpnStatus.DISCONNECTED -> Tile.STATE_INACTIVE
            else -> Tile.STATE_UNAVAILABLE
        }
        tile.label = "NetSieve"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                state.isAutoTesting -> "Автоподбор..."
                state.status == VpnStatus.CONNECTED -> shortStrategy
                state.status == VpnStatus.CONNECTING -> "Запуск..."
                state.status == VpnStatus.DISCONNECTING -> "Остановка..."
                else -> "Отключён"
            }
        }
        tile.updateTile()
    }
}
