package com.example.indoorpositiondetectionsystem

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class SignalGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val signalMap = mutableMapOf<String, Int>()

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 48f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0BEC5")
        textSize = 28f
    }

    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 26f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C344A")
        strokeWidth = 1.5f
    }

    private val backgroundBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#24384A")
        style = Paint.Style.FILL
    }

    fun updateSignals(signals: Map<String, Int>) {
        signalMap.clear()
        signalMap.putAll(signals)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {

        super.onDraw(canvas)

        canvas.drawColor(Color.parseColor("#0A1625"))

        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawRect(
            2f,
            2f,
            w - 2f,
            h - 2f,
            borderPaint
        )

        canvas.drawText(
            "WiFi AP Proximity Graph",
            35f,
            60f,
            titlePaint
        )

        val labs = listOf(
            "LAB 1",
            "LAB 2",
            "LAB 3",
            "LAB 4"
        )

        val startX = 170f
        val barHeight = 42f
        val graphWidth = w - 320f

        var y = 120f

        for (lab in labs) {

            canvas.drawLine(
                25f,
                y + 55f,
                w - 25f,
                y + 55f,
                gridPaint
            )

            val rssi = signalMap[lab] ?: -100

            /*
                Normalize RSSI

                -100 dBm = 0%
                -40 dBm = 100%
             */

            val percent =
                ((rssi + 100).coerceIn(0, 60) * 100) / 60

            val widthBar =
                graphWidth * percent / 100f

            barPaint.color = when {

                rssi >= -60 ->
                    Color.parseColor("#00FF9C")

                rssi >= -70 ->
                    Color.parseColor("#00E5FF")

                rssi >= -80 ->
                    Color.parseColor("#FFC107")

                else ->
                    Color.parseColor("#FF5252")
            }

            canvas.drawText(
                lab,
                35f,
                y + 28f,
                labelPaint
            )

            canvas.drawRoundRect(
                RectF(
                    startX,
                    y,
                    startX + graphWidth,
                    y + barHeight
                ),
                16f,
                16f,
                backgroundBarPaint
            )

            canvas.drawRoundRect(
                RectF(
                    startX,
                    y,
                    startX + widthBar,
                    y + barHeight
                ),
                16f,
                16f,
                barPaint
            )

            canvas.drawText(
                "$rssi dBm",
                startX + graphWidth + 18f,
                y + 18f,
                valuePaint
            )

            canvas.drawText(
                "$percent%",
                startX + graphWidth + 18f,
                y + 45f,
                percentPaint
            )

            y += 90f
        }

        drawLegend(canvas, h)
    }

    private fun drawLegend(canvas: Canvas, canvasHeight: Float) {

        val legendY = canvasHeight - 45f

        val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 24f
            color = Color.WHITE
        }

        val green = Paint().apply {
            color = Color.parseColor("#00FF9C")
        }

        val blue = Paint().apply {
            color = Color.parseColor("#00E5FF")
        }

        val yellow = Paint().apply {
            color = Color.parseColor("#FFC107")
        }

        val red = Paint().apply {
            color = Color.parseColor("#FF5252")
        }

        canvas.drawCircle(40f, legendY, 10f, green)
        canvas.drawText("Strong", 60f, legendY + 8f, legendPaint)

        canvas.drawCircle(200f, legendY, 10f, blue)
        canvas.drawText("Good", 220f, legendY + 8f, legendPaint)

        canvas.drawCircle(340f, legendY, 10f, yellow)
        canvas.drawText("Weak", 360f, legendY + 8f, legendPaint)

        canvas.drawCircle(500f, legendY, 10f, red)
        canvas.drawText("Very Weak", 520f, legendY + 8f, legendPaint)
    }
}