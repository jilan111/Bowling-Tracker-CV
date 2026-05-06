package com.bowltrack.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.bowltrack.ui.theme.AccentMint
import com.bowltrack.ui.theme.BackgroundPrimary
import com.bowltrack.util.HapticHelper

/**
 * Primary call-to-action button: pill-shaped, mint fill, scales 0.96 while
 * pressed, fires a [HapticFeedbackConstants.CONFIRM] tap on release.
 *
 * The control is hand-built rather than wrapping `material3.Button` because
 * Material3's button enforces its own padding, ripple shape, and elevation
 * model that fight the brand language. Reading the source of the Compose
 * button before writing this was the quickest way to be sure we are not
 * re-creating a worse copy of it: we keep the ripple and interaction-source
 * plumbing, drop the elevation and tint logic.
 *
 * @param text Label rendered inside the pill. Kept as a [String] (rather
 *             than a slot) because every consumer in the app uses plain
 *             text; if we ever need icons we can add an overload.
 * @param onClick Click callback. Haptic feedback is dispatched immediately
 *                before invoking the callback so it lines up with the
 *                visual press release.
 * @param enabled Disabling drops alpha to 0.45 and short-circuits clicks,
 *                matching the pattern used by other interactive surfaces
 *                in the app.
 * @param leadingIcon Optional composable rendered to the left of the
 *                    label. Pass `null` (the default) for a text-only
 *                    button.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // 0.96 on press matches the figma spec; the spring is medium-low to
    // avoid a "rubber-band" rebound that would feel toy-like.
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "PrimaryButtonScale",
    )

    val view = LocalView.current
    val alpha = if (enabled) 1f else 0.45f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .scale(scale)
            .defaultMinSize(minHeight = 52.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(
                // Subtle vertical gradient gives the pill a light source
                // from above without resorting to elevation shadows.
                brush = Brush.verticalGradient(
                    listOf(
                        AccentMint.copy(alpha = alpha),
                        AccentMint.copy(alpha = alpha * 0.85f),
                    )
                )
            )
            .indication(
                interactionSource = interactionSource,
                indication = rememberRipple(color = BackgroundPrimary),
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null, // ripple handled by .indication above
            ) {
                HapticHelper.confirm(view)
                onClick()
            }
            .padding(PaddingValues(horizontal = 28.dp, vertical = 14.dp)),
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = BackgroundPrimary,
        )
    }
}

/**
 * Secondary variant rendered as a 1-px outlined pill rather than a filled
 * one. Same haptics and scale animation as [PrimaryButton] so the two read
 * as a coherent pair when stacked or placed side-by-side.
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "SecondaryButtonScale",
    )
    val view = LocalView.current
    val border = MaterialTheme.colorScheme.outline
    val alpha = if (enabled) 1f else 0.45f

    val pillShape = RoundedCornerShape(percent = 50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .scale(scale)
            .defaultMinSize(minHeight = 52.dp)
            .clip(pillShape)
            .background(Color.Transparent)
            .border(width = 1.dp, color = border, shape = pillShape)
            .indication(
                interactionSource = interactionSource,
                indication = rememberRipple(color = AccentMint),
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
            ) {
                HapticHelper.light(view)
                onClick()
            }
            .padding(PaddingValues(horizontal = 28.dp, vertical = 14.dp)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = AccentMint.copy(alpha = alpha),
        )
    }
}
