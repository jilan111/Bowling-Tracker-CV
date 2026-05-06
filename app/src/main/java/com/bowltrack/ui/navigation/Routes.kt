package com.bowltrack.ui.navigation

/**
 * Route names used by the [androidx.navigation.compose.NavHost] graph.
 *
 * Sealed-class routing was considered but a string-keyed object was kept
 * for simplicity: every screen the navigation graph needs to reach today
 * is parameterless. The single parameterised route ([SessionDetail])
 * builds its own pattern + helper, mirroring the official Compose
 * Navigation samples.
 */
object Routes {
    const val Home = "home"
    const val History = "history"
    const val Settings = "settings"

    /** Top-level video-input destinations introduced in Milestone 4. */
    const val Recorder = "recorder"
    const val Picker = "picker"

    /**
     * Live analysis screen — Milestone 9. The video file path is
     * passed as a URL-encoded argument because Compose Navigation's
     * argument plumbing only accepts primitives.
     */
    const val AnalysisPattern = "analysis/{videoPath}"
    const val ArgVideoPath = "videoPath"

    fun analysis(videoPath: String): String =
        "analysis/${java.net.URLEncoder.encode(videoPath, "UTF-8")}"

    /** Results screen — Milestone 10. The result and video are passed
     *  via [com.bowltrack.ui.results.ResultsHandoff] rather than
     *  through the route so we don't have to make
     *  [com.bowltrack.python.VideoAnalysisResult] Parcelable. */
    const val Results = "results"

    /**
     * HSV calibration screen — Milestone 12. Single argument is the
     * target name (`pin` or `car`).
     */
    const val CalibrationPattern = "settings/calibration/{target}"
    const val ArgCalibrationTarget = "target"

    fun calibration(target: String): String = "settings/calibration/$target"

    /**
     * Debug-only destination from Milestone 5 that drives
     * [com.bowltrack.video.VideoFrameExtractor] over the most recent
     * recording or import. Removed once the analysis screen lands in
     * Milestone 9.
     */
    const val FrameExtractorDebug = "debug/frame-extractor"

    /**
     * Debug-only destination from Milestone 6 that runs the Python
     * classical detector on a single decoded frame and overlays the
     * results. Also retired in Milestone 9.
     */
    const val DetectionDebug = "debug/detection"

    /**
     * Debug-only destination from Milestone 8 that drives the full
     * hybrid pipeline end-to-end via `pipeline.analyze_video`. Removed
     * once the polished Analysis screen lands in Milestone 9.
     */
    const val AnalyzeVideoDebug = "debug/analyze-video"

    /** Pattern with placeholder; consumed by `composable(...)`. */
    const val SessionDetailPattern = "session/{sessionId}"

    /** Concrete route for navigating to a specific session. */
    fun sessionDetail(sessionId: String): String = "session/$sessionId"

    /** Argument key shared by [SessionDetailPattern] and lookups. */
    const val ArgSessionId = "sessionId"
}
