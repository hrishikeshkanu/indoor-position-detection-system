package com.example.indoorpositiondetectionsystem

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** Live RSSI readings keyed "LAB 1".."LAB 4"; -100 means not seen. */
data class RssiVector(val values: Map<String, Int>)

/**
 * Output of the localization engine.
 * @param gridId      Best-guess grid cell, e.g. "L2-G5".
 * @param lab         Lab name, e.g. "LAB 2".
 * @param confidence  Normalized confidence 0..1; higher is more certain.
 * @param usedFallback  True when the room-confident filter was widened to all 36 cells.
 */
data class LocalizationResult(
    val gridId: String,
    val lab: String,
    val confidence: Float,
    val usedFallback: Boolean
)

/**
 * Returns expected (reference) RSSI per lab for the given grid cell,
 * or null if no reference is available for that cell.
 */
fun interface ReferenceSource {
    fun reference(gridId: String): Map<String, Double>?
}

// ---------------------------------------------------------------------------
// Reference sources
// ---------------------------------------------------------------------------

/**
 * Reference source that computes expected RSSI from the Log-Distance Path Loss
 * model with an added wall-loss penalty when the AP is in a different lab.
 *
 * All constants are named and tunable here; they are NOT copied from other files.
 * The formula stays consistent with [RouterConfig.calculateDistance]:
 *   distance = 10 ^ ((TX_POWER - rssi) / (10 * PATH_LOSS_N))
 * so the inverse gives:
 *   rssi = TX_POWER - 10 * PATH_LOSS_N * log10(max(d, 1.0))
 */
object ModelReferenceSource : ReferenceSource {

    // ---- Tunable constants ------------------------------------------------
    /** Reference transmit power (dBm) at 1 m; must match RouterConfig. */
    private const val TX_POWER = -40.0

    /** Path-loss exponent (indoor); must match RouterConfig. */
    private const val PATH_LOSS_N = 3.0

    /** Extra attenuation (dB) when the AP's lab differs from the grid's lab. */
    private const val WALL_LOSS_DB = 10.0

    /**
     * Real-world width of the map in metres.
     * Used to convert fraction-based positions to metres.
     */
    private const val MAP_WIDTH_METERS = 20.0

    // ---- AP logical positions as fractions of the map square --------------
    private const val LAB1_FX = 0.25
    private const val LAB1_FY = 0.25
    private const val LAB2_FX = 0.75
    private const val LAB2_FY = 0.25
    private const val LAB3_FX = 0.25
    private const val LAB3_FY = 0.75
    private const val LAB4_FX = 0.75
    private const val LAB4_FY = 0.75

    private val apPositions = mapOf(
        "LAB 1" to Pair(LAB1_FX, LAB1_FY),
        "LAB 2" to Pair(LAB2_FX, LAB2_FY),
        "LAB 3" to Pair(LAB3_FX, LAB3_FY),
        "LAB 4" to Pair(LAB4_FX, LAB4_FY)
    )

    override fun reference(gridId: String): Map<String, Double>? {
        val center = GridConfig.cellCenterFractions(gridId) ?: return null
        val cell = GridConfig.parseGridId(gridId) ?: return null
        val result = mutableMapOf<String, Double>()
        for ((lab, pos) in apPositions) {
            val dx = (center.first - pos.first) * MAP_WIDTH_METERS
            val dy = (center.second - pos.second) * MAP_WIDTH_METERS
            val distMeters = sqrt(dx * dx + dy * dy)
            val wallLoss = if (lab == cell.lab) 0.0 else WALL_LOSS_DB
            val rssi = TX_POWER - 10.0 * PATH_LOSS_N * log10(max(distMeters, 1.0)) - wallLoss
            result[lab] = rssi
        }
        return result
    }
}

/**
 * Reference source backed by a pre-loaded fingerprint map.
 * Data format: gridId -> { "LAB 1" -> avgRssi, ... }.
 * Returns null for grids not present in [data]; no storage/loading happens here.
 */
class FingerprintReferenceSource(
    private val data: Map<String, Map<String, Double>>
) : ReferenceSource {
    override fun reference(gridId: String): Map<String, Double>? = data[gridId]
}

/**
 * Tries [primary] first; falls back to [fallback] when primary returns null.
 */
class CompositeReferenceSource(
    private val primary: ReferenceSource,
    private val fallback: ReferenceSource
) : ReferenceSource {
    override fun reference(gridId: String): Map<String, Double>? =
        primary.reference(gridId) ?: fallback.reference(gridId)
}

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------

/**
 * Weighted k-NN grid localization engine.
 *
 * The engine is pure Kotlin with no Android imports. It is NOT wired into any
 * Activity in Stage 2; Activities continue to use MapView.estimatePosition.
 *
 * ### Tunable constants
 * | Constant | Value | Purpose |
 * |---|---|---|
 * | MISSING_RSSI | -95 | Substitute for -100 (not seen) in distance math |
 * | DEFAULT_AP_WEIGHT | 1.0 | Default per-AP weight in the Euclidean distance |
 * | STRONGEST_AP_WEIGHT | 1.5 | Weight boost for the strongest live AP |
 * | ROOM_CONFIDENT_RSSI | -75 | If the strongest AP is below this, widen candidates to all 36 cells |
 */
