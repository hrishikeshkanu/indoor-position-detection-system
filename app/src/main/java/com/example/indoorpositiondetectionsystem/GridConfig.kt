package com.example.indoorpositiondetectionsystem

object GridConfig {
    const val GRID_ROWS = 3
    const val GRID_COLS = 3

    // Retune these rectangles when the floor image is replaced.
    val LAB_RECT_FRACTIONS: Map<String, List<Float>> = mapOf(
        "LAB 1" to listOf(0.03f, 0.03f, 0.45f, 0.44f),
        "LAB 2" to listOf(0.55f, 0.03f, 0.97f, 0.44f),
        "LAB 3" to listOf(0.03f, 0.56f, 0.45f, 0.97f),
        "LAB 4" to listOf(0.55f, 0.56f, 0.97f, 0.97f)
    )
    val LAB_NAMES: List<String> = listOf("LAB 1", "LAB 2", "LAB 3", "LAB 4")
    val ALL_GRID_IDS: List<String> = LAB_NAMES.flatMap(::gridIdsForLab)

    data class GridCell(val gridId: String, val lab: String, val row: Int, val col: Int)

    fun gridId(lab: String, row: Int, col: Int): String? {
        val labNumber = labNumber(lab) ?: return null
        if (row !in 0 until GRID_ROWS || col !in 0 until GRID_COLS) return null
        val cellNumber = row * GRID_COLS + col + 1
        return "L$labNumber-G$cellNumber"
    }

    fun gridIdsForLab(lab: String): List<String> {
        if (labNumber(lab) == null) return emptyList()
        return (0 until GRID_ROWS).flatMap { row ->
            (0 until GRID_COLS).mapNotNull { col -> gridId(lab, row, col) }
        }
    }

    fun parseGridId(id: String): GridCell? {
        if (id.length != 5 || id[0] != 'L' || id[2] != '-' || id[3] != 'G') return null
        if (id[1] !in '1'..'4' || id[4] !in '1'..'9') return null

        val labNumber = id[1] - '0'
        val cellNumber = id[4] - '0' - 1
        return GridCell(
            gridId = id,
            lab = "LAB $labNumber",
            row = cellNumber / GRID_COLS,
            col = cellNumber % GRID_COLS
        )
    }

    fun cellFractions(gridId: String): List<Float>? {
        val cell = parseGridId(gridId) ?: return null
        val labFractions = LAB_RECT_FRACTIONS[cell.lab] ?: return null
        val labLeft = labFractions[0]
        val labTop = labFractions[1]
        val cellWidth = (labFractions[2] - labLeft) / GRID_COLS
        val cellHeight = (labFractions[3] - labTop) / GRID_ROWS
        val left = labLeft + cell.col * cellWidth
        val top = labTop + cell.row * cellHeight
        return listOf(left, top, left + cellWidth, top + cellHeight)
    }

    fun cellCenterFractions(gridId: String): Pair<Float, Float>? {
        val fractions = cellFractions(gridId) ?: return null
        return Pair((fractions[0] + fractions[2]) / 2f, (fractions[1] + fractions[3]) / 2f)
    }

    fun gridAt(fx: Float, fy: Float): String? {
        if (!fx.isFinite() || !fy.isFinite()) return null
        for (lab in LAB_NAMES) {
            val fractions = LAB_RECT_FRACTIONS[lab] ?: continue
            val left = fractions[0]
            val top = fractions[1]
            val right = fractions[2]
            val bottom = fractions[3]
            if (fx < left || fy < top || fx > right || fy > bottom) continue

            val cellWidth = (right - left) / GRID_COLS
            val cellHeight = (bottom - top) / GRID_ROWS
            val col = when {
                fx < left + cellWidth -> 0
                fx < left + 2f * cellWidth -> 1
                else -> GRID_COLS - 1
            }
            val row = when {
                fy < top + cellHeight -> 0
                fy < top + 2f * cellHeight -> 1
                else -> GRID_ROWS - 1
            }
            return gridId(lab, row, col)
        }
        return null
    }

    fun neighbors(gridId: String): List<String> {
        val cell = parseGridId(gridId) ?: return emptyList()
        val neighbors = mutableListOf<String>()
        for (row in cell.row - 1..cell.row + 1) {
            for (col in cell.col - 1..cell.col + 1) {
                if (row == cell.row && col == cell.col) continue
                gridId(cell.lab, row, col)?.let(neighbors::add)
            }
        }
        return neighbors
    }

    private fun labNumber(lab: String): Int? =
        LAB_NAMES.indexOf(lab).takeIf { it >= 0 }?.plus(1)
}
