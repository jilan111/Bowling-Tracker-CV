package com.bowltrack.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Pure helpers for inspecting runtime permission state.
 *
 * The recorder and picker screens both need to know whether the relevant
 * permission is currently granted before they decide what UI to show
 * (request prompt vs. live preview). Centralising the checks here keeps
 * the SDK-version branching out of the screen code.
 */
object PermissionHelpers {

    /** True when `android.permission.CAMERA` is currently granted. */
    fun hasCamera(context: Context): Boolean = isGranted(context, Manifest.permission.CAMERA)

    /** True when `android.permission.RECORD_AUDIO` is currently granted. */
    fun hasMicrophone(context: Context): Boolean =
        isGranted(context, Manifest.permission.RECORD_AUDIO)

    /**
     * True when the runtime permission required to read a gallery video
     * is granted on the current OS version. On API 33+ the relevant
     * permission is `READ_MEDIA_VIDEO`; on older releases it is the
     * legacy `READ_EXTERNAL_STORAGE`.
     *
     * Note: when the user picks a video through `PickVisualMedia` no
     * runtime permission is required at all — the picker hands us a
     * scoped URI. This helper exists for the rare path where we want to
     * iterate gallery items ourselves (currently unused).
     */
    fun hasGalleryRead(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return isGranted(context, permission)
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
}
