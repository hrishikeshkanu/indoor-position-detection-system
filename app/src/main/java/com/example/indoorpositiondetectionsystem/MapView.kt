package com.example.indoorpositiondetectionsystem

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.sqrt

class MapView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

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

    private var routers     = mutableMapOf<String, PointF>()
    private var distances   = mutableMapOf<String, Double>()
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

    fun updateDistances(newDistances: Map<String, Double>) {
        distances = newDistances.toMutableMap(); invalidate()
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

    private fun estimatePosition(): PointF? {
        if (routers.size < 2 || distances.isEmpty()) return null
        val sorted = distances.entries.filter { routers.containsKey(it.key) }.sortedBy { it.value }
        if (sorted.size < 2) return null

        val primaryPt   = routers[sorted[0].key] ?: return null
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

        val pos = estimatePosition()

        for ((_, point) in routers) {
            canvas.drawCircle(point.x, point.y, zoneRadius, paintCoverageFill)
            canvas.drawCircle(point.x, point.y, zoneRadius, paintCoverageStroke)
        }
        for ((lab, point) in routers) {
            canvas.drawCircle(point.x, point.y, 40f, paintRouterGlow)
            canvas.drawCircle(point.x, point.y, 18f, paintRouterFill)
            val nameY = routerLabelY(point, pos)
            canvas.drawText(lab, point.x, nameY, paintRouterLabel)
            val distText = distances[lab]?.let { if (it < 90.0) "${"%.1f".format(it)} m" else "–" } ?: "–"
            val distY = if (nameY < point.y) nameY - 24f else nameY + 30f
            canvas.drawText(distText, point.x, distY, paintDistLabel)
        }
        if (pos != null) {
            canvas.drawCircle(pos.x, pos.y, 42f, paintDeviceGlow)
            canvas.drawCircle(pos.x, pos.y, 20f, paintDeviceFill)
            val labelPt = youLabelOffset(pos)
            canvas.drawText("YOU", labelPt.x, labelPt.y, paintDeviceLabel)
        }
    }
}