package com.example.indoorpositiondetectionsystem

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.roundToInt

class PositionTrackerTest {

    private val engine = GridLocalizationEngine(ModelReferenceSource, k = 3)
    private fun newTracker() = PositionTracker(engine)

    /** Advances 25 s per scan, below STALE_RESET_MS. One Clock per test. */
    private class Clock {
        var t = 0L
        fun next(): Long { t += 25_000L; return t }
    }

    private fun ref(gridId: String): RssiVector =
        RssiVector(ModelReferenceSource.reference(gridId)!!.mapValues { it.value.roundToInt() })

    private fun noisy(gridId: String, rnd: Random, sd: Double): RssiVector =
        RssiVector(ModelReferenceSource.reference(gridId)!!.mapValues {
            (it.value + rnd.nextGaussian() * sd).roundToInt().coerceIn(-99, -30)
        })

    private val allMissing = RssiVector(GridConfig.LAB_NAMES.associateWith { -100 })

    private fun settle(tr: PositionTracker, gridId: String, c: Clock, n: Int = 6): TrackerResult {
        var last: TrackerResult? = null
        repeat(n) { last = tr.update(ref(gridId), c.next()) }
        return last!!
    }

    @Test
    fun firstUpdateAcceptsCandidateImmediately() {
        val tr = newTracker(); val c = Clock()
        val r = tr.update(ref("L1-G5"), c.next())
        assertNotNull(r.gridId)
        assertTrue(r.changed)
        assertEquals("LAB 1", r.lab)
    }

    @Test
    fun singleOutlierDoesNotChangeGrid() {
        val tr = newTracker(); val c = Clock()
        settle(tr, "L2-G5", c, 5)
        val base = ref("L2-G5")
        val outlier = RssiVector(base.values.mapValues { (it.value - 20).coerceAtLeast(-99) })
        tr.update(outlier, c.next())
        assertEquals("L2-G5", tr.currentGridId)
        tr.update(base, c.next())
        assertEquals("L2-G5", tr.currentGridId)
    }

    @Test
    fun missingApHeldThenDropped() {
        val tr = newTracker(); val c = Clock()
        settle(tr, "L1-G5", c, 3)
        val others = ref("L1-G5").values.toMutableMap()
        others["LAB 1"] = -100
        val missing = RssiVector(others)
        val r1 = tr.update(missing, c.next())
        assertNotEquals(-100, r1.smoothedRssi["LAB 1"])
        val r2 = tr.update(missing, c.next())
        assertNotEquals(-100, r2.smoothedRssi["LAB 1"])
        val r3 = tr.update(missing, c.next())
        assertEquals(-100, r3.smoothedRssi["LAB 1"])
    }

    @Test
    fun missingApReinitializesDirectly() {
        val tr = newTracker(); val c = Clock()
        settle(tr, "L1-G5", c, 3)
        val miss = ref("L1-G5").values.toMutableMap()
        miss["LAB 1"] = -100
        tr.update(RssiVector(miss), c.next())
        val back = ref("L1-G5").values.toMutableMap()
        back["LAB 1"] = -60
        val r = tr.update(RssiVector(back), c.next())
        assertEquals(-60, r.smoothedRssi["LAB 1"])
    }

    @Test
    fun trackerIsMuchMoreStableThanRawEngine() {
        val rnd = Random(42)
        val tr = newTracker(); val c = Clock()
        var rawChanges = 0
        var trackerChanges = 0
        var prevRaw: String? = null
        repeat(100) { i ->
            val v = noisy("L2-G1", rnd, 2.5)
            val raw = engine.localize(v)?.gridId
            if (i > 0 && raw != prevRaw) rawChanges++
            prevRaw = raw
            val r = tr.update(v, c.next())
            if (i > 0 && r.changed) trackerChanges++
        }
        println("Stability: rawChanges=$rawChanges trackerChanges=$trackerChanges")
        assertTrue("rawChanges must be > 0 (raw=$rawChanges tracker=$trackerChanges)", rawChanges > 0)
        assertTrue(
            "tracker should change <= 1/3 as often as raw (raw=$rawChanges tracker=$trackerChanges)",
            trackerChanges * 3 <= rawChanges
        )
    }

