package com.example.indoorpositiondetectionsystem

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

class MapView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    companion object {
        private const val MAP_WIDTH_METERS = 20f
        private const val LAB_LEFT = 0.25f
        private const val LAB_RIGHT = 0.75f
        private const val LAB_TOP = 0.25f
        private const val LAB_BOTTOM = 0.75f
        private val LAB_RECT_FRACTIONS = mapOf(
            "LAB 1" to listOf(0.03f, 0.03f, 0.45f, 0.44f),
            "LAB 2" to listOf(0.55f, 0.03f, 0.97f, 0.44f),
            "LAB 3" to listOf(0.03f, 0.56f, 0.45f, 0.97f),
            "LAB 4" to listOf(0.55f, 0.56f, 0.97f, 0.97f)
        )
    }

    data class UserMarker(
        val name: String,
        val distances: Map<String, Double>,
        val isSelf: Boolean
    )

    private val paintBackground = Paint().apply {
        color = Color.parseColor("#0A1625"); style = Paint.Style.FILL
    }
    private var mapRect = RectF()
    private var floorBitmap: Bitmap? = null
    private val paintFloor = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val paintScrim = Paint().apply {
        color = Color.argb(90, 10, 22, 37); style = Paint.Style.FILL
    }
    private val paintLabHighlightFill = Paint().apply {
        color = Color.argb(38, 0, 255, 156); style = Paint.Style.FILL
    }
    private val paintLabHighlightStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 255, 156); style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val paintDotOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A1625"); style = Paint.Style.STROKE; strokeWidth = 5f
    }
    private val paintCaptionPill = Paint().apply {
        color = Color.argb(170, 10, 22, 37); style = Paint.Style.FILL
    }
    private val paintLegendPill = Paint().apply {
        color = Color.argb(170, 10, 22, 37); style = Paint.Style.FILL
    }
    private val paintLegendText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6F7FF"); textSize = 20f
        textAlign = Paint.Align.LEFT
    }
    private val paintLabelHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A1625"); style = Paint.Style.STROKE
        strokeWidth = 6f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
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
        color = Color.parseColor("#00E5FF"); textSize = 22f
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val paintDistLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88AABBCC"); textSize = 24f; textAlign = Paint.Align.CENTER
    }
    private val paintDistValue = Paint(paintDistLabel).apply {
        color = Color.parseColor("#E6F7FF"); textSize = 24f
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
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
        val side = minOf(w, h).toFloat()
        val left = (w - side) / 2f
        val top = (h - side) / 2f
        mapRect.set(left, top, left + side, top + side)

        floorBitmap?.recycle()
        floorBitmap = null
        if (side > 0f) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeResource(resources, R.drawable.floor_map, bounds)
                check(bounds.outWidth > 0 && bounds.outHeight > 0)

                var sampleSize = 1
                val largestDimension = maxOf(bounds.outWidth, bounds.outHeight)
                while (largestDimension / sampleSize > side) sampleSize *= 2

                val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                floorBitmap = BitmapFactory.decodeResource(resources, R.drawable.floor_map, options)
            } catch (_: Exception) {
                floorBitmap = null
            }
        }
        if (!mapRect.isEmpty) {
            val side = mapRect.width()
            fun at(fx: Float, fy: Float) =
                PointF(mapRect.left + side * fx, mapRect.top + side * fy)
            routers = mutableMapOf(
                "LAB 1" to at(LAB_LEFT, LAB_TOP),
                "LAB 2" to at(LAB_RIGHT, LAB_TOP),
                "LAB 3" to at(LAB_LEFT, LAB_BOTTOM),
                "LAB 4" to at(LAB_RIGHT, LAB_BOTTOM)
            )
            mapScale = side / MAP_WIDTH_METERS
            zoneRadius = side * 0.22f
        } else {
            routers.clear()
            mapScale = 0f
            zoneRadius = 0f
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        floorBitmap?.recycle(); floorBitmap = null
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
        val labelRadius = 44f
        return if (nearestDist < 90f) {
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
            val center = PointF(mapRect.centerX(), mapRect.centerY())
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
        if (floorBitmap != null) {
            canvas.drawBitmap(floorBitmap!!, null, mapRect, paintFloor)
            canvas.drawRect(mapRect, paintScrim)
        } else {
            val cellSize = mapRect.width() / 10f
            if (cellSize > 0f) {
                var x = mapRect.left
                while (x <= mapRect.right) {
                    canvas.drawLine(x, mapRect.top, x, mapRect.bottom, paintGrid)
                    x += cellSize
                }
                var y = mapRect.top
                while (y <= mapRect.bottom) {
                    canvas.drawLine(mapRect.left, y, mapRect.right, y, paintGrid)
                    y += cellSize
                }
            }
        }
        canvas.drawRect(mapRect.left + 4f, mapRect.top + 4f, mapRect.right - 4f, mapRect.bottom - 4f, paintBorder)
        if (routers.isEmpty()) return

        val referenceMarker = userMarkers.firstOrNull { it.isSelf } ?: userMarkers.firstOrNull()
        val referencePos = referenceMarker?.let { estimatePosition(it.distances) }
        if (!mapRect.isEmpty) {
            val detectedLab = referenceMarker?.distances?.entries
                ?.filter { it.value < 90.0 && LAB_RECT_FRACTIONS.containsKey(it.key) }
                ?.minByOrNull { it.value }?.key
            val fractions = detectedLab?.let { LAB_RECT_FRACTIONS[it] }
            if (fractions != null) {
                val side = mapRect.width()
                val labRect = RectF(
                    mapRect.left + side * fractions[0],
                    mapRect.top + side * fractions[1],
                    mapRect.left + side * fractions[2],
                    mapRect.top + side * fractions[3]
                )
                canvas.drawRoundRect(labRect, 12f, 12f, paintLabHighlightFill)
                canvas.drawRoundRect(labRect, 12f, 12f, paintLabHighlightStroke)
            }
        }

        for ((lab, point) in routers) {
            canvas.drawCircle(point.x, point.y, 24f, paintRouterGlow)
            paintDotOutline.strokeWidth = 4f
            canvas.drawCircle(point.x, point.y, 10f, paintDotOutline)
            paintDotOutline.strokeWidth = 5f
            canvas.drawCircle(point.x, point.y, 9f, paintRouterFill)
            val labelMinY = (mapRect.top + 30f).coerceAtMost(mapRect.centerY())
            val labelMaxY = (mapRect.bottom - 30f).coerceAtLeast(mapRect.centerY())
            val nameY = routerLabelY(point, referencePos).coerceIn(labelMinY, labelMaxY)
            val routerLabel = "AP ${lab.substringAfter("LAB ")}"
            drawHaloText(canvas, routerLabel, point.x, nameY, paintRouterLabel)
            val distText = referenceMarker?.distances?.get(lab)
                ?.let { if (it < 90.0) "${"%.1f".format(it)} m" else "–" } ?: "–"
            val distY = (if (nameY < point.y) point.y + 52f else point.y - 40f)
                .coerceIn(labelMinY, labelMaxY)
            drawHaloText(canvas, distText, point.x, distY, paintDistValue)
        }

        drawLegend(canvas)

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
                    .sortedWith(compareBy<Int> { estimated[it].marker.name }.thenBy { it })
                var markerOffset = 0
                var ringNumber = 1
                while (markerOffset < circleIndices.size) {
                    val radius = 50f + (ringNumber - 1) * 45f
                    val capacity = maxOf(1, floor(2.0 * PI * radius / 44.0).toInt())
                    val ringCount = minOf(capacity, circleIndices.size - markerOffset)
                    val angleStep = 2.0 * PI / capacity
                    val ringOffset = if (ringNumber % 2 == 0) angleStep / 2.0 else 0.0
                    repeat(ringCount) { slot ->
                        val angle = -PI / 2.0 + ringOffset + angleStep * slot
                        val markerIndex = circleIndices[markerOffset + slot]
                        adjustedPositions[markerIndex] = PointF(
                            anchor.x + cos(angle).toFloat() * radius,
                            anchor.y + sin(angle).toFloat() * radius
                        )
                    }
                    markerOffset += ringCount
                    ringNumber++
                }
            }
        }

        val outOfRangeCount = adjustedPositions.count { it == null }
        val placedCount = adjustedPositions.size - outOfRangeCount
        val isCrowded = placedCount > 12
        adjustedPositions.indices.forEach { index ->
            adjustedPositions[index]?.let { position ->
                val minX = mapRect.left + 30f
                val minY = mapRect.top + 30f
                position.x = position.x.coerceIn(minX, (mapRect.right - 30f).coerceAtLeast(minX))
                position.y = position.y.coerceIn(minY, (mapRect.bottom - 30f).coerceAtLeast(minY))
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
            val glowRadius = if (marker.isSelf) 42f else if (isCrowded) 22f else 34f
            val dotRadius = if (marker.isSelf) 20f else if (isCrowded) 11f else 16f

            canvas.drawCircle(pos.x, pos.y, glowRadius, glowPaint)
            canvas.drawCircle(pos.x, pos.y, dotRadius + 1f, paintDotOutline)
            canvas.drawCircle(pos.x, pos.y, dotRadius, fillPaint)
            if (marker.isSelf) canvas.drawCircle(pos.x, pos.y, dotRadius + 2f, paintSelfRing)

            val labelText = if (isCrowded && !marker.isSelf && marker.name.length > 10)
                marker.name.take(9) + "…" else marker.name
            labelPaint.textSize = if (isCrowded && !marker.isSelf) 22f else 28f
            val initialLabelPt = youLabelOffset(pos)
            val textWidth = labelPaint.measureText(labelText)
            val fontMetrics = labelPaint.fontMetrics
            val availableWidth = (mapRect.width() - 60f).coerceAtLeast(1f)
            val textScale = (availableWidth / textWidth.coerceAtLeast(1f)).coerceAtMost(1f)
            val renderedHalfWidth = textWidth * textScale / 2f
            val minLabelX = mapRect.left + 30f + renderedHalfWidth
            val maxLabelX = (mapRect.right - 30f - renderedHalfWidth).coerceAtLeast(minLabelX)
            val minLabelY = mapRect.top + 30f - fontMetrics.top
            val maxLabelY = (mapRect.bottom - 30f - fontMetrics.bottom).coerceAtLeast(minLabelY)
            val rawLabelCandidates = listOf(
                PointF(initialLabelPt.x, initialLabelPt.y - 24f),
                PointF(initialLabelPt.x, initialLabelPt.y + 24f),
                PointF(initialLabelPt.x - 50f, initialLabelPt.y),
                PointF(initialLabelPt.x + 50f, initialLabelPt.y)
            )
            val labelCandidates = rawLabelCandidates.map { candidate ->
                PointF(candidate.x.coerceIn(minLabelX, maxLabelX), candidate.y.coerceIn(minLabelY, maxLabelY))
            }
            val labelPt = labelCandidates.firstOrNull { candidate ->
                drawnLabelPoints.none { pixelDist(it, candidate) < 40f }
            } ?: continue
            drawnLabelPoints.add(labelPt)
            if (textScale < 1f) {
                canvas.save()
                canvas.scale(textScale, 1f, labelPt.x, labelPt.y)
            }
            drawHaloText(canvas, labelText, labelPt.x, labelPt.y, labelPaint)
            if (textScale < 1f) canvas.restore()
        }

        val caption = "$placedCount on map" +
            if (outOfRangeCount > 0) ", $outOfRangeCount out of range" else ""
        val captionWidth = paintDistValue.measureText(caption) + 24f
        val captionBottom = mapRect.bottom - 8f
        val captionRect = RectF(
            mapRect.centerX() - captionWidth / 2f,
            captionBottom - 36f,
            mapRect.centerX() + captionWidth / 2f,
            captionBottom
        )
        canvas.drawRoundRect(captionRect, 14f, 14f, paintCaptionPill)
        val captionMetrics = paintDistValue.fontMetrics
        val captionBaseline = captionRect.centerY() - (captionMetrics.ascent + captionMetrics.descent) / 2f
        canvas.drawText(caption, mapRect.centerX(), captionBaseline, paintDistValue)
    }

    private fun drawLegend(canvas: Canvas) {
        if (mapRect.width() < 400f) return

        val rows = listOf(
            "You" to paintDeviceFill,
            "Others" to paintOtherDeviceFill,
            "Router (AP)" to paintRouterFill
        )
        val textWidth = rows.maxOf { paintLegendText.measureText(it.first) }
        val left = mapRect.left + 12f
        val top = mapRect.top + 12f
        val legendWidth = textWidth + 48f
        val legendHeight = 3 * 26f + 16f
        val legendRect = RectF(left, top, left + legendWidth, top + legendHeight)
        canvas.drawRoundRect(legendRect, 12f, 12f, paintLegendPill)

        val metrics = paintLegendText.fontMetrics
        rows.forEachIndexed { index, (label, dotPaint) ->
            val centerY = top + 8f + 13f + index * 26f
            canvas.drawCircle(left + 18f, centerY, 6f, dotPaint)
            val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(label, left + 34f, baseline, paintLegendText)
        }
    }

    private fun drawHaloText(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
        paintLabelHalo.textSize = paint.textSize
        canvas.drawText(text, x, y, paintLabelHalo)
        canvas.drawText(text, x, y, paint)
    }

    private fun copyPoint(point: PointF): PointF = PointF(point.x, point.y)
}