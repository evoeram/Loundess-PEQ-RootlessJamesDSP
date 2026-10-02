package me.timschneeberger.rootlessjamesdsp.view

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.withStyledAttributes
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Lightweight frequency-response plot for loudness correction visualization.
 * Draws the correction curve (gain vs frequency) in real time.
 */
class LoudnessSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val mGraphBackground = Paint()
    private val mGridLineMajor = Paint()
    private val mGridLineMinor = Paint()
    private val mGridLineZeroDb = Paint()
    private val mFreqLabelPaint = Paint()
    private val mDbLabelPaint = Paint()
    private val mAxisLabelText = Paint()
    private val mCurvePaint = Paint()
    private val mFillPaint = Paint()
    private val mFadePaint = Paint()

    private var mViewWidth = 0f
    private var mViewHeight = 0f
    private var mDensity = 1f
    private var mIsDarkMode = false

    private val padLeftProp = 15f / 800f
    private val padRightProp = 15f / 800f
    private val padTopProp = 10f / 200f
    private val padBottomProp = 28f / 200f

    private var mPlotLeft = 0f
    private var mPlotTop = 0f
    private var mPlotWidth = 0f
    private var mPlotHeight = 0f

    private var mFrequencies = DoubleArray(0)
    private var mGains = DoubleArray(0)
    private var mPreampDb = 0.0

    private var mMaxDb = 3f
    private var mMinDb = -3f

    private val crinXvals = intArrayOf(2, 3, 4, 5, 6, 8, 10, 15)
    private val tickPattern = intArrayOf(3, 0, 0, 1, 0, 0, 2, 0)
    private val tickThicknessBase = floatArrayOf(0.2f, 0.4f, 0.4f, 0.9f, 1.5f)

    private data class XTick(val freq: Double, val type: Int, val label: String?)
    private val xTicks = mutableListOf<XTick>()

    private val mPath = Path()
    private val mFillPath = Path()
    private val mClipRect = RectF()
    private val mBgRect = RectF()

    private val mCurveColor: Int

    init {
        mDensity = context.resources.displayMetrics.density
        mIsDarkMode = (context.resources.configuration.uiMode
            and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val accentColor = getColor(android.R.attr.colorAccent)
        mCurveColor = accentColor

        buildXTicks()
        initPaints()
    }

    private fun buildXTicks() {
        xTicks.clear()
        for (exp in 1..3) {
            for (m in crinXvals) {
                val f = m * Math.pow(10.0, exp.toDouble())
                if (f <= MAX_FREQ) {
                    val tickType = getTickType(xTicks.size)
                    val label = if (tickType != 0) formatXLabel(f) else null
                    xTicks.add(XTick(f, tickType, label))
                }
            }
        }
        xTicks.add(0, XTick(20.0, 4, "20Hz"))
        xTicks.add(XTick(20000.0, 4, "20kHz"))
    }

    private fun getTickType(i: Int): Int {
        if (i == 0 || i == 3 * 8) return 4
        return tickPattern[i % 8]
    }

    private fun formatXLabel(f: Double): String {
        return if (f >= 1000.0) "${(f / 1000.0).toInt()}k" else "${f.toInt()}"
    }

    private fun initPaints() {
        val dark = mIsDarkMode
        val bgColor = if (dark) Color.argb(255, 30, 30, 32) else Color.WHITE
        mGraphBackground.color = bgColor
        mGraphBackground.style = Paint.Style.FILL
        mGraphBackground.isAntiAlias = true

        val gridMinorColor = if (dark) Color.argb(50, 200, 200, 200) else Color.argb(40, 51, 51, 51)
        val gridMajorColor = if (dark) Color.argb(100, 200, 200, 200) else Color.argb(80, 51, 51, 51)
        val gridZeroColor = if (dark) Color.argb(160, 220, 220, 220) else Color.argb(160, 85, 85, 85)

        mGridLineMinor.color = gridMinorColor
        mGridLineMinor.style = Paint.Style.STROKE
        mGridLineMinor.strokeWidth = 0.5f * mDensity

        mGridLineMajor.color = gridMajorColor
        mGridLineMajor.style = Paint.Style.STROKE
        mGridLineMajor.strokeWidth = 1f * mDensity

        mGridLineZeroDb.color = gridZeroColor
        mGridLineZeroDb.style = Paint.Style.STROKE
        mGridLineZeroDb.strokeWidth = 1.5f * mDensity
        mGridLineZeroDb.pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 7f), 0f)

        val textColor = if (dark) Color.argb(180, 220, 220, 220) else Color.argb(160, 51, 51, 51)

        mFreqLabelPaint.textAlign = Paint.Align.CENTER
        mFreqLabelPaint.textSize = sp(9f)
        mFreqLabelPaint.color = textColor
        mFreqLabelPaint.isAntiAlias = true

        mDbLabelPaint.textAlign = Paint.Align.LEFT
        mDbLabelPaint.textSize = sp(9f)
        mDbLabelPaint.color = textColor
        mDbLabelPaint.isAntiAlias = true

        mAxisLabelText.textSize = sp(9f)
        mAxisLabelText.color = textColor
        mAxisLabelText.isAntiAlias = true

        mCurvePaint.color = mCurveColor
        mCurvePaint.style = Paint.Style.STROKE
        mCurvePaint.strokeWidth = 2.5f * mDensity
        mCurvePaint.isAntiAlias = true
        mCurvePaint.strokeCap = Paint.Cap.ROUND
        mCurvePaint.strokeJoin = Paint.Join.ROUND

        mFillPaint.color = mCurveColor
        mFillPaint.style = Paint.Style.FILL
        mFillPaint.alpha = 25
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, getContext().resources.displayMetrics)

    private fun getColor(attr: Int): Int {
        if (isInEditMode) return Color.GRAY
        var color = 0
        context.withStyledAttributes(TypedValue().data, intArrayOf(attr)) {
            color = getColor(0, 0)
        }
        return color
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        mViewWidth = (right - left).toFloat()
        mViewHeight = (bottom - top).toFloat()

        val padLeft = padLeftProp * mViewWidth
        val padRight = padRightProp * mViewWidth
        val padTop = padTopProp * mViewHeight
        val padBottom = padBottomProp * mViewHeight

        mPlotLeft = padLeft
        mPlotTop = padTop
        mPlotWidth = (mViewWidth - padLeft - padRight).coerceAtLeast(0f)
        mPlotHeight = (mViewHeight - padTop - padBottom).coerceAtLeast(0f)
    }

    fun setCurve(frequencies: DoubleArray, gains: DoubleArray, preampDb: Double) {
        mFrequencies = frequencies
        mGains = gains
        mPreampDb = preampDb
        updateDbRange()
        postInvalidate()
    }

    fun clearCurve() {
        mFrequencies = DoubleArray(0)
        mGains = DoubleArray(0)
        mPreampDb = 0.0
        mMaxDb = 3f
        mMinDb = -3f
        postInvalidate()
    }

    private fun updateDbRange() {
        val allValues = mGains.toList()
        if (allValues.isEmpty()) {
            mMaxDb = 3f; mMinDb = -3f; return
        }
        val maxVal = allValues.maxOrNull() ?: 0.0
        val minVal = allValues.minOrNull() ?: 0.0
        mMaxDb = (ceil((maxVal + 3f) / 3f) * 3f).toFloat()
        if (mMaxDb < 3f) mMaxDb = 3f
        mMinDb = (floor((minVal - 3f) / 3f) * 3f).toFloat()
        if (mMinDb > -3f) mMinDb = -3f
    }

    override fun onDraw(canvas: Canvas) {
        mPath.rewind()
        mFillPath.rewind()

        val zeroY = mPlotTop + projectY(0f) * mPlotHeight

        val cornerRadius = 4f / 800f * mViewWidth
        mBgRect.set(0f, 0f, mViewWidth, mViewHeight)
        canvas.drawRoundRect(mBgRect, cornerRadius, cornerRadius, mGraphBackground)

        // Draw horizontal grid lines
        val step = computeDbStep()
        var db = floor(mMinDb / step) * step
        while (db <= mMaxDb + 0.01f) {
            val y = mPlotTop + projectY(db) * mPlotHeight
            val isZero = abs(db) < 0.01f
            val isMajor = db.roundToInt() % (if (step >= 5f) 10 else 5) == 0

            if (isZero) {
                canvas.drawLine(mPlotLeft, y, mPlotLeft + mPlotWidth, y, mGridLineZeroDb)
            } else if (isMajor) {
                canvas.drawLine(mPlotLeft, y, mPlotLeft + mPlotWidth, y, mGridLineMajor)
            } else {
                canvas.drawLine(mPlotLeft, y, mPlotLeft + mPlotWidth, y, mGridLineMinor)
            }

            if (!isZero) {
                val dbLabel = formatDbLabel(db)
                val labelY = y - (mDbLabelPaint.descent() + mDbLabelPaint.ascent()) / 2f
                canvas.drawText(dbLabel, mPlotLeft + mPlotWidth - 4f * mDensity, labelY, mDbLabelPaint)
            }
            db += step
        }

        // Draw X-axis ticks
        for ((index, tick) in xTicks.withIndex()) {
            val x = mPlotLeft + projectX(tick.freq) * mPlotWidth
            val thickness = tickThicknessBase[tick.type] * mDensity
            mGridLineMinor.strokeWidth = thickness
            canvas.drawLine(x, mPlotTop, x, mPlotTop + mPlotHeight, mGridLineMinor)

            if (tick.label != null) {
                val freqLabelY = mPlotTop + mPlotHeight + mPlotHeight * 0.14f
                when (index) {
                    0 -> {
                        mFreqLabelPaint.textAlign = Paint.Align.LEFT
                        canvas.drawText(tick.label, x, freqLabelY, mFreqLabelPaint)
                    }
                    xTicks.lastIndex -> {
                        mFreqLabelPaint.textAlign = Paint.Align.RIGHT
                        canvas.drawText(tick.label, x, freqLabelY, mFreqLabelPaint)
                    }
                    else -> {
                        mFreqLabelPaint.textAlign = Paint.Align.CENTER
                        canvas.drawText(tick.label, x, freqLabelY, mFreqLabelPaint)
                    }
                }
            }
        }
        mFreqLabelPaint.textAlign = Paint.Align.CENTER
        mGridLineMinor.strokeWidth = 0.5f * mDensity

        // Clip to plot area and draw curve
        mClipRect.set(mPlotLeft, mPlotTop, mPlotLeft + mPlotWidth, mPlotTop + mPlotHeight)
        val saveCount = canvas.save()
        canvas.clipRect(mClipRect)

        if (mFrequencies.isNotEmpty() && mGains.size == mFrequencies.size) {
            // Build curve path
            for (i in mFrequencies.indices) {
                val x = mPlotLeft + projectX(mFrequencies[i]) * mPlotWidth
                val y = mPlotTop + projectY(mGains[i].toFloat()) * mPlotHeight
                if (i == 0) mPath.moveTo(x, y)
                else mPath.lineTo(x, y)
            }

            // Build fill path (down to zero line)
            mFillPath.addPath(mPath)
            val lastX = mPlotLeft + projectX(mFrequencies.last()) * mPlotWidth
            val firstX = mPlotLeft + projectX(mFrequencies.first()) * mPlotWidth
            mFillPath.lineTo(lastX, zeroY)
            mFillPath.lineTo(firstX, zeroY)
            mFillPath.close()

            canvas.drawPath(mFillPath, mFillPaint)
            canvas.drawPath(mPath, mCurvePaint)
        }

        canvas.restoreToCount(saveCount)
        drawEdgeFade(canvas)
    }

    private fun drawEdgeFade(canvas: Canvas) {
        val fadeWidth = 7f * mDensity
        if (mPlotWidth <= fadeWidth * 2) return

        val bgColor = if (mIsDarkMode) Color.argb(255, 30, 30, 32) else Color.WHITE
        val transparent = Color.argb(0, Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor))

        mFadePaint.shader = LinearGradient(
            mPlotLeft, 0f, mPlotLeft + fadeWidth, 0f,
            intArrayOf(bgColor, transparent), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(mPlotLeft, mPlotTop, mPlotLeft + fadeWidth, mPlotTop + mPlotHeight, mFadePaint)

        mFadePaint.shader = LinearGradient(
            mPlotLeft + mPlotWidth - fadeWidth, 0f, mPlotLeft + mPlotWidth, 0f,
            intArrayOf(transparent, bgColor), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(mPlotLeft + mPlotWidth - fadeWidth, mPlotTop, mPlotLeft + mPlotWidth, mPlotTop + mPlotHeight, mFadePaint)
    }

    private fun formatDbLabel(db: Float): String {
        val dbInt = db.roundToInt()
        return if (dbInt > 0) "+$dbInt" else "$dbInt"
    }

    private fun computeDbStep(): Float {
        val range = mMaxDb - mMinDb
        return when {
            range <= 12f -> 3f
            range <= 24f -> 3f
            range <= 48f -> 6f
            else -> 12f
        }
    }

    private fun projectX(frequency: Double): Float {
        val logMin = log10(MIN_FREQ)
        val logMax = log10(MAX_FREQ)
        val logF = log10(frequency.coerceIn(MIN_FREQ, MAX_FREQ))
        return ((logF - logMin) / (logMax - logMin)).toFloat()
    }

    private fun projectY(db: Float): Float {
        val range = mMaxDb - mMinDb
        if (range <= 0f) return 0.5f
        val pos = (db - mMinDb) / range
        return 1.0f - pos
    }

    companion object {
        private const val MIN_FREQ = 20.0
        private const val MAX_FREQ = 20000.0
    }
}
