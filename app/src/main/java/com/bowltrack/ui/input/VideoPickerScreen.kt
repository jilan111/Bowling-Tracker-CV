package com.bowltrack.ui.input

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bowltrack.ui.components.GlassCard
import com.bowltrack.ui.components.PrimaryButton
import com.bowltrack.ui.components.SecondaryButton
import com.bowltrack.ui.theme.AccentCoral
import com.bowltrack.ui.theme.LocalTypographyExtras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Hosts the system's `PickVisualMedia` chooser, then copies the chosen
 * video into app-private storage so the analysis pipeline can read it
 * without a content-resolver round-trip per frame.
 *
 * Uses the photo-picker contract introduced in Android 13 (with a
 * compat back-port supplied by the Activity library) — that means no
 * runtime storage permission is needed; the picker hands us a scoped
 * URI which the framework keeps valid for the duration of the calling
 * task.
 *
 * @param onClose User cancelled the screen entirely.
 * @param onPicked Successful import. Receives the on-disk copy of the
 *                 chosen video.
 */
@Composable
fun VideoPickerScreen(
    onClose: () -> Unit,
    onPicked: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickError by remember { mutableStateOf<String?>(null) }
    var isCopying by remember { mutableStateOf(false) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) {
            // User cancelled the system picker; we keep the screen up
            // so they can retry without going back to Home.
            return@rememberLauncherForActivityResult
        }
        pickedUri = uri
        scope.launch {
            isCopying = true
            pickError = null
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    copyToInternal(context.contentResolver, uri, context.filesDir)
                }
            }
            isCopying = false
            outcome
                .onSuccess { onPicked(it) }
                .onFailure { error ->
                    pickError = error.message ?: "Could not import video"
                }
        }
    }

    LaunchedEffect(Unit) {
        // Open the picker as soon as the screen lands so the user does
        // not have to take an extra tap. If they cancel we fall back to
        // the manual "open picker" CTA below.
        pickerLauncher.launch(
            PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.VideoOnly,
            )
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp)
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Import a video",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Pick a video from your gallery. We'll copy it to private storage so the analysis pipeline can read it without keeping the gallery app open.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when {
                        isCopying -> "Copying video..."
                        pickedUri != null -> "Imported."
                        else -> "Waiting for selection."
                    },
                    style = LocalTypographyExtras.current.mono,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (pickError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = pickError.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AccentCoral,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        PrimaryButton(
            text = if (pickedUri == null) "Open picker" else "Pick another",
            onClick = {
                pickerLauncher.launch(
                    PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.VideoOnly,
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        SecondaryButton(
            text = "Cancel",
            onClick = onClose,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

/**
 * Streams the picked video into app-private storage.
 *
 * Reading the URI directly during analysis would force the pipeline to
 * keep an open `ContentResolver` handle for the lifetime of the
 * analysis, which fights the "scoped storage" model — the gallery app
 * may be killed and the URI revoked at any time. Copying eagerly is the
 * simpler contract.
 */
private fun copyToInternal(
    resolver: ContentResolver,
    source: Uri,
    filesDir: File,
): File {
    val destination = newRecordingFile(filesDirParent = filesDir)
    resolver.openInputStream(source).use { input ->
        requireNotNull(input) { "Empty stream from $source" }
        FileOutputStream(destination).use { output ->
            input.copyTo(output)
        }
    }
    return destination
}

/**
 * Mirrors [com.bowltrack.video.newRecordingFile] but lets us pass an
 * arbitrary parent directory for the copy. Kept private to this file
 * because it is an implementation detail of the picker flow.
 */
private fun newRecordingFile(filesDirParent: File): File {
    val directory = File(filesDirParent, "imports").apply { mkdirs() }
    val timestamp = System.currentTimeMillis()
    return File(directory, "BowlTrack_import_$timestamp.mp4")
}
