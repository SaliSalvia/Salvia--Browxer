package com.salvia.salviabrowxer.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.salvia.salviabrowxer.R
import com.salvia.salviabrowxer.ui.theme.SalviaCyan
import com.salvia.salviabrowxer.ui.theme.SalviaGold
import com.salvia.salviabrowxer.ui.theme.SalviaIris
import com.salvia.salviabrowxer.ui.theme.SalviaPink

@Composable
fun OrbitalBrandMark(
    size: Dp = 76.dp,
    showRing: Boolean = true,
    animateRing: Boolean = false,
    contentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "orbital_ring")
    val angle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbital_angle"
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        if (showRing) {
            Canvas(modifier = Modifier.size(size)) {
                val strokeWidth = (size.toPx() * 0.055f).coerceAtLeast(2.dp.toPx())
                rotate(if (animateRing) angle else 0f) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(
                                SalviaIris,
                                SalviaCyan,
                                SalviaGold,
                                SalviaPink,
                                SalviaIris
                            )
                        ),
                        startAngle = 0f,
                        sweepAngle = 300f,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }
        }
        Image(
            painter = painterResource(id = R.drawable.salvia_logo),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(if (showRing) size * 0.78f else size)
                .padding(if (showRing) 2.dp else 0.dp)
                .clip(CircleShape)
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.14f),
                    shape = CircleShape
                )
        )
    }
}
