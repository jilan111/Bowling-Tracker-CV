package com.bowltrack.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bowltrack.BuildConfig
import com.bowltrack.data.prefs.DetectionPreferences
import com.bowltrack.data.prefs.PathStyle
import com.bowltrack.ui.components.GlassCard
import com.bowltrack.ui.theme.AccentCoral
import com.bowltrack.ui.theme.AccentMint
import com.bowltrack.ui.theme.LocalTypographyExtras
import com.bowltrack.util.HapticHelper
import kotlinx.coroutines.launch

/**
 * Settings destination, fully bound to [DetectionPreferences].
 *
 * Every interactive control persists immediately so the user does not
 * have to hunt for a "Save" button — the brief explicitly favours
 * direct manipulation. Saving on every drag also lets the live
 * calibration screen pick up the new value the next time it composes.
 */
@Composable
fun SettingsScreen(
    onPinColorClick: () -> Unit,
    onCarColorClick: () -> Unit,
    onFrameExtractorDebugClick: () -> Unit = {},
    onDetectionDebugClick: () -> Unit = {},
    onAnalyzeVideoDebugClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { DetectionPreferences(context.applicationContext) }
    val settings by prefs.flow.collectAsState(initial = null)

    val sensitivity = settings?.sensitivity ?: 55
    val useYoloFallback = settings?.useYoloFallback ?: true
    val pathStyle = settings?.pathStyle ?: PathStyle.Line
    val slowMotionReplay = settings?.slowMotionReplay ?: false

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Header()

        Section(title = "Detection") {
            ChevronRow(
                title = "Pin colour calibration",
                value = pinValueLabel(settings?.pinRanges),
                onClick = {
                    HapticHelper.light(view)
                    onPinColorClick()
                },
                accent = MaterialTheme.colorScheme.onSurface,
            )
            HairlineDivider()
            ChevronRow(
                title = "Car colour calibration",
                value = carValueLabel(settings?.carRanges),
                onClick = {
                    HapticHelper.light(view)
                    onCarColorClick()
                },
                accent = AccentCoral,
            )
            HairlineDivider()
            SliderRow(
                title = "Detection sensitivity",
                percent = sensitivity,
                onPercentChange = { value ->
                    scope.launch { prefs.setSensitivity(value) }
                },
            )
            HairlineDivider()
            ToggleRow(
                title = "YOLO fallback",
                description = "Use YOLOv8n when classical CV is uncertain.",
                checked = useYoloFallback,
                onCheckedChange = { enabled ->
                    HapticHelper.light(view)
                    scope.launch { prefs.setYoloFallback(enabled) }
                },
            )
        }

        Section(title = "Visualization") {
            PathStylePicker(
                selected = pathStyle,
                onSelect = { style ->
                    HapticHelper.light(view)
                    scope.launch { prefs.setPathStyle(style) }
                },
            )
            HairlineDivider()
            ToggleRow(
                title = "Slow-motion replay",
                description = "Plays the replay video at half speed.",
                checked = slowMotionReplay,
                onCheckedChange = { enabled ->
                    HapticHelper.light(view)
                    scope.launch { prefs.setSlowMotionReplay(enabled) }
                },
            )
        }

        Section(title = "About") {
            ChevronRow(
                title = "App version",
                value = BuildConfig.VERSION_NAME,
                onClick = { /* no-op until the about sheet lands */ },
                accent = MaterialTheme.colorScheme.onSurface,
            )
            HairlineDivider()
            ChevronRow(
                title = "Frame extractor (debug)",
                value = "Milestone 5 verification",
                onClick = onFrameExtractorDebugClick,
                accent = AccentMint,
            )
            HairlineDivider()
            ChevronRow(
                title = "Detection (debug)",
                value = "Milestone 6 verification",
                onClick = onDetectionDebugClick,
                accent = AccentMint,
            )
            HairlineDivider()
            ChevronRow(
                title = "Analyze video (debug)",
                value = "Milestone 8 end-to-end",
                onClick = onAnalyzeVideoDebugClick,
                accent = AccentMint,
            )
        }
    }
}

@Composable
private fun Header() {
    Column {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "All preferences are stored on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column { content() }
        }
    }
}

@Composable
private fun ChevronRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    accent: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.5f)),
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = value,
                style = LocalTypographyExtras.current.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SliderRow(
    title: String,
    percent: Int,
    onPercentChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$percent%",
                style = LocalTypographyExtras.current.monoMedium,
                color = AccentMint,
            )
        }
        Slider(
            value = percent.toFloat(),
            onValueChange = { onPercentChange(it.toInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = AccentMint,
                activeTrackColor = AccentMint,
                inactiveTrackColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.background,
                checkedTrackColor = AccentMint,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

@Composable
private fun PathStylePicker(
    selected: PathStyle,
    onSelect: (PathStyle) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(
            text = "Path style",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PathStyle.values().forEach { option ->
                PathStyleChip(
                    option = option,
                    isSelected = option == selected,
                    onClick = { onSelect(option) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PathStyleChip(
    option: PathStyle,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pillShape = RoundedCornerShape(percent = 50)
    val containerAlpha = if (isSelected) 0.18f else 0f
    val borderColor = if (isSelected) AccentMint else MaterialTheme.colorScheme.outline
    val labelColor = if (isSelected) AccentMint else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .height(40.dp)
            .clip(pillShape)
            .background(AccentMint.copy(alpha = containerAlpha))
            .border(width = 1.dp, color = borderColor, shape = pillShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = option.label,
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
        )
    }
}

@Composable
private fun HairlineDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
    )
}

private fun pinValueLabel(ranges: List<IntArray>?): String {
    if (ranges.isNullOrEmpty()) return "White / cream (default)"
    val r = ranges.first()
    return "H ${r[0]}–${r[3]} • S ${r[1]}–${r[4]} • V ${r[2]}–${r[5]}"
}

private fun carValueLabel(ranges: List<IntArray>?): String {
    if (ranges.isNullOrEmpty()) return "Coral red (default)"
    val r = ranges.first()
    val wrap = if (ranges.size > 1) " (wrap)" else ""
    return "H ${r[0]}–${r[3]} • S ${r[1]}–${r[4]}$wrap"
}
