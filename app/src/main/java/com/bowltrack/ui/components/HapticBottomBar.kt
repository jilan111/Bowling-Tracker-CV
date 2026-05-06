package com.bowltrack.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.bowltrack.ui.theme.AccentMint
import com.bowltrack.ui.theme.BackgroundSecondary
import com.bowltrack.ui.theme.BorderSubtle
import com.bowltrack.util.HapticHelper

/** Tabs surfaced in [HapticBottomBar]. Order in the enum drives layout order. */
enum class BowlTrackTab(
    val title: String,
    val outlined: ImageVector,
    val filled: ImageVector,
) {
    Home(title = "Home", outlined = Icons.Outlined.Home, filled = Icons.Filled.Home),
    History(title = "History", outlined = Icons.Outlined.History, filled = Icons.Filled.History),
    Settings(title = "Settings", outlined = Icons.Outlined.Settings, filled = Icons.Filled.Settings),
}

/**
 * Custom bottom navigation bar.
 *
 * The bar renders three [BowlTrackTab]s with morphing icons (outlined ->
 * filled on selection) and an indicator pill that slides under the active
 * tab. A light haptic fires whenever the user changes tab; an
 * already-selected tap does not fire haptics, matching iOS-style "tap
 * does nothing" behaviour to avoid feedback noise.
 *
 * Built from a [Row] of three equally-weighted columns so the tab spacing
 * is correct regardless of label length. The selection indicator
 * animates by interpolating its target X offset rather than picking up a
 * shared element transition; this keeps the implementation portable to
 * older API levels that lack the SharedTransitionLayout API.
 */
@Composable
fun HapticBottomBar(
    selected: BowlTrackTab,
    onSelect: (BowlTrackTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val tabs = remember { BowlTrackTab.values().toList() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(
                brush = Brush.verticalGradient(
                    listOf(
                        BackgroundSecondary.copy(alpha = 0.0f),
                        BackgroundSecondary,
                    )
                )
            ),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.surface)
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                NavTab(
                    tab = tab,
                    isSelected = tab == selected,
                    onClick = {
                        if (tab != selected) {
                            HapticHelper.light(view)
                            onSelect(tab)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NavTab(
    tab: BowlTrackTab,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Selection state drives a 0->1 animation that fades the pill in,
    // bumps the icon size up by 4 %, and brightens the label.
    val selection by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "NavTabSelection",
    )

    val pillAlpha = 0.18f * selection
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(
        alpha = 0.6f + 0.4f * selection,
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(AccentMint.copy(alpha = pillAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isSelected) tab.filled else tab.outlined,
                contentDescription = tab.title,
                tint = if (isSelected) AccentMint else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size((20 + 2 * selection).dp)
                    .padding(end = 0.dp),
            )
            // The label fades in only when selected, keeping the bar
            // visually quiet at rest.
            if (selection > 0.05f) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = tab.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor,
                )
            }
        }
    }
}

/**
 * Decorative top-edge hairline used by screens that pin a [HapticBottomBar]
 * at the bottom — provides a subtle separation between content and chrome
 * without resorting to a shadow.
 */
@Composable
fun BottomBarTopHairline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(BorderSubtle),
    )
}

