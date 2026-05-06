package com.bowltrack.data.repository

import com.bowltrack.python.CarSources
import com.bowltrack.python.FallenPinRecord
import com.bowltrack.python.TimingBreakdown
import com.bowltrack.python.VideoAnalysisResult

/**
 * Reverse-maps a [SavedRunBundle] back into the [VideoAnalysisResult]
 * shape the UI layer already knows how to render.
 *
 * Keeps the Results screen agnostic of where its input came from: the
 * live screen receives a fresh result from the Python bridge, and the
 * History screen receives one of these. Doing the conversion here
 * means we never duplicate "how to render a result" logic.
 */
fun SavedRunBundle.toAnalysisResult(): VideoAnalysisResult {
    val totalLkPlusOthers = session.classicalMs + session.yoloMs
    return VideoAnalysisResult(
        videoPath = session.videoPath,
        totalPins = session.totalPins,
        fallenPins = falls.map { row ->
            FallenPinRecord(
                order = row.order,
                pinId = row.pinId,
                frameIndex = row.frameIndex,
                timestampSeconds = row.timestampSeconds,
            )
        },
        carPath = path.map { it.x to it.y },
        frameCount = 0, // unknown at restore time; UI does not require it
        analysisWidth = session.analysisWidth,
        analysisHeight = session.analysisHeight,
        yoloStatus = session.yoloStatus,
        yoloInvocations = session.yoloInvocations,
        carSources = CarSources(
            color = session.carColor,
            yolo = session.carYolo,
            lk = session.carLk,
            missing = session.carMissing,
        ),
        timingTotalMs = TimingBreakdown(
            classicalMs = session.classicalMs,
            yoloMs = session.yoloMs,
            // Lucas-Kanade timing isn't persisted (it's marginal in
            // the totals), so the surfaced "total" sums the two
            // tiers we do persist.
            totalMs = totalLkPlusOthers,
        ),
        error = null,
    )
}
