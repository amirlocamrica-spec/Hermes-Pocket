package com.hermes.android.ui.component

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.android.ui.viewmodel.ChatConnectionState

/**
 * A reactive pet companion that visually reflects the gateway connection state.
 * Lightweight implementation (no Glance/widget dependency) — lives inside the
 * chat screen as an ambient status indicator with personality.
 */
/** Pure mapping from connection state to pet visual - testable without Compose. */
internal data class PetVisual(val emoji: String, val label: String)

internal fun mapConnectionToPetVisual(state: ChatConnectionState): PetVisual = when (state) {
    ChatConnectionState.Connected -> PetVisual("🐾", "آنلاین")
    ChatConnectionState.Connecting -> PetVisual("🔍", "در حال اتصال...")
    ChatConnectionState.Reconnecting -> PetVisual("🔄", "تلاش مجدد...")
    ChatConnectionState.Disconnected -> PetVisual("😴", "آفلاین")
    ChatConnectionState.Failed -> PetVisual("😵", "خطا!")
}

@Composable
fun PetCompanion(
    connectionState: ChatConnectionState,
    modifier: Modifier = Modifier,
) {
    val (emoji, label) = mapConnectionToPetVisual(connectionState)

    // Breathing animation when connected; bounce when reconnecting; static otherwise
    val infiniteTransition = rememberInfiniteTransition(label = "pet")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = when (connectionState) {
            ChatConnectionState.Connected -> 1.08f
            ChatConnectionState.Reconnecting -> 1.15f
            else -> 1f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (connectionState) {
                    ChatConnectionState.Connected -> 2000
                    ChatConnectionState.Reconnecting -> 600
                    else -> 1000
                },
                easing = EaseInOutSine,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "petScale",
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (connectionState == ChatConnectionState.Disconnected) 0.5f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "petAlpha",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.padding(8.dp),
    ) {
        Text(
            text = emoji,
            fontSize = 32.sp,
            modifier = Modifier
                .scale(scale)
                .alpha(alpha),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private val EaseInOutSine: Easing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

</parameter>
