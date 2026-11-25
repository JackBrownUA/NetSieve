package io.github.jackbrownua.netsieve.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jackbrownua.netsieve.vpn.VpnStatus

@Composable
fun RadarButton(
    status: VpnStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnected = status == VpnStatus.CONNECTED
    val isTransitioning = status == VpnStatus.CONNECTING || status == VpnStatus.DISCONNECTING

    val coreColor by animateColorAsState(
        targetValue = when (status) {
            VpnStatus.CONNECTED -> Color(0xFF10B981)
            VpnStatus.CONNECTING, VpnStatus.DISCONNECTING -> Color(0xFFF59E0B)
            VpnStatus.PAUSED -> Color(0xFFF59E0B)
            VpnStatus.DISCONNECTED -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(400),
        label = "radarCoreColor"
    )

    val secondaryColor by animateColorAsState(
        targetValue = when (status) {
            VpnStatus.CONNECTED -> Color(0xFF059669)
            VpnStatus.PAUSED -> Color(0xFFB45309)
            VpnStatus.CONNECTING, VpnStatus.DISCONNECTING -> Color(0xFFD97706)
            VpnStatus.DISCONNECTED -> MaterialTheme.colorScheme.primaryContainer
        },
        animationSpec = tween(400),
        label = "radarSecondaryColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "radarWaves")
    val wave1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave1"
    )
    val wave2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, delayMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave2"
    )
    val wave3 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, delayMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave3"
    )

    Box(
        modifier = modifier.size(230.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(230.dp)) {
            val minR = 74.dp.toPx()
            val maxR = size.minDimension / 2f

            if (isConnected || isTransitioning) {
                listOf(wave1, wave2, wave3).forEach { progress ->
                    val radius = minR + (maxR - minR) * progress
                    val alpha = (1f - progress) * 0.45f
                    drawCircle(
                        color = coreColor.copy(alpha = alpha),
                        radius = radius,
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                }
            } else {
                drawCircle(
                    color = coreColor.copy(alpha = 0.16f),
                    radius = minR + 14.dp.toPx(),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }

        Box(
            modifier = Modifier
                .size(148.dp)
                .shadow(
                    elevation = if (isConnected) 18.dp else 10.dp,
                    shape = CircleShape,
                    ambientColor = coreColor,
                    spotColor = coreColor
                )
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(coreColor, secondaryColor)
                    )
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !isTransitioning,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = when (status) { VpnStatus.CONNECTED -> Icons.Rounded.Shield; VpnStatus.PAUSED -> Icons.Rounded.Pause; else -> Icons.Rounded.PowerSettingsNew },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when (status) {
                        VpnStatus.DISCONNECTED -> "ЗАПУСТИТЬ"
                        VpnStatus.CONNECTING -> "ЗАПУСК..."
                        VpnStatus.CONNECTED -> "АКТИВЕН"
                        VpnStatus.PAUSED -> "НА ПАУЗЕ"
                        VpnStatus.DISCONNECTING -> "СТОП..."
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
