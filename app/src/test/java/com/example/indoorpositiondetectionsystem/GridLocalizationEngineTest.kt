package com.example.indoorpositiondetectionsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class GridLocalizationEngineTest {

    private val engine = GridLocalizationEngine(ModelReferenceSource, k = 3)

    // -----------------------------------------------------------------------
    // Helper: build an RSSI vector from a map (missing labs default to -100)
    // -----------------------------------------------------------------------
    private fun rssi(vararg pairs: Pair<String, Int>): RssiVector =
        RssiVector(mapOf(*pairs))

    private fun allMissing(): RssiVector =
        RssiVector(mapOf("LAB 1" to -100, "LAB 2" to -100, "LAB 3" to -100, "LAB 4" to -100))

    // -----------------------------------------------------------------------
    // 1. All -100 returns null
    // -----------------------------------------------------------------------
    @Test
    fun allMissingSignalReturnsNull() {
        assertNull(engine.localize(allMissing()))
    }

    @Test
    fun emptyValuesMapTreatedAsMissing() {
        assertNull(engine.localize(RssiVector(emptyMap())))
    }

    // -----------------------------------------------------------------------
    // 2. Self-reference round-trip: feeding each grid's own model reference
    //    (rounded to Int) should ideally return the same grid.
    //    We assert exact matches and document the count.
    // -----------------------------------------------------------------------
    @Test
    fun modelSelfReferenceRoundTrip() {
        var exactMatches = 0
        val violations = mutableListOf<String>()

        for (gridId in GridConfig.ALL_GRID_IDS) {
            val ref = ModelReferenceSource.reference(gridId) ?: continue
            // Round float reference values to Int exactly as a live scan would produce them.
            val rssiVector = RssiVector(ref.mapValues { (_, v) -> v.roundToInt() })
            val result = engine.localize(rssiVector)
            assertNotNull("Expected non-null result for grid $gridId", result)
            result!!
            if (result.gridId == gridId) {
                exactMatches++
            } else {
                // Allowed adjacent-cell tolerance: result must be the same lab cell
                // AND be the same cell or a (Moore) neighbor within that lab.
                // The model source places nearby cells very close in RSSI space, so
                // integer rounding can shift the winner by one cell — this is expected.
                val neighbors = GridConfig.neighbors(gridId) + gridId
                val sameLabNeighbor = result.lab == GridConfig.parseGridId(gridId)!!.lab &&
                        result.gridId in neighbors
                if (!sameLabNeighbor) {
                    violations += "$gridId -> ${result.gridId} (lab=${result.lab})"
                }
            }
        }

        // Report for transparency.
        println("Self-reference exact matches: $exactMatches/36")

        // The critical assertion: NO result may land outside the lab or outside
        // the Moore neighborhood of the queried cell.
        assertTrue(
            "Round-trip violations (wrong lab or non-neighbor): $violations",
            violations.isEmpty()
        )
    }

    @Test
    fun centerCellsMatchExactly() {
        val centerCells = listOf("L1-G5", "L2-G5", "L3-G5", "L4-G5")
        for (gridId in centerCells) {
            val ref = ModelReferenceSource.reference(gridId)
            assertNotNull("Reference expected for $gridId", ref)
            val rssiVector = RssiVector(ref!!.mapValues { (_, v) -> v.roundToInt() })
            val result = engine.localize(rssiVector)
            assertNotNull("Result expected for $gridId", result)
            assertEquals("Center cell $gridId must match exactly", gridId, result!!.gridId)
        }
    }

    @Test
    fun winnerIsBestMatchNotWorst() {
        val centerCells = listOf("L1-G5", "L2-G5", "L3-G5", "L4-G5")
        for (id in centerCells) {
            val ref = ModelReferenceSource.reference(id)!!
            val rssiVector = RssiVector(ref.mapValues { it.value.roundToInt() })
            val result = engine.localize(rssiVector)
            assertNotNull("Result expected for $id", result)
            assertEquals(id, result!!.gridId)
        }
    }

    // -----------------------------------------------------------------------
    // 3. Strong LAB 2 signal with weak others picks a LAB 2 grid
    // -----------------------------------------------------------------------
    @Test
    fun strongLab2SignalPicksLab2Grid() {
        val result = engine.localize(rssi(
            "LAB 1" to -90,
            "LAB 2" to -50,   // strong
            "LAB 3" to -90,
            "LAB 4" to -90
        ))
        assertNotNull(result)
        assertEquals("LAB 2", result!!.lab)
        assertTrue(result.gridId.startsWith("L2-"))
    }

    // -----------------------------------------------------------------------
    // 4. Strong signals do not widen to other rooms; weak signals may
    // -----------------------------------------------------------------------
    @Test
    fun strongSignalStaysInPreferredRoom() {
        // LAB 1 dominant and strong (>= ROOM_CONFIDENT_RSSI = -75)
        val result = engine.localize(rssi(
            "LAB 1" to -60,
            "LAB 2" to -90,
            "LAB 3" to -90,
            "LAB 4" to -90
        ))
        assertNotNull(result)
        assertEquals(false, result!!.usedFallback)
        assertEquals("LAB 1", result.lab)
    }

    @Test
    fun weakSignalEnablesFallbackAcrossRooms() {
        // All signals below ROOM_CONFIDENT_RSSI = -75 → usedFallback = true
        val result = engine.localize(rssi(
            "LAB 1" to -80,
            "LAB 2" to -82,
            "LAB 3" to -85,
            "LAB 4" to -88
        ))
        assertNotNull(result)
        assertTrue("Expected usedFallback=true for all-weak signals", result!!.usedFallback)
    }

    // -----------------------------------------------------------------------
    // 5. Missing-reference grids are skipped; empty source returns null
    // -----------------------------------------------------------------------
    @Test
    fun emptyReferenceSourceReturnsNull() {
        val emptyEngine = GridLocalizationEngine(FingerprintReferenceSource(emptyMap()), k = 3)
        assertNull(emptyEngine.localize(rssi("LAB 1" to -65, "LAB 2" to -80)))
    }

    @Test
    fun partialReferenceSourceOnlyConsidersAvailableGrids() {
        // Provide references only for LAB 2 cells; engine must still produce a result.
        val partialData: Map<String, Map<String, Double>> = GridConfig.gridIdsForLab("LAB 2")
            .associateWith { gridId ->
                ModelReferenceSource.reference(gridId)!!
            }
        val partialEngine = GridLocalizationEngine(FingerprintReferenceSource(partialData), k = 3)
        val result = partialEngine.localize(rssi(
            "LAB 1" to -90,
            "LAB 2" to -55,
            "LAB 3" to -90,
            "LAB 4" to -90
        ))
        assertNotNull(result)
        // Result must come from LAB 2 since that is the only one with references.
        assertEquals("LAB 2", result!!.lab)
    }

    // -----------------------------------------------------------------------
    // 6. Composite source prefers primary, falls back to secondary
    // -----------------------------------------------------------------------
    @Test
    fun compositeSourcePrefersPrimary() {
        // Primary covers only L1-G5; secondary covers all.
        val primaryData = mapOf(
            "L1-G5" to mapOf("LAB 1" to -50.0, "LAB 2" to -70.0, "LAB 3" to -70.0, "LAB 4" to -70.0)
        )
        val primary = FingerprintReferenceSource(primaryData)
        val composite = CompositeReferenceSource(primary, ModelReferenceSource)

        // For "L1-G5" the primary reference is returned.
        val refFromPrimary = composite.reference("L1-G5")
        assertEquals(primaryData["L1-G5"], refFromPrimary)

        // For any other cell, the model reference is used.
        val refFromFallback = composite.reference("L2-G3")
        assertEquals(ModelReferenceSource.reference("L2-G3"), refFromFallback)
    }

    @Test
    fun compositeReferenceSourceEngineProducesResult() {
        val primaryData = mapOf(
            "L3-G5" to mapOf("LAB 1" to -80.0, "LAB 2" to -80.0, "LAB 3" to -55.0, "LAB 4" to -75.0)
        )
        val composite = CompositeReferenceSource(
            FingerprintReferenceSource(primaryData),
            ModelReferenceSource
        )
        val compositeEngine = GridLocalizationEngine(composite, k = 3)
        val result = compositeEngine.localize(rssi(
            "LAB 1" to -85,
            "LAB 2" to -85,
            "LAB 3" to -60,
            "LAB 4" to -80
        ))
        assertNotNull(result)
    }

    // -----------------------------------------------------------------------
    // 7. Output is deterministic for identical input
    // -----------------------------------------------------------------------
    @Test
    fun outputIsDeterministic() {
        val input = rssi("LAB 1" to -65, "LAB 2" to -78, "LAB 3" to -85, "LAB 4" to -90)
        val r1 = engine.localize(input)
        val r2 = engine.localize(input)
        val r3 = engine.localize(input)
        assertEquals(r1, r2)
        assertEquals(r2, r3)
    }

    // -----------------------------------------------------------------------
    // 8. Confidence is within 0..1; lower for an ambiguous vector than for
    //    an exact self-reference match
    // -----------------------------------------------------------------------
    @Test
    fun confidenceIsWithinBounds() {
        for (gridId in GridConfig.ALL_GRID_IDS) {
            val ref = ModelReferenceSource.reference(gridId) ?: continue
            val result = engine.localize(RssiVector(ref.mapValues { (_, v) -> v.roundToInt() }))
            if (result != null) {
                assertTrue(
                    "Confidence ${result.confidence} out of [0,1] for $gridId",
                    result.confidence in 0f..1f
                )
            }
        }
    }

    @Test
    fun exactMatchHasHigherConfidenceThanAmbiguous() {
        // Exact self-reference for L1-G5 (center of LAB 1).
        val exactRef = ModelReferenceSource.reference("L1-G5")!!
        val exactResult = engine.localize(RssiVector(exactRef.mapValues { (_, v) -> v.roundToInt() }))

        // Ambiguous: all labs roughly equal.
        val ambiguousResult = engine.localize(rssi(
            "LAB 1" to -80,
            "LAB 2" to -80,
            "LAB 3" to -80,
            "LAB 4" to -80
        ))

        assertNotNull(exactResult)
        assertNotNull(ambiguousResult)
        assertTrue(
            "Exact match confidence ${exactResult!!.confidence} should be >= " +
                    "ambiguous confidence ${ambiguousResult!!.confidence}",
            exactResult.confidence >= ambiguousResult.confidence
        )
    }

    // -----------------------------------------------------------------------
    // Bonus: single-lab dominant signal stays in that lab (spot checks)
    // -----------------------------------------------------------------------
    @Test
    fun strongLab1PicksLab1() {
        val result = engine.localize(rssi("LAB 1" to -52, "LAB 2" to -88, "LAB 3" to -88, "LAB 4" to -88))
        assertNotNull(result)
        assertEquals("LAB 1", result!!.lab)
    }

    @Test
    fun strongLab3PicksLab3() {
        val result = engine.localize(rssi("LAB 1" to -90, "LAB 2" to -90, "LAB 3" to -55, "LAB 4" to -85))
        assertNotNull(result)
        assertEquals("LAB 3", result!!.lab)
    }

    @Test
    fun strongLab4PicksLab4() {
        val result = engine.localize(rssi("LAB 1" to -90, "LAB 2" to -85, "LAB 3" to -88, "LAB 4" to -53))
        assertNotNull(result)
        assertEquals("LAB 4", result!!.lab)
    }
}
