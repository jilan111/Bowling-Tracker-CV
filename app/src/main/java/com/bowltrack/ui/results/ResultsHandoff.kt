package com.bowltrack.ui.results

import com.bowltrack.python.VideoAnalysisResult
import java.io.File

/**
 * Process-scoped hand-off slot used to pass the freshly computed
 * [VideoAnalysisResult] from the Analysis screen to the Results
 * screen.
 *
 * Compose Navigation only carries primitive arguments, and routing the
 * result through `SavedStateHandle` would force us to make every
 * nested type Parcelable. The value being handed off lives only as
 * long as the user's "look at the run I just finished" interaction —
 * once the Results screen reads it, the slot is cleared. If the
 * process dies before they reach Results, we lose nothing the user
 * cared about (the source video is still on disk; if persistence is
 * wired up in M11 they can re-open the run from History).
 *
 * Concurrency: writes happen on the Main dispatcher inside Compose, so
 * a plain `@Volatile` reference is enough.
 */
object ResultsHandoff {

    /**
     * One pending result, or `null` when the slot is empty. Held
     * alongside the source [File] because the Results screen needs
     * both to play back the video and to render the path overlay.
     */
    @Volatile
    private var pending: Pair<File, VideoAnalysisResult>? = null

    /** Stores the pair the Analysis screen produced. */
    fun put(video: File, result: VideoAnalysisResult) {
        pending = video to result
    }

    /**
     * Reads and clears the slot. Returns `null` if the user navigated
     * to Results without going through Analysis (e.g. process death,
     * or a deep-link if we ever add one).
     */
    fun take(): Pair<File, VideoAnalysisResult>? {
        val out = pending
        pending = null
        return out
    }
}
