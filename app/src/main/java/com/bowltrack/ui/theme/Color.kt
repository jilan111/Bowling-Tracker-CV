package com.bowltrack.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Semantic colour tokens for the "Precision Sport Lab" theme.
 *
 * Naming follows a `Layer` + role convention rather than raw colour names so
 * that downstream code reads as `BackgroundPrimary` instead of `Navy900`.
 * If the palette is ever rebalanced, the call sites do not need to change.
 *
 * The palette is intentionally dark-mode-first; the light theme variant in
 * [Theme] derives from these tokens with adjusted luminance rather than
 * defining a parallel set, to keep the brand identity coherent.
 */

/** Deep navy-black used as the root canvas. */
val BackgroundPrimary = Color(0xFF0A0E1A)

/** Surface for cards laid on top of the canvas. */
val BackgroundSecondary = Color(0xFF141925)

/** Highest surface tier for stacked / elevated cards. */
val BackgroundTertiary = Color(0xFF1E2536)

/** Primary brand accent: paths, primary CTAs, score readouts. */
val AccentMint = Color(0xFF00D9A3)

/** Destructive / fallen-pin marker. */
val AccentCoral = Color(0xFFFF4757)

/** Warning / in-progress state. */
val AccentAmber = Color(0xFFFFB547)

/**
 * Cool electric blue used for the YOLO debug overlay. Picked to read
 * clearly against both the mint primary and the navy background while
 * staying inside the brand's "instrument-panel" register.
 */
val AccentBlue = Color(0xFF3FA9FF)

val TextPrimary = Color(0xFFF5F7FA)
val TextSecondary = Color(0xFF8B95A7)
val TextTertiary = Color(0xFF5A6478)

/** 1 px hairline used to separate adjacent surfaces of the same tier. */
val BorderSubtle = Color(0xFF252D40)