class GridLocalizationEngine(
    private val source: ReferenceSource,
    private val k: Int = 3
) {

    companion object {
        /** Substituted for -100 (not seen) in weighted distance math. */
        private const val MISSING_RSSI = -95.0

        /** Default per-AP Euclidean weight. */
        private const val DEFAULT_AP_WEIGHT = 1.0

        /** Weight boost applied to the strongest live AP. */
        private const val STRONGEST_AP_WEIGHT = 1.5

        /**
         * If the strongest live AP is weaker than this threshold the engine
         * widens the candidate set from the preferred room's 9 cells to all 36
         * (sets [LocalizationResult.usedFallback] = true).
         */
        private const val ROOM_CONFIDENT_RSSI = -75

        private val LAB_NAMES = GridConfig.LAB_NAMES
    }

    /**
     * Localizes [rssi] to the best matching grid cell.
     *
     * Returns null when:
     * - all four RSSI values are -100 (no signal), or
     * - the reference source provides no reference for any candidate grid.
     *
     * Never throws; input is validated defensively.
     */
    fun localize(rssi: RssiVector): LocalizationResult? {
        val values = rssi.values

        // Reject if all signals are absent.
        if (LAB_NAMES.all { (values[it] ?: -100) == -100 }) return null

        // Determine the strongest live AP and its lab.
        val strongestEntry = LAB_NAMES
            .mapNotNull { lab -> values[lab]?.takeIf { it != -100 }?.let { lab to it } }
            .maxByOrNull { it.second }

        val preferredLab = strongestEntry?.first
        val strongestRssi = strongestEntry?.second ?: Int.MIN_VALUE

        // Room pre-selection: widen to all 36 when the signal is weak.
        val usedFallback = preferredLab == null || strongestRssi < ROOM_CONFIDENT_RSSI
        val candidateIds: List<String> = if (!usedFallback && preferredLab != null) {
            GridConfig.gridIdsForLab(preferredLab)
        } else {
            GridConfig.ALL_GRID_IDS
        }

        // Build the per-AP weight map.
        val weights = LAB_NAMES.associateWith { lab ->
            if (lab == preferredLab) STRONGEST_AP_WEIGHT else DEFAULT_AP_WEIGHT
        }

        // Compute weighted Euclidean distance to each candidate reference vector.
        data class Candidate(val gridId: String, val lab: String, val dist: Double)

        val candidates = candidateIds.mapNotNull { gridId ->
            val ref = source.reference(gridId) ?: return@mapNotNull null
            val cell = GridConfig.parseGridId(gridId) ?: return@mapNotNull null
            val dist = weightedEuclidean(values, ref, weights)
            Candidate(gridId, cell.lab, dist)
        }

        if (candidates.isEmpty()) return null

        // k-NN: take k best candidates (smallest distance).
        val topK = candidates.sortedBy { it.dist }.take(k)

        // Weighted vote: each candidate casts a vote proportional to 1/distance.
        // Ties broken by smallest distance (already sorted).
        val votes = mutableMapOf<String, Double>()
        for (candidate in topK) {
            val weight = if (candidate.dist > 0.0) 1.0 / candidate.dist else Double.MAX_VALUE / k
            votes[candidate.gridId] = (votes[candidate.gridId] ?: 0.0) + weight
        }

        // The winner is the candidate with the highest summed weight.
        val winner = topK.maxWithOrNull(
            compareByDescending<Candidate> { votes[it.gridId] ?: 0.0 }
                .thenBy { it.dist }
        ) ?: return null

        // Confidence: gap between best and second-best distances.
        val best = topK[0].dist
        val secondBest = topK.getOrNull(1)?.dist
        val gapConfidence = if (secondBest != null && secondBest > 0.0) {
            (1.0 - best / secondBest).coerceIn(0.0, 1.0)
        } else 1.0

        // Reduce confidence when fewer than 2 APs are seen.
        val seenCount = LAB_NAMES.count { (values[it] ?: -100) != -100 }
        val seenFactor = when {
            seenCount >= 2 -> 1.0
            seenCount == 1 -> 0.6
            else -> 0.3
        }
        val confidence = (gapConfidence * seenFactor).toFloat().coerceIn(0f, 1f)

        return LocalizationResult(
            gridId = winner.gridId,
            lab = winner.lab,
            confidence = confidence,
            usedFallback = usedFallback
        )
    }

    // Weighted Euclidean distance: sqrt( sum( w_i * (live_i - ref_i)^2 ) )
    private fun weightedEuclidean(
        live: Map<String, Int>,
        ref: Map<String, Double>,
        weights: Map<String, Double>
    ): Double {
        var sum = 0.0
        for (lab in LAB_NAMES) {
            val liveVal = (live[lab] ?: -100).let { if (it == -100) MISSING_RSSI else it.toDouble() }
            val refVal = ref[lab] ?: MISSING_RSSI
            val w = weights[lab] ?: DEFAULT_AP_WEIGHT
            val diff = liveVal - refVal
            sum += w * diff * diff
        }
        return sqrt(sum)
    }
}
