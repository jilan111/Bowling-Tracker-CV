package com.bowltrack.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bowltrack.R

/**
 * Typography is loaded from TTFs bundled in `res/font/`.
 *
 * BowlTrack is contractually offline, so the typeface stack ships inside
 * the APK rather than being fetched at runtime. Each font face is a static
 * (non-variable) TTF; the Compose font runtime selects the correct face
 * based on the [FontWeight] requested in the style. If a referenced
 * resource is missing the build fails at the R-class generation step,
 * which is exactly what we want — silent fallbacks would let a
 * misconfigured build ship without the brand typography.
 */
private val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

private val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

private val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

/**
 * Display, headline and body styles are bound to Material3 slots so default
 * Compose components pick them up automatically. The mono style for
 * timestamps lives in [BowlTrackTypographyExtras] because Material3 has no
 * dedicated slot for it.
 */
val BowlTrackTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.25.sp,
    ),
)

/**
 * Styles that do not map cleanly onto Material3 slots. Components reach for
 * these directly through [LocalTypographyExtras] in [Theme.kt].
 */
data class BowlTrackTypographyExtras(
    val mono: TextStyle = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),
    val monoMedium: TextStyle = mono.copy(fontWeight = FontWeight.Medium),
    val monoLarge: TextStyle = mono.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
    ),
)
