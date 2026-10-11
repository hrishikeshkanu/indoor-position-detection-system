package com.example.indoorpositiondetectionsystem

import kotlin.math.roundToInt

/**
 * Output of one tracker update.
 * @param gridId        Stabilized grid cell (after smoothing + hysteresis), or null if unknown.
 * @param lab           Lab of [gridId], or null.
 * @param confidence    0..1; scaled down while holding a grid with no signal.
 * @param rawGridId     What the engine said for this scan's smoothed vector (before hysteresis), or null.
 * @param smoothedRssi  EWMA-smoothed RSSI per lab, -100 = not seen.
 * @param changed       True only when [gridId] differs from the previous output.
 */
data class TrackerResult(
    val gridId: String?,
    val lab: String?,
    val confidence: Float,
    val rawGridId: String?,
    val smoothedRssi: Map<String, Int>,
    val changed: Boolean
)

/**
 * Stabilizes [GridLocalizationEngine] output over time.
 * Pure Kotlin: no Android imports, and time is passed in (never read from the clock).
 * One instance per user/device.
 */
class PositionTracker(private val engine: GridLocalizationEngine) {

    companion object {
        /** EWMA weight of the newest reading (0..1). Higher = faster, noisier. */
        const val EWMA_ALPHA = 0.4

        /** Consecutive -100 scans an AP keeps its last smoothed value before becoming -100. */
        const val MISSING_HOLD_SCANS = 2

        /** Consecutive wins needed to move to an adjacent cell in the same lab. */
        const val ADJACENT_CONFIRM_SCANS = 2

        /** Consecutive wins needed to jump to a non-adjacent cell in the same lab. */
        const val JUMP_CONFIRM_SCANS = 3

        /** Confidence at or above which a same-lab jump needs only [HIGH_CONFIDENCE_JUMP_SCANS]. */
        const val HIGH_CONFIDENCE = 0.6f
        const val HIGH_CONFIDENCE_JUMP_SCANS = 2

        /** The new lab's smoothed RSSI must beat the current lab's by this many dB to switch rooms. */
        const val ROOM_SWITCH_MARGIN_DB = 4

        /** Consecutive wins needed to switch to a different lab. */
        const val ROOM_CONFIRM_SCANS = 2

        /** Consecutive null engine results the current grid is held before becoming null. */
        const val NULL_HOLD_SCANS = 3

        /** Confidence multiplier applied on each held (no-signal) scan. */
        const val NULL_CONFIDENCE_DECAY = 0.7f

        /** If this much time passes between updates, all history is discarded. */
        const val STALE_RESET_MS = 90_000L

        private const val NOT_SEEN = -100
    }

    private val smoothed = HashMap<String, Double>()
    private val missingCount = HashMap<String, Int>()
    private var currentGrid: String? = null
    private var currentConfidence = 0f
    private var pendingGrid: String? = null
    private var pendingCount = 0
    private var nullCount = 0
    private var lastUpdateMs: Long? = null

    /** The current stabilized grid, or null. */
    val currentGridId: String? get() = currentGrid

    /** Clears all history. */
    fun reset() {
        smoothed.clear()
        missingCount.clear()
        currentGrid = null
        currentConfidence = 0f
        pendingGrid = null
        pendingCount = 0
        nullCount = 0
        lastUpdateMs = null
    }

    fun update(rssi: RssiVector, nowMs: Long): TrackerResult {
        val last = lastUpdateMs
        if (last != null && nowMs - last > STALE_RESET_MS) reset()
        lastUpdateMs = nowMs

        val previousGrid = currentGrid
        smoothInput(rssi)
        val smoothedInt = smoothedAsInts()
        val result = engine.localize(RssiVector(smoothedInt))

        if (result == null) {
            nullCount++
            pendingGrid = null
            pendingCount = 0
            if (nullCount > NULL_HOLD_SCANS) {
                currentGrid = null
                currentConfidence = 0f
            } else {
                currentConfidence *= NULL_CONFIDENCE_DECAY
            }
        } else {
            nullCount = 0
            applyHysteresis(result, smoothedInt)
        }

        val grid = currentGrid
        return TrackerResult(
            gridId = grid,
            lab = grid?.let { GridConfig.parseGridId(it)?.lab },
            confidence = currentConfidence.coerceIn(0f, 1f),
            rawGridId = result?.gridId,
            smoothedRssi = smoothedInt,
            changed = grid != previousGrid
        )
    }

    // ---- EWMA smoothing -------------------------------------------------

    private fun smoothInput(rssi: RssiVector) {
        for (lab in GridConfig.LAB_NAMES) {
            val raw = rssi.values[lab] ?: NOT_SEEN
            if (raw == NOT_SEEN) {
                if (!smoothed.containsKey(lab)) continue
                val count = (missingCount[lab] ?: 0) + 1
                missingCount[lab] = count
                if (count > MISSING_HOLD_SCANS) smoothed.remove(lab)
            } else {
                val previous = smoothed[lab]
                val wasMissing = (missingCount[lab] ?: 0) > 0
                smoothed[lab] = if (previous == null || wasMissing) {
                    raw.toDouble()
                } else {
                    EWMA_ALPHA * raw + (1.0 - EWMA_ALPHA) * previous
                }
                missingCount[lab] = 0
            }
        }
    }

    private fun smoothedAsInts(): Map<String, Int> =
        GridConfig.LAB_NAMES.associateWith { lab -> smoothed[lab]?.roundToInt() ?: NOT_SEEN }

    // ---- Hysteresis -----------------------------------------------------

    private fun applyHysteresis(result: LocalizationResult, smoothedInt: Map<String, Int>) {
        val candidate = result.gridId
        val current = currentGrid

        if (current == null) {
            accept(candidate, result.confidence)
            return
        }
        if (candidate == current) {
            pendingGrid = null
            pendingCount = 0
            currentConfidence = result.confidence
            return
        }

        if (candidate == pendingGrid) pendingCount++ else {
            pendingGrid = candidate
            pendingCount = 1
        }

        val currentCell = GridConfig.parseGridId(current)
        val candidateCell = GridConfig.parseGridId(candidate)
        var needed = 1
        var allowed = true

        if (currentCell != null && candidateCell != null) {
            if (currentCell.lab == candidateCell.lab) {
                needed = when {
                    candidate in GridConfig.neighbors(current) -> ADJACENT_CONFIRM_SCANS
                    result.confidence >= HIGH_CONFIDENCE -> HIGH_CONFIDENCE_JUMP_SCANS
                    else -> JUMP_CONFIRM_SCANS
                }
            } else {
                needed = ROOM_CONFIRM_SCANS
                val currentRssi = smoothedInt[currentCell.lab] ?: NOT_SEEN
                val candidateRssi = smoothedInt[candidateCell.lab] ?: NOT_SEEN
                // Margin check is skipped when the current lab's AP is not seen.
                if (currentRssi != NOT_SEEN && candidateRssi - currentRssi < ROOM_SWITCH_MARGIN_DB) {
                    allowed = false
                }
            }
        }

        if (allowed && pendingCount >= needed) accept(candidate, result.confidence)
    }

    private fun accept(gridId: String, confidence: Float) {
        currentGrid = gridId
        currentConfidence = confidence
        pendingGrid = null
        pendingCount = 0
    }
}
