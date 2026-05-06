package com.bowltrack.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner radii are deliberately larger than Material3 defaults to give the
 * surfaces the soft, instrument-panel feel called for by the brief. The
 * `extraLarge` slot is reserved for hero / scoreboard surfaces.
 */
val BowlTrackShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
