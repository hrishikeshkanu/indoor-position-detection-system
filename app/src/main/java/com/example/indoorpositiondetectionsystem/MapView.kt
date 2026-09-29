package com.example.indoorpositiondetectionsystem

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MapView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    data class UserMarker(
        val name: String,
        val distances: Map<String, Double>,
        val isSelf: Boolean
    )

    private val paintBackground = Paint().apply {
        color = Color.parseColor("#0A1625"); style = Paint.Style.FILL
    }
    private val paintGrid = Paint().apply {
        color = Color.parseColor("#162840"); strokeWidth = 1.5f; style = Paint.Style.STROKE
    }
    private val paintBorder = Paint().apply {
        color = Color.parseColor("#00E5FF"); strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val paintRouterFill = Paint().apply {
        color = Color.parseColor("#00E5FF"); style = Paint.Style.FILL
    }
    private val paintRouterGlow = Paint().apply {
        color = Color.argb(60, 0, 229, 255); style = Paint.Style.FILL
    }
    private val paintCoverageStroke = Paint().apply {
        color = Color.argb(120, 0, 229, 255); strokeWidth = 2f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val paintCoverageFill = Paint().apply {
        color = Color.argb(20, 0, 229, 255); style = Paint.Style.FILL
    }
    private val paintDeviceFill = Paint().apply {
        color = Color.parseColor("#00FF9C"); style = Paint.Style.FILL
    }
    private val paintDeviceGlow = Paint().apply {
        color = Color.argb(70, 0, 255, 156); style = Paint.Style.FILL
    }
    private val paintOtherDeviceFill = Paint().apply {
        color = Color.parseColor("#FF6EC7"); style = Paint.Style.FILL
    }
    private val paintOtherDeviceGlow = Paint().apply {
        color = Color.argb(70, 255, 110, 199); style = Paint.Style.FILL
    }
    private val paintSelfRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EAF4F7"); strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val paintRouterLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF"); textSize = 34f
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val paintDistLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88AABBCC"); textSize = 24f; textAlign = Paint.Align.CENTER
    }
    private val paintDeviceLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF9C"); textSize = 28f
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val paintOtherDeviceLabel = Paint(paintDeviceLabel).apply {
        color = Color.parseColor("#FF6EC7")
    }

    private var routers     = mutableMapOf<String, PointF>()
    private var userMarkers: List<UserMarker> = emptyList()
    private var mapScale    = 1f
    private var zoneRadius  = 1f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val px = w * 0.18f; val py = h * 0.14f
        routers = mutableMapOf(
            "LAB 1" to PointF(px,       py),
            "LAB 2" to PointF(w - px,   py),
            "LAB 3" to PointF(px,       h - py),
            "LAB 4" to PointF(w - px,   h - py)
        )
        mapScale   = sqrt((w * w + h * h).toDouble()).toFloat() / 20f
        zoneRadius = w * 0.30f
    }

    fun updateUsers(users: List<UserMarker>) {
        userMarkers = users
        invalidate()
    }

    fun updateDistances(newDistances: Map<String, Double>) {
        updateUsers(listOf(UserMarker("YOU", newDistances, true)))
    }

    private fun pixelDist(a: PointF, b: PointF): Float {
        val dx = a.x - b.x; val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun unitVector(from: PointF, to: PointF): PointF {
        val dx = to.x - from.x; val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy)
        return if (len < 0.01f) PointF(0f, -1f) else PointF(dx / len, dy / len)
    }

    private fun routerLabelY(routerPt: PointF, youPos: PointF?, threshold: Float = 90f): Float {
        if (youPos == null) return routerPt.y - 50f
        return if (pixelDist(routerPt, youPos) < threshold && youPos.y <= routerPt.y)
            routerPt.y + 78f else routerPt.y - 50f
    }

    private fun youLabelOffset(pos: PointF): PointF {
        if (routers.isEmpty()) return PointF(pos.x, pos.y - 55f)
        var nearestDist = Float.MAX_VALUE; var nearestPoint = PointF(pos.x, pos.y - 1f)
        for ((_, pt) in routers) {
            val d = pixelDist(pos, pt)
            if (d < nearestDist) { nearestDist = d; nearestPoint = pt }
        }
        val labelRadius = 58f
        return if (nearestDist < 110f) {
            val away = unitVector(nearestPoint, pos)
            PointF(pos.x + away.x * labelRadius, pos.y + away.y * labelRadius)
        } else PointF(pos.x, pos.y - labelRadius)
    }

    private fun estimatePosition(distances: Map<String, Double>): PointF? {
        val sorted = distances.entries
            .filter { routers.containsKey(it.key) && it.value < 90.0 }
            .sortedBy { it.value }
        if (sorted.isEmpty()) return null

        val primaryPt   = routers[sorted[0].key] ?: return null
        if (sorted.size == 1) {
            val center = PointF(width / 2f, height / 2f)
            val offset = (sorted[0].value.toFloat() * mapScale).coerceAtMost(zoneRadius - 30f)
            val dir = unitVector(primaryPt, center)
            return PointF(primaryPt.x + dir.x * offset, primaryPt.y + dir.y * offset)
        }

        val secondaryPt = routers[sorted[1].key] ?: return null
        val rawOffset   = sorted[0].value.toFloat() * mapScale
        val offset      = rawOffset.coerceAtMost(zoneRadius - 30f)
        val dir         = unitVector(primaryPt, secondaryPt)

        return PointF(primaryPt.x + dir.x * offset, primaryPt.y + dir.y * offset)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()

        canvas.drawRect(0f, 0f, w, h, paintBackground)
        var x = 80f; while (x < w) { canvas.drawLine(x, 0f, x, h, paintGrid); x += 80f }
        var y = 80f; while (y < h) { canvas.drawLine(0f, y, w, y, paintGrid); y += 80f }
        canvas.drawRect(4f, 4f, w - 4f, h - 4f, paintBorder)
        if (routers.isEmpty()) return

        val referenceMarker = userMarkers.firstOrNull { it.isSelf } ?: userMarkers.firstOrNull()
        val referencePos = referenceMarker?.let { estimatePosition(it.distances) }

        for ((_, point) in routers) {
            canvas.drawCircle(point.x, point.y, zoneRadius, paintCoverageFill)
            canvas.drawCircle(point.x, point.y, zoneRadius, paintCoverageStroke)
        }
        for ((lab, point) in routers) {
            canvas.drawCircle(point.x, point.y, 40f, paintRouterGlow)
            canvas.drawCircle(point.x, point.y, 18f, paintRouterFill)
            val nameY = routerLabelY(point, referencePos)
            canvas.drawText(lab, point.x, nameY, paintRouterLabel)
            val distText = referenceMarker?.distances?.get(lab)
                ?.let { if (it < 90.0) "${"%.1f".format(it)} m" else "–" } ?: "–"
            val distY = if (nameY < point.y) nameY - 24f else nameY + 30f
            canvas.drawText(distText, point.x, distY, paintDistLabel)
        }

        data class EstimatedMarker(val marker: UserMarker, val truePos: PointF?)
        val estimated = userMarkers.map { EstimatedMarker(it, estimatePosition(it.distances)) }
        val adjustedPositions = estimated.map { it.truePos?.let(::copyPoint) }.toMutableList()
        val positionedIndices = estimated.indices.filter { estimated[it].truePos != null }
        val ungrouped = positionedIndices.toMutableSet()

        while (ungrouped.isNotEmpty()) {
            val group = mutableListOf(ungrouped.first())
            ungrouped.remove(group.first())
            var index = 0
            while (index < group.size) {
                val current = estimated[group[index]].truePos!!
                val nearby = ungrouped.filter { candidate ->
                    pixelDist(current, estimated[candidate].truePos!!) < 60f
                }
                group.addAll(nearby)
                ungrouped.removeAll(nearby.toSet())
                index++
            }

            if (group.size > 1) {
                val selfIndex = group.firstOrNull { estimated[it].marker.isSelf }
                val anchor = selfIndex?.let { estimated[it].truePos!! } ?: PointF(
                    group.map { estimated[it].truePos!!.x }.average().toFloat(),
                    group.map { estimated[it].truePos!!.y }.average().toFloat()
                )
                val circleIndices = group.filter { it != selfIndex }
                val angleStep = 2.0 * PI / circleIndices.size
                circleIndices.forEachIndexed { circleIndex, markerIndex ->
                    val angle = -PI / 2.0 + angleStep * circleIndex
                    adjustedPositions[markerIndex] = PointF(
                        anchor.x + cos(angle).toFloat() * 50f,
                        anchor.y + sin(angle).toFloat() * 50f
                    )
                }
            }
        }

        val outOfRangeCount = adjustedPositions.count { it == null }
        adjustedPositions.indices.forEach { index ->
            adjustedPositions[index]?.let { position ->
                position.x = position.x.coerceIn(30f, (w - 30f).coerceAtLeast(30f))
                position.y = position.y.coerceIn(30f, (h - 30f).coerceAtLeast(30f))
            }
        }

        val drawnLabelPoints = mutableListOf<PointF>()
        val drawOrder = estimated.indices.sortedBy { estimated[it].marker.isSelf }
        for (markerIndex in drawOrder) {
            val marker = estimated[markerIndex].marker
            val pos = adjustedPositions[markerIndex] ?: continue
            val glowPaint = if (marker.isSelf) paintDeviceGlow else paintOtherDeviceGlow
            val fillPaint = if (marker.isSelf) paintDeviceFill else paintOtherDeviceFill
            val labelPaint = if (marker.isSelf) paintDeviceLabel else paintOtherDeviceLabel
            val glowRadius = if (marker.isSelf) 42f else 34f
            val dotRadius = if (marker.isSelf) 20f else 16f

            canvas.drawCircle(pos.x, pos.y, glowRadius, glowPaint)
            canvas.drawCircle(pos.x, pos.y, dotRadius, fillPaint)
            if (marker.isSelf) canvas.drawCircle(pos.x, pos.y, dotRadius + 2f, paintSelfRing)

            val initialLabelPt = youLabelOffset(pos)
            val labelCandidates = listOf(0f, 24f, -24f, 48f).map { offset ->
                PointF(
                    initialLabelPt.x.coerceIn(30f, (w - 30f).coerceAtLeast(30f)),
                    (initialLabelPt.y + offset).coerceIn(30f, (h - 30f).coerceAtLeast(30f))
                )
            }
            val labelPt = labelCandidates.firstOrNull { candidate ->
                drawnLabelPoints.none { pixelDist(it, candidate) < 40f }
            } ?: continue
            drawnLabelPoints.add(labelPt)
            canvas.drawText(marker.name, labelPt.x, labelPt.y, labelPaint)
        }

        if (outOfRangeCount > 0) {
            val caption = if (outOfRangeCount == 1) "1 user out of range"
                else "$outOfRangeCount users out of range"
            canvas.drawText(caption, w / 2f, (h - 16f).coerceAtLeast(24f), paintDistLabel)
        }
    }

    private fun copyPoint(point: PointF): PointF = PointF(point.x, point.y)
}