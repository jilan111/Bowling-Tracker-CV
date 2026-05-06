package com.bowltrack.ui.input

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bowltrack.ui.components.PrimaryButton
import com.bowltrack.ui.theme.AccentCoral
import com.bowltrack.ui.theme.AccentMint
import com.bowltrack.ui.theme.LocalTypographyExtras
import com.bowltrack.util.HapticHelper
import com.bowltrack.util.PermissionHelpers
import com.bowltrack.video.VideoCaptureController
import com.bowltrack.video.VideoCaptureResult
import com.bowltrack.video.newRecordingFile
import kotlinx.coroutines.launch
import java.io.File

/**
 * Full-screen CameraX recorder.
 *
 * The screen owns a single [VideoCaptureController] for the duration of
 * its composition; CameraX's lifecycle integration handles teardown
 * automatically when the underlying [LifecycleOwner] is destroyed.
 *
 * @param onClose Tapped on the close icon — caller decides whether that
 *                pops the back stack or routes home.
 * @param onCaptureComplete Fired when the user finishes a recording.
 *                          The caller receives the resulting [File] and
 *                          duration so it can hand the video to the
 *                          analysis pipeline (Milestone 8) or stash it.
 */
@Composable
fun CameraRecorderScreen(
    onClose: () -> Unit,
    onCaptureComplete: (File, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val controller = remember { VideoCaptureController(context) }
    val state by controller.state.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(PermissionHelpers.hasCamera(context))
    }
    var hasMicPermission by remember {
        mutableStateOf(PermissionHelpers.hasMicrophone(context))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        hasCameraPermission = granted[Manifest.permission.CAMERA] ?: hasCameraPermission
        hasMicPermission = granted[Manifest.permission.RECORD_AUDIO] ?: hasMicPermission
    }

    LaunchedEffect(Unit) {
        // Always ask for both at once — asking one-at-a-time would
        // produce two system dialogs in sequence, which feels jarring.
        if (!hasCameraPermission || !hasMicPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                )
            )
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(hasCameraPermission, previewView) {
        if (hasCameraPermission) {
            // Suppression: hasCameraPermission is exactly the gate the
            // controller's @RequiresPermission documents.
            @Suppress("MissingPermission")
            controller.bindToLifecycle(lifecycleOwner, previewView)
        }
    }

    DisposableEffect(Unit) {
        // Stop any in-flight recording if the screen leaves composition
        // — otherwise CameraX would keep writing to a file the user
        // cannot reach from anywhere in the app.
        onDispose {
            controller.stopRecording()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PermissionRationale(
                onGrantClick = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO,
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
            )
        }

        // ---- Top chrome (close + elapsed timer) ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(
                icon = Icons.Outlined.Close,
                contentDescription = "Close",
                onClick = onClose,
            )
            Spacer(Modifier.size(12.dp))
            ElapsedReadout(
                isRecording = state.isRecording,
                elapsedNanos = state.elapsedNanos,
            )
            Spacer(Modifier.weight(1f))
            CircleIconButton(
                icon = Icons.Outlined.Cameraswitch,
                contentDescription = "Flip camera",
                onClick = {
                    if (hasCameraPermission && !state.isRecording) {
                        scope.launch {
                            @Suppress("MissingPermission")
                            controller.flipCamera(lifecycleOwner, previewView)
                        }
                    }
                },
            )
        }

        // ---- Bottom record button ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .systemBarsPadding()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.lastError != null) {
                Text(
                    text = state.lastError?.message ?: "Camera error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentCoral,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            RecordButton(
                isRecording = state.isRecording,
                enabled = hasCameraPermission && state.isInitialised,
                onClick = {
                    if (!hasCameraPermission) return@RecordButton
                    HapticHelper.confirm(view)
                    if (state.isRecording) {
                        controller.stopRecording()
                    } else {
                        scope.launch {
                            val destination = newRecordingFile(context)
                            try {
                                @Suppress("MissingPermission")
                                val result: VideoCaptureResult =
                                    controller.startRecording(destination)
                                onCaptureComplete(result.file, result.durationMillis)
                            } catch (error: Exception) {
                                // Errors are surfaced through the
                                // controller's state flow already; this
                                // catch is just to keep the coroutine
                                // from cancelling its scope.
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ElapsedReadout(isRecording: Boolean, elapsedNanos: Long) {
    val seconds = elapsedNanos / 1_000_000_000L
    val mm = seconds / 60L
    val ss = seconds % 60L
    val readout = "%02d:%02d".format(mm, ss)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (isRecording) {
            // Pulsing coral dot makes the recording state legible from
            // across the room.
            val transition = rememberInfiniteTransition(label = "RecDotPulse")
            val pulseAlpha by transition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "RecDotPulseAlpha",
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(AccentCoral.copy(alpha = pulseAlpha)),
            )
            Spacer(Modifier.size(6.dp))
        }
        Text(
            text = readout,
            style = LocalTypographyExtras.current.monoMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun CircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
            .clickable {
                HapticHelper.light(view)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun RecordButton(
    isRecording: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "RecButtonPulse")
    val ringScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "RecButtonPulseScale",
    )

    val innerScale by animateFloatAsState(
        targetValue = if (isRecording) 0.55f else 1f,
        label = "RecButtonInnerScale",
    )
    val cornerPercent by animateFloatAsState(
        targetValue = if (isRecording) 22f else 50f,
        label = "RecButtonCornerPercent",
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
        if (isRecording) {
            // Pulsing ring only animates while recording.
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .scale(ringScale)
                    .alpha(0.5f)
                    .clip(CircleShape)
                    .border(2.dp, AccentMint, CircleShape),
            )
        }
        // Outer hairline ring
        Box(
            modifier = Modifier
                .size(78.dp)
                .clip(CircleShape)
                .border(width = 3.dp, color = AccentMint, shape = CircleShape),
        )
        // Inner fill — circle when idle, rounded square while recording
        Box(
            modifier = Modifier
                .size(60.dp)
                .scale(innerScale)
                .clip(RoundedCornerShape(percent = cornerPercent.toInt()))
                .background(if (enabled) AccentMint else AccentMint.copy(alpha = 0.4f))
                .clickable(enabled = enabled, onClick = onClick),
        )
    }
}

@Composable
private fun PermissionRationale(
    onGrantClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Camera permission required",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "BowlTrack records video on-device and never uploads it. Grant camera and microphone access to record a new run.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton(text = "Grant access", onClick = onGrantClick)
    }
}
