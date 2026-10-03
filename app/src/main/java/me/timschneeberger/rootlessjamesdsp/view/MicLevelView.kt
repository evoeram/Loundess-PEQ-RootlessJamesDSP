package me.timschneeberger.rootlessjamesdsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Horizontal VU-style microphone level meter.
 *
 * Displays real-time input level as a horizontal gradient bar (green → yellow → red)
 * with a peak-hold marker and dB scale markings. Continuously updated via [setLevelDb].
 *
 * Designed to always run — monitors microphone input even when no measurement is active.
 */
class MicLevelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f * resources.displayMetrics.density
        alpha = 130
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f
        alpha = 80
    }
    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private var currentDb = -60f
    private var peakDb = -60f
    private var peakHoldMs = 0L
    private var clipping = false

    private val minDb = -60f
    private val maxDb = 0f

    private var gradientShader: LinearGradient? = null
    private var lastWidth = 0
    private var lastHeight = 0

    init {
        val tv = android.util.TypedValue()
        context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorOutlineVariant, tv, true
        )
        bgPaint.color = if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data.toInt()
        bgPaint.alpha = 60

        context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorOnSurface, tv, true
        )
        labelPaint.color = if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data.toInt()
        labelPaint.alpha = 130
        tickPaint.color = labelPaint.color
    }

    fun setLevelDb(dbFs: Float, isClipping: Boolean) {
        currentDb = dbFs.coerceIn(minDb, maxDb)
        clipping = isClipping
        if (dbFs > peakDb) {
            peakDb = dbFs.coerceIn(minDb, maxDb)
            peakHoldMs = System.currentTimeMillis()
        }
        postInvalidateOnAnimation()
    }

    fun reset() {
        currentDb = minDb
        peakDb = minDb
        clipping = false
        peakHoldMs = 0L
        postInvalidateOnAnimation()
    }

    private fun ensureGradient(w: Int, h: Int) {
        if (w == lastWidth && h == lastHeight && gradientShader != null) return
        lastWidth = w
        lastHeight = h
        gradientShader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(
                0xFF4CAF50.toInt(),
                0xFF8BC34A.toInt(),
                0xFFFFEB3B.toInt(),
                0xFFFF9800.toInt(),
                0xFFFF5252.toInt(),
            ),
            floatArrayOf(0f, 0.45f, 0.65f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        barPaint.shader = gradientShader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        ensureGradient(width, height)

        val dp = resources.displayMetrics.density
        val labelH = 10f * dp
        val barTop = 2f * dp
        val barBottom = h - labelH
        val barLeft = 2f * dp
        val barRight = w - 2f * dp
        val barWidth = barRight - barLeft
        val radius = 4f * dp

        // Background track
        canvas.drawRoundRect(RectF(barLeft, barTop, barRight, barBottom), radius, radius, bgPaint)

        // Level fill (from left)
        val levelFrac = ((currentDb - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
        if (levelFrac > 0.001f) {
            val fillRight = barLeft + levelFrac * barWidth
            canvas.drawRoundRect(RectF(barLeft, barTop, fillRight, barBottom), radius, radius, barPaint)
        }

        // Peak hold marker (vertical line)
        val now = System.currentTimeMillis()
        if (peakDb > minDb) {
            val elapsed = now - peakHoldMs
            if (elapsed > 1200) {
                val decay = ((elapsed - 1200) / 100f) * 3f
                peakDb = maxOf(minDb, peakDb - decay)
            }
            val peakFrac = ((peakDb - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
            if (peakFrac > 0.001f) {
                val peakX = barLeft + peakFrac * barWidth
                peakPaint.color = if (peakDb >= -3f) 0xFFFF5252.toInt()
                                  else if (peakDb >= -12f) 0xFFFF9800.toInt()
                                  else 0xFF4CAF50.toInt()
                canvas.drawLine(peakX, barTop - 1f * dp, peakX, barBottom + 1f * dp, peakPaint)
            }
        }

        // Clipping: red border
        if (clipping) {
            clipPaint.color = 0xFFFF5252.toInt()
            canvas.drawRoundRect(RectF(barLeft - 1f, barTop - 1f, barRight + 1f, barBottom + 1f),
                radius + 1f, radius + 1f, clipPaint)
        }

        // dB scale ticks + labels below
        val dbMarks = floatArrayOf(-60f, -40f, -24f, -12f, -6f, 0f)
        labelPaint.textAlign = android.graphics.Paint.Align.CENTER
        for (db in dbMarks) {
            val frac = ((db - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
            val x = barLeft + frac * barWidth
            canvas.drawLine(x, barBottom, x, barBottom + 2f * dp, tickPaint)
            val label = if (db == 0f) "0" else "${db.toInt()}"
            canvas.drawText(label, x, barBottom + labelH - 1f * dp, labelPaint)
        }
    }
}
