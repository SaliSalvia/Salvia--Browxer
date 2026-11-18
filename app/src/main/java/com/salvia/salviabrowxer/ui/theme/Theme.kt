package com.salvia.salviabrowxer.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private val SalviaDarkColorScheme = darkColorScheme(
    primary = SalviaIris,
    onPrimary = SalviaPearl,
    primaryContainer = SalviaIrisContainer,
    onPrimaryContainer = SalviaIrisLight,
    secondary = SalviaCyan,
    onSecondary = SalviaDeepCosmic,
    secondaryContainer = SalviaCyanContainer,
    onSecondaryContainer = SalviaCyanLight,
    tertiary = SalviaGold,
    onTertiary = SalviaDeepCosmic,
    background = SalviaBlack,
    onBackground = SalviaPearl,
    surface = SalviaSurface,
    onSurface = SalviaPearl,
    surfaceVariant = SalviaElevated,
    onSurfaceVariant = SalviaSilverMid,
    outline = SalviaBorder,
    outlineVariant = SalviaBorderLight,
    error = SalviaError,
    onError = SalviaDeepCosmic
)

private val SalviaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun SalviaTheme(
    isRtl: Boolean = false,
    content: @Composable () -> Unit
) {
    val direction = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        MaterialTheme(
            colorScheme = SalviaDarkColorScheme,
            typography = salviaTypography(isRtl),
            shapes = SalviaShapes,
            content = content
        )
    }
}
