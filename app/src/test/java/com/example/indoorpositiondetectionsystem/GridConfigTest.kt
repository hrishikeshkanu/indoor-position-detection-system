package com.example.indoorpositiondetectionsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GridConfigTest {
    private val epsilon = 0.00001f

    @Test
    fun allGridIdsAreUniqueAndInExpectedOrder() {
        assertEquals(36, GridConfig.ALL_GRID_IDS.size)
        assertEquals(36, GridConfig.ALL_GRID_IDS.toSet().size)
        assertEquals("L1-G1", GridConfig.ALL_GRID_IDS.first())
        assertEquals("L4-G9", GridConfig.ALL_GRID_IDS.last())
    }

    @Test
    fun gridIdsForLabAreRowMajorAndUnknownLabIsEmpty() {
        assertEquals(
            (1..9).map { "L2-G$it" },
            GridConfig.gridIdsForLab("LAB 2")
        )
        assertTrue(GridConfig.gridIdsForLab("LAB 9").isEmpty())
    }

    @Test
    fun gridIdValidatesLabAndCellCoordinates() {
        assertEquals("L1-G1", GridConfig.gridId("LAB 1", 0, 0))
        assertEquals("L1-G9", GridConfig.gridId("LAB 1", 2, 2))
        assertNull(GridConfig.gridId("LAB 1", 3, 0))
        assertNull(GridConfig.gridId("LAB 9", 0, 0))
    }

    @Test
    fun parseGridIdRoundTripsAndRejectsMalformedIds() {
        for (id in GridConfig.ALL_GRID_IDS) {
            val cell = GridConfig.parseGridId(id)
            assertEquals(id, cell?.gridId)
            assertEquals(id, GridConfig.gridId(cell!!.lab, cell.row, cell.col))
        }

        for (invalidId in listOf("", "L5-G1", "L1-G0", "L1-G10", "garbage")) {
            assertNull(GridConfig.parseGridId(invalidId))
        }
    }

    @Test
    fun everyCellCenterMapsBackToItsGridId() {
        for (id in GridConfig.ALL_GRID_IDS) {
            val center = GridConfig.cellCenterFractions(id)!!
            assertEquals(id, GridConfig.gridAt(center.first, center.second))
        }
    }

    @Test
    fun cellRectanglesEvenlyPartitionTheirLab() {
        for (lab in GridConfig.LAB_NAMES) {
            val labRect = GridConfig.LAB_RECT_FRACTIONS[lab]!!
            val cells = GridConfig.gridIdsForLab(lab).map { GridConfig.cellFractions(it)!! }
            val expectedWidth = (labRect[2] - labRect[0]) / GridConfig.GRID_COLS
            val expectedHeight = (labRect[3] - labRect[1]) / GridConfig.GRID_ROWS

            for (cell in cells) {
                assertTrue(cell[0] >= labRect[0] - epsilon)
                assertTrue(cell[1] >= labRect[1] - epsilon)
                assertTrue(cell[2] <= labRect[2] + epsilon)
                assertTrue(cell[3] <= labRect[3] + epsilon)
                assertEquals(expectedWidth, cell[2] - cell[0], epsilon)
                assertEquals(expectedHeight, cell[3] - cell[1], epsilon)
            }
        }
    }

    @Test
    fun gridAtRejectsGapsAndPointsOutsideLabs() {
        assertNull(GridConfig.gridAt(0.5f, 0.5f))
        assertNull(GridConfig.gridAt(-0.1f, 0.2f))
        assertNull(GridConfig.gridAt(Float.NaN, 0.2f))
        assertNull(GridConfig.gridAt(0.2f, Float.POSITIVE_INFINITY))
    }

    @Test
    fun gridAtUsesHalfOpenCellsAndIncludesLabOuterEdges() {
        val labRect = GridConfig.LAB_RECT_FRACTIONS["LAB 1"]!!
        val left = labRect[0]
        val top = labRect[1]
        val right = labRect[2]
        val bottom = labRect[3]
        val firstVerticalBoundary = left + (right - left) / GridConfig.GRID_COLS
        val firstHorizontalBoundary = top + (bottom - top) / GridConfig.GRID_ROWS

        assertEquals("L1-G2", GridConfig.gridAt(firstVerticalBoundary, top + 0.01f))
        assertEquals("L1-G4", GridConfig.gridAt(left + 0.01f, firstHorizontalBoundary))
        assertEquals("L1-G9", GridConfig.gridAt(right, bottom))
        assertEquals("L1-G3", GridConfig.gridAt(right, top + 0.01f))
        assertEquals("L1-G7", GridConfig.gridAt(left + 0.01f, bottom))
    }

    @Test
    fun neighborsAreEightConnectedAndStayWithinTheirLab() {
        assertEquals(3, GridConfig.neighbors("L1-G1").size)
        assertEquals(8, GridConfig.neighbors("L1-G5").size)
        assertEquals(5, GridConfig.neighbors("L1-G2").size)
        assertTrue(GridConfig.neighbors("L1-G5").all { it.startsWith("L1-") })
        assertTrue(GridConfig.neighbors("not-a-grid").isEmpty())
    }
}
