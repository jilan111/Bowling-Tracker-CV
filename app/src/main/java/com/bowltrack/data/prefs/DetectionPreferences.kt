package com.bowltrack.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persisted detection / visualization preferences.
 *
 * Backed by DataStore Preferences. We deliberately keep the schema
 * flat (no JSON columns) so each setting is independently observable
 * — Compose can collect just `pinHsv` without rebuilding the whole
 * preferences object on a slow-motion toggle change.
 *
 * The HSV ranges are persisted as compact CSV strings rather than as
 * separate keys per channel. Every persisted range still round-trips
 * losslessly through the [HsvRanges] serializer.
 */
class DetectionPreferences(private val context: Context) {

    /** Live snapshot used by the AnalysisViewModel + calibration screen. */
    val flow: Flow<DetectionSettings> = context.bowlTrackDataStore.data.map { prefs ->
        val customPin = HsvRanges.parse(prefs[KEY_PIN_HSV])
        val customCar = HsvRanges.parse(prefs[KEY_CAR_HSV])
        DetectionSettings(
            pinRanges = customPin ?: DEFAULT_PIN_RANGES,
            carRanges = customCar ?: DEFAULT_CAR_RANGES,
            customPinRanges = customPin,
            customCarRanges = customCar,
            sensitivity = prefs[KEY_SENSITIVITY] ?: 55,
            useYoloFallback = prefs[KEY_YOLO_FALLBACK] ?: true,
            pathStyle = prefs[KEY_PATH_STYLE]?.let { runCatching { PathStyle.valueOf(it) }.getOrNull() }
                ?: PathStyle.Line,
            slowMotionReplay = prefs[KEY_SLOW_MOTION] ?: false,
        )
    }

    suspend fun setPinRanges(ranges: List<IntArray>) {
        context.bowlTrackDataStore.edit { it[KEY_PIN_HSV] = HsvRanges.encode(ranges) }
    }

    suspend fun setCarRanges(ranges: List<IntArray>) {
        context.bowlTrackDataStore.edit { it[KEY_CAR_HSV] = HsvRanges.encode(ranges) }
    }

    suspend fun setSensitivity(percent: Int) {
        context.bowlTrackDataStore.edit { it[KEY_SENSITIVITY] = percent.coerceIn(0, 100) }
    }

    suspend fun setYoloFallback(enabled: Boolean) {
        context.bowlTrackDataStore.edit { it[KEY_YOLO_FALLBACK] = enabled }
    }

    suspend fun setPathStyle(style: PathStyle) {
        context.bowlTrackDataStore.edit { it[KEY_PATH_STYLE] = style.name }
    }

    suspend fun setSlowMotionReplay(enabled: Boolean) {
        context.bowlTrackDataStore.edit { it[KEY_SLOW_MOTION] = enabled }
    }

    private companion object {
        private val KEY_PIN_HSV = stringPreferencesKey("pin_hsv_ranges")
        private val KEY_CAR_HSV = stringPreferencesKey("car_hsv_ranges")
        private val KEY_SENSITIVITY = intPreferencesKey("sensitivity_percent")
        private val KEY_YOLO_FALLBACK = booleanPreferencesKey("yolo_fallback_enabled")
        private val KEY_PATH_STYLE = stringPreferencesKey("path_style")
        private val KEY_SLOW_MOTION = booleanPreferencesKey("slow_motion_replay")
    }
}

private val Context.bowlTrackDataStore by preferencesDataStore(name = "bowltrack_settings")

/**
 * Plain immutable snapshot the UI binds against. Built by
 * [DetectionPreferences.flow]; never constructed directly elsewhere.
 */
data class DetectionSettings(
    /**
     * Display-friendly pin HSV ranges — falls back to the bundled
     * default so the calibration screen always has something to show.
     * Do *not* pass this straight to the analyzer; use
     * [customPinRanges] there so the new color-agnostic detector is
     * not forced into a colour-mask path the user never asked for.
     */
    val pinRanges: List<IntArray>,
    val carRanges: List<IntArray>,
    /** Non-null only when the user has saved an HSV calibration. */
    val customPinRanges: List<IntArray>? = null,
    val customCarRanges: List<IntArray>? = null,
    val sensitivity: Int,
    val useYoloFallback: Boolean,
    val pathStyle: PathStyle,
    val slowMotionReplay: Boolean,
) {
    /** Map sensitivity (0..100) onto a confidence threshold (1.0..0.05). */
    val confidenceThreshold: Float
        get() = (1f - sensitivity / 100f * 0.95f).coerceIn(0.05f, 1f)
}

/** Visual style for the replay overlay's path. */
enum class PathStyle(val label: String) {
    Line(label = "Line"),
    Dotted(label = "Dotted"),
    FadingTail(label = "Fading"),
}

/**
 * Compact, lossless string codec for a list of HSV ranges. Each range
 * is six ints in the canonical order
 * `[H_lo, S_lo, V_lo, H_hi, S_hi, V_hi]`; ranges are joined by `|`,
 * channels by `,`. Example: `0,0,170,179,60,255`.
 *
 * Kept as a tiny module-private helper rather than reaching for a JSON
 * serializer — DataStore is supposed to stay small and synchronous.
 */
internal object HsvRanges {
    fun encode(ranges: List<IntArray>): String =
        ranges.joinToString("|") { it.joinToString(",") }

    fun parse(raw: String?): List<IntArray>? {
        if (raw.isNullOrBlank()) return null
        return raw.split('|').map { chunk ->
            chunk.split(',').map { it.toInt() }.toIntArray()
        }
    }
}

/** Default pin range, white / cream pins under typical indoor light. */
internal val DEFAULT_PIN_RANGES: List<IntArray> = listOf(
    intArrayOf(0, 0, 170, 179, 60, 255),
)

/** Default car ranges, coral red. Two ranges to handle hue wrap-around. */
internal val DEFAULT_CAR_RANGES: List<IntArray> = listOf(
    intArrayOf(0, 120, 80, 10, 255, 255),
    intArrayOf(170, 120, 80, 179, 255, 255),
)