    @Test
    fun adjacentMoveNeedsTwoConsistentScans() {
        val tr = newTracker(); val c = Clock()
        settle(tr, "L1-G1", c, 6)
        var firstRawFlipChecked = false
        var switchedAt = -1
        for (i in 1..8) {
            val r = tr.update(ref("L1-G2"), c.next())
            if (!firstRawFlipChecked && r.rawGridId == "L1-G2") {
                assertEquals("must not switch on first raw flip", "L1-G1", r.gridId)
                firstRawFlipChecked = true
            }
            if (r.gridId == "L1-G2" && switchedAt < 0) switchedAt = i
        }
        assertTrue("raw engine never reported L1-G2", firstRawFlipChecked)
        assertTrue("tracker never reached L1-G2 within 8 feeds", switchedAt in 1..8)
    }

    @Test
    fun sameLabJumpNeedsConfirmation() {
        val tr = newTracker(); val c = Clock()
        settle(tr, "L3-G1", c, 6)
        var firstRawFlipChecked = false
        var reached = false
        for (i in 1..10) {
            val r = tr.update(ref("L3-G9"), c.next())
            if (!firstRawFlipChecked && r.rawGridId == "L3-G9") {
                assertNotEquals("must not switch on first raw flip", "L3-G9", r.gridId)
                firstRawFlipChecked = true
            }
            if (r.gridId == "L3-G9") reached = true
        }
        assertTrue("raw engine never reported L3-G9", firstRawFlipChecked)
        assertTrue("tracker never reached L3-G9 within 10 feeds", reached)
    }

    @Test
    fun briefCrossRoomSpikeDoesNotSwitch() {
        val tr = newTracker(); val c = Clock()
        settle(tr, "L1-G5", c, 6)
        tr.update(ref("L4-G5"), c.next())
        assertEquals("LAB 1", GridConfig.parseGridId(tr.currentGridId!!)!!.lab)
        repeat(3) {
            tr.update(ref("L1-G5"), c.next())
            assertEquals("LAB 1", GridConfig.parseGridId(tr.currentGridId!!)!!.lab)
        }
    }

    @Test
    fun sustainedCrossRoomMoveSwitches() {
        val tr = newTracker(); val c = Clock()
        settle(tr, "L1-G5", c, 6)
        var last: TrackerResult? = null
        repeat(8) { last = tr.update(ref("L4-G5"), c.next()) }
        assertEquals("LAB 4", last!!.lab)
    }

    @Test
    fun allMissingHoldsThenNull() {
        // CORRECTED SPEC: AP values are held for 2 missing scans (EWMA hold), so the engine
        // still sees data on scans 1-2. Null-hold starts at scan 3 and lasts 3 scans (3,4,5).
        // The grid becomes null on scan 6.
        val tr = newTracker(); val c = Clock()
        settle(tr, "L2-G5", c, 6)
        repeat(2) {
            val r = tr.update(allMissing, c.next())
            assertEquals("L2-G5", r.gridId)
        }
        var prevConf = Float.MAX_VALUE
        repeat(3) { i ->
            val r = tr.update(allMissing, c.next())
            assertEquals("scan ${i + 3} should still hold the grid", "L2-G5", r.gridId)
            if (i > 0) assertTrue("confidence must decrease (was $prevConf, now ${r.confidence})", r.confidence < prevConf)
            prevConf = r.confidence
        }
        val r6 = tr.update(allMissing, c.next())
        assertNull(r6.gridId)
    }

    @Test
    fun staleGapResets() {
        val tr = newTracker(); val c = Clock()
        settle(tr, "L1-G5", c, 6)
        val r = tr.update(ref("L3-G5"), c.t + 91_000L)
        assertEquals("L3-G5", r.gridId)
    }

    @Test
    fun resetClearsState() {
        val tr = newTracker(); val c = Clock()
        settle(tr, "L1-G5", c, 6)
        assertNotNull(tr.currentGridId)
        tr.reset()
        assertNull(tr.currentGridId)
    }

    @Test
    fun deterministicForSameSequence() {
        fun run(): List<TrackerResult> {
            val rnd = Random(7)
            val tr = newTracker(); val c = Clock()
            return List(20) { tr.update(noisy("L2-G5", rnd, 3.0), c.next()) }
        }
        assertEquals(run(), run())
    }
}
