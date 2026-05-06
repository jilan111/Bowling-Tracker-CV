package com.bowltrack.util

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Thin wrapper around [View.performHapticFeedback] that picks the best
 * available constant for the current API level.
 *
 * The platform added [HapticFeedbackConstants.CONFIRM] in API 30 and
 * [HapticFeedbackConstants.GESTURE_END] in API 30. On older devices we fall
 * back to [HapticFeedbackConstants.VIRTUAL_KEY], which exists since API 5
 * and produces a comparable short tap. Routing every haptic call through
 * this helper keeps the call sites in components readable and ensures the
 * minSdk-26 contract is honoured.
 */
object HapticHelper {

    /** Strong, decisive feedback for primary CTAs (e.g. PrimaryButton). */
    fun confirm(view: View) {
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        view.performHapticFeedback(code)
    }

    /** Light tap for navigation tab changes and toggles. */
    fun light(view: View) {
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.GESTURE_END
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(code)
    }
}
