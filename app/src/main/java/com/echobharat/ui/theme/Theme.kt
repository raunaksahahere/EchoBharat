package com.echobharat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = AccentSaffron,
    onPrimary = SurfaceCard,
    primaryContainer = BubbleMine,
    onPrimaryContainer = TextPrimary,
    secondary = AccentEmerald,
    onSecondary = SurfaceCard,
    secondaryContainer = SurfaceVariantBg,
    onSecondaryContainer = TextPrimary,
    tertiary = AccentChakra,
    background = SurfaceBg,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantBg,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle,
    error = AccentAlert,
    onError = SurfaceCard
)

@Composable
fun EchoBharatTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}
