package com.bowltrack.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Dark theme colour scheme. The app is dark-mode-first; the light scheme
 * exists only so that users who explicitly enable light mode in system
 * settings still get a coherent surface, not a mismatch.
 */
private val BowlTrackDarkScheme = darkColorScheme(
    primary = AccentMint,
    onPrimary = BackgroundPrimary,
    primaryContainer = BackgroundTertiary,
    onPrimaryContainer = AccentMint,
    secondary = AccentCoral,
    onSecondary = TextPrimary,
    tertiary = AccentAmber,
    onTertiary = BackgroundPrimary,
    background = BackgroundPrimary,
    onBackground = TextPrimary,
    surface = BackgroundSecondary,
    onSurface = TextPrimary,
    surfaceVariant = BackgroundTertiary,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle,
    error = AccentCoral,
    onError = TextPrimary,
)

/**
 * Light scheme is a desaturated mirror of the dark palette so the brand
 * still reads "instrument panel" rather than reverting to default Material.
 * Surface tones are explicitly chosen rather than left to dynamic colour to
 * preserve the mint accent across OEM skins.
 */
private val BowlTrackLightScheme = lightColorScheme(
    primary = AccentMint,
    onPrimary = BackgroundPrimary,
    primaryContainer = Color(0xFFE6FFF7),
    onPrimaryContainer = Color(0xFF003B2A),
    secondary = AccentCoral,
    onSecondary = TextPrimary,
    tertiary = AccentAmber,
    onTertiary = BackgroundPrimary,
    background = Color(0xFFF5F7FA),
    onBackground = BackgroundPrimary,
    surface = Color(0xFFFFFFFF),
    onSurface = BackgroundPrimary,
    surfaceVariant = Color(0xFFE5E9F2),
    onSurfaceVariant = TextTertiary,
    outline = Color(0xFFC4CBDA),
    error = AccentCoral,
    onError = TextPrimary,
)

/**
 * Composition-local for typography styles that do not have a Material3 slot
 * (currently: monospaced numerics). Resolve via [BowlTrackTheme.typographyExtras].
 */
val LocalTypographyExtras = staticCompositionLocalOf { BowlTrackTypographyExtras() }

/**
 * Composition-local exposing whether RenderEffect blur is supported on the
 * current device. [com.bowltrack.ui.components.GlassCard] reads this to
 * decide between a real blur and a layered translucent fallback.
 */
val LocalSupportsRenderEffectBlur = compositionLocalOf {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}

/**
 * Wraps the entire app. Dynamic colour is *intentionally disabled* so the
 * carefully tuned mint/coral identity survives across OEM skins; the brief
 * forbids relying on Material3 defaults.
 */
@Composable
fun BowlTrackTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) BowlTrackDarkScheme else BowlTrackLightScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            // Light icons on dark backgrounds and vice versa, computed from
            // the actual luminance rather than the boolean theme flag so the
            // contrast stays correct if the palette changes.
            val isLightStatus = colorScheme.background.luminance() > 0.5f
            controller.isAppearanceLightStatusBars = isLightStatus
            controller.isAppearanceLightNavigationBars = isLightStatus
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = BowlTrackTypography,
        shapes = BowlTrackShapes,
        content = {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalTypographyExtras provides BowlTrackTypographyExtras(),
                content = content,
            )
        }
    )
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)
