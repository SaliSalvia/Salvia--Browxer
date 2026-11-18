package com.salvia.salviabrowxer.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salvia.salviabrowxer.core.FloatingSnap
import com.salvia.salviabrowxer.ui.theme.SalviaBorder
import com.salvia.salviabrowxer.ui.theme.SalviaCyan
import com.salvia.salviabrowxer.ui.theme.SalviaDeepCosmic
import com.salvia.salviabrowxer.ui.theme.SalviaElevated
import com.salvia.salviabrowxer.ui.theme.SalviaGold
import com.salvia.salviabrowxer.ui.theme.SalviaIris
import com.salvia.salviabrowxer.ui.theme.SalviaIrisDeep
import com.salvia.salviabrowxer.ui.theme.SalviaMonoFontFamily
import com.salvia.salviabrowxer.ui.theme.SalviaPearl
import com.salvia.salviabrowxer.ui.theme.SalviaPink
import com.salvia.salviabrowxer.ui.theme.SalviaSilverMid
import kotlin.math.roundToInt

@Composable
fun FloatingDownloadButton(
    containerWidthPx: Float,
    containerHeightPx: Float,
    buttonSizeDp: Int,
    savedX: Float,
    savedY: Float,
    hasMainMedia: Boolean,
    mediaCount: Int,
    alwaysVisible: Boolean,
    contentDescriptionText: String,
    onPositionSaved: (Float, Float) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!alwaysVisible && !hasMainMedia && mediaCount == 0) return
    if (containerWidthPx <= 0f || containerHeightPx <= 0f) return

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val sizePx = with(density) { buttonSizeDp.dp.toPx() }
    val padPx = with(density) { 14.dp.toPx() }

    val initialPos = remember(containerWidthPx, containerHeightPx, savedX, savedY, sizePx) {
        val rawX = if (savedX >= 0f) savedX else (containerWidthPx - sizePx - padPx)
        val rawY = if (savedY >= 0f) savedY else (containerHeightPx - sizePx - padPx * 2f)
        FloatingSnap.snapFloatingPosition(rawX, rawY, containerWidthPx, containerHeightPx, sizePx, padPx)
    }

    var posX by remember { mutableFloatStateOf(initialPos.first) }
    var posY by remember { mutableFloatStateOf(initialPos.second) }

    LaunchedEffect(initialPos) {
        posX = initialPos.first
        posY = initialPos.second
    }

    val animX by animateFloatAsState(targetValue = posX, label = "fab_x")
    val animY by animateFloatAsState(targetValue = posY, label = "fab_y")

    val highlighted = hasMainMedia || mediaCount > 0
    val infinite = rememberInfiniteTransition(label = "comet")
    val spin by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "comet_spin"
    )

    Box(
        modifier = modifier
            .offset { IntOffset(animX.roundToInt(), animY.roundToInt()) }
            .size(buttonSizeDp.dp)
            .alpha(if (highlighted) 1f else 0.72f)
            .semantics { contentDescription = contentDescriptionText }
            .testTag("floating_download_button")
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    }
                )
            }
            .pointerInput(containerWidthPx, containerHeightPx, sizePx) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        posX = (posX + dragAmount.x).coerceIn(padPx, (containerWidthPx - sizePx - padPx).coerceAtLeast(padPx))
                        posY = (posY + dragAmount.y).coerceIn(padPx, (containerHeightPx - sizePx - padPx).coerceAtLeast(padPx))
                    },
                    onDragEnd = {
                        val (sx, sy) = FloatingSnap.snapFloatingPosition(
                            posX, posY, containerWidthPx, containerHeightPx, sizePx, padPx
                        )
                        posX = sx
                        posY = sy
                        onPositionSaved(sx, sy)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (highlighted) {
            Canvas(modifier = Modifier.size(buttonSizeDp.dp + 12.dp)) {
                rotate(spin) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(SalviaIris, SalviaCyan, SalviaGold, SalviaPink, Color.Transparent)
                        ),
                        startAngle = 0f,
                        sweepAngle = 250f,
                        useCenter = false,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .size(buttonSizeDp.dp)
                .shadow(elevation = if (highlighted) 14.dp else 4.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(
                    if (highlighted) {
                        Brush.linearGradient(listOf(SalviaIrisDeep, SalviaIris, SalviaCyan))
                    } else {
                        Brush.linearGradient(listOf(SalviaElevated, SalviaElevated))
                    }
                )
                .border(
                    width = 1.5.dp,
                    color = if (highlighted) Color.White.copy(alpha = 0.28f) else SalviaBorder,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = contentDescriptionText,
                tint = if (highlighted) SalviaPearl else SalviaSilverMid,
                modifier = Modifier.size((buttonSizeDp * 0.44f).dp)
            )
        }

        if (mediaCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(1.dp)
                    .clip(CircleShape)
                    .background(SalviaGold)
                    .border(1.dp, SalviaDeepCosmic, CircleShape)
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    text = mediaCount.toString(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = SalviaMonoFontFamily,
                    color = SalviaDeepCosmic
                )
            }
        }
    }
}
