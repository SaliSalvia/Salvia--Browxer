package com.salvia.salviabrowxer.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.salvia.salviabrowxer.ui.theme.SalviaBlack
import com.salvia.salviabrowxer.ui.theme.SalviaCyan
import com.salvia.salviabrowxer.ui.theme.SalviaGold
import com.salvia.salviabrowxer.ui.theme.SalviaIris
import kotlinx.coroutines.delay

@Composable
fun AppSplashOverlay(
    onFinished: () -> Unit
) {
    LaunchedEffect(Unit) {
        delay(1250)
        onFinished()
    }

    val infinite = rememberInfiniteTransition(label = "splash")
    val scale by infinite.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "splash_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SalviaBlack)
            .testTag("splash_overlay")
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onFinished
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(280.dp)
                .scale(scale)
                .background(
                    brush = Brush.radialGradient(
                        listOf(
                            SalviaIris.copy(alpha = 0.28f),
                            SalviaCyan.copy(alpha = 0.16f),
                            SalviaGold.copy(alpha = 0.08f),
                            Color.Transparent
                        )
                    )
                )
        )
        OrbitalBrandMark(
            size = 156.dp,
            showRing = false,
            contentDescription = "SalviaBrowxer"
        )
    }
}
