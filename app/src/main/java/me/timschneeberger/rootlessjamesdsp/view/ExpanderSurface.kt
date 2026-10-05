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
import kotlin.math.roundToInt

/**
 * Bar-chart visualization for harmonic / sub-harmonic expander gain values.
 * Each bar represents the gain (0–100 %) of one harmonic or sub-harmonic.
 * The crossover frequency and output mix are shown as info text.
 */
class ExpanderSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val isSubHarmonic: Boolean = false
) : View(context, attrs) {

    private val mBarPaint = Paint()
    private val mBarFillPaint = Paint()
    private val mGridLinePaint = Paint()
    private val mGridLineZeroPaint = Paint()
    private val mLabelPaint = Paint()
    private val mValuePaint = Paint()
    private val mInfoPaint = Paint()
    private val mBgPaint = Paint()

    private var mViewWidth = 0f
    private var mViewHeight = 0f
    private var mDensity = 1f
    private var mIsDarkMode = false

    private val padLeftProp = 16f / 800f
    private val padRightProp = 16f / 800f
    private val padTopProp = 18f / 200f
    private val padBottomProp = 34f / 200f

    private var mPlotLeft = 0f
    private var mPlotTop = 0f
    private var mPlotWidth = 0f
    private var mPlotHeight = 0f

    private val numBars = 10
    private val mGains = FloatArray(numBars) { 0f }
    private var mCrossoverHz = 2000f
    private var mMixPercent = 50f
    private var mEnabled = false
    private var mUpperLimit = 100f  // Y-axis upper bound, expands when THD > 100%

    private val mPath = Path()
    private val mClipRect = RectF()
    private val mBgRect = RectF()

    private val mAccentColor: Int

    /** Labels under each bar */
    private val barLabels: Array<String>
    private val barSubLabels: Array<String>

    init {
        mDensity = context.resources.displayMetrics.density
        mIsDarkMode = (context.resources.configuration.uiMode
            and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        mAccentColor = getColor(android.R.attr.colorAccent)

        if (isSubHarmonic) {
            barLabels = arrayOf("f/2", "f/3", "f/4", "f/5", "f/6", "f/7", "f/8", "f/9", "f/10", "THD")
            barSubLabels = arrayOf("2nd", "3rd", "4th", "5th", "6th", "7th", "8th", "9th", "10th", "Total")
        } else {
            barLabels = arrayOf("H2", "H3", "H4", "H5", "H6", "H7", "H8", "H9", "H10", "THD")
            barSubLabels = arrayOf("2nd", "3rd", "4th", "5th", "6th", "7th", "8th", "9th", "10th", "Total")
        }

        initPaints()
    }

    private fun initPaints() {
        val dark = mIsDarkMode
        val bgColor = if (dark) Color.argb(255, 30, 30, 32) else Color.WHITE
        mBgPaint.color = bgColor
        mBgPaint.style = Paint.Style.FILL
        mBgPaint.isAntiAlias = true

        val gridColor = if (dark) Color.argb(40, 200, 200, 200) else Color.argb(35, 51, 51, 51)
        mGridLinePaint.color = gridColor
        mGridLinePaint.style = Paint.Style.STROKE
        mGridLinePaint.strokeWidth = 0.5f * mDensity

        val gridZeroColor = if (dark) Color.argb(80, 200, 200, 200) else Color.argb(80, 85, 85, 85)
        mGridLineZeroPaint.color = gridZeroColor
        mGridLineZeroPaint.style = Paint.Style.STROKE
        mGridLineZeroPaint.strokeWidth = 1f * mDensity
        mGridLineZeroPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 5f), 0f)

        mBarPaint.color = mAccentColor
        mBarPaint.style = Paint.Style.STROKE
        mBarPaint.strokeWidth = 2f * mDensity
        mBarPaint.isAntiAlias = true

        mBarFillPaint.color = mAccentColor
        mBarFillPaint.style = Paint.Style.FILL
        mBarFillPaint.alpha = 60

        val textColor = if (dark) Color.argb(180, 220, 220, 220) else Color.argb(160, 51, 51, 51)
        mLabelPaint.textAlign = Paint.Align.CENTER
        mLabelPaint.textSize = sp(9f)
        mLabelPaint.color = textColor
        mLabelPaint.isAntiAlias = true

        mValuePaint.textAlign = Paint.Align.CENTER
        mValuePaint.textSize = sp(8f)
        mValuePaint.color = mAccentColor
        mValuePaint.isAntiAlias = true

        mInfoPaint.textAlign = Paint.Align.LEFT
        mInfoPaint.textSize = sp(9f)
        mInfoPaint.color = textColor
        mInfoPaint.isAntiAlias = true
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

    fun setGains(gains: FloatArray, crossoverHz: Float, mixPercent: Float, enabled: Boolean) {
        for (i in 0 until minOf(9, gains.size)) {
            mGains[i] = gains[i]
        }
        // Compute THD (Total Harmonic Distortion) as RMS sum of all harmonic gains.
        // Standard formula: THD = sqrt(sum(h_i^2)) — expressed in %.
        // Can exceed 100% when multiple harmonics are active simultaneously.
        var sumSquares = 0.0
        for (i in 0 until 9) {
            sumSquares += mGains[i].toDouble() * mGains[i].toDouble()
        }
        mGains[9] = kotlin.math.sqrt(sumSquares).toFloat()

        // Auto-scale Y-axis: shrink to max value when THD < 100%, expand when > 100%
        val maxVal = (0 until numBars).maxOf { mGains[it] }
        mUpperLimit = when {
            maxVal <= 0f -> 100f  // nothing to show, keep default
            maxVal <= 10f -> (kotlin.math.ceil(maxVal / 5.0) * 5.0).toFloat().coerceAtLeast(5f)
            maxVal <= 50f -> (kotlin.math.ceil(maxVal / 10.0) * 10.0).toFloat()
            maxVal <= 100f -> (kotlin.math.ceil(maxVal / 25.0) * 25.0).toFloat()
            else -> (kotlin.math.ceil(maxVal / 50.0) * 50.0).toFloat()
        }

        mCrossoverHz = crossoverHz
        mMixPercent = mixPercent
        mEnabled = enabled
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cornerRadius = 4f / 800f * mViewWidth
        mBgRect.set(0f, 0f, mViewWidth, mViewHeight)
        canvas.drawRoundRect(mBgRect, cornerRadius, cornerRadius, mBgPaint)

        if (!mEnabled) return

        // Horizontal grid lines at 25%, 50%, 75%, 100% of upper limit
        val pctSteps = floatArrayOf(0.25f, 0.50f, 0.75f, 1.00f)
        for (pct in pctSteps) {
            val y = mPlotTop + mPlotHeight * (1f - pct)
            canvas.drawLine(mPlotLeft, y, mPlotLeft + mPlotWidth, y, mGridLinePaint)
        }
        // Zero line (baseline)
        val zeroY = mPlotTop + mPlotHeight
        canvas.drawLine(mPlotLeft, zeroY, mPlotLeft + mPlotWidth, zeroY, mGridLineZeroPaint)

        // Y-axis labels (scaled to upper limit)
        mValuePaint.textAlign = Paint.Align.RIGHT
        mValuePaint.color = if (mIsDarkMode) Color.argb(120, 200, 200, 200) else Color.argb(120, 85, 85, 85)
        for (pct in pctSteps) {
            val y = mPlotTop + mPlotHeight * (1f - pct)
            val labelValue = (pct * mUpperLimit).roundToInt()
            val label = "$labelValue%"
            val labelY = y - (mValuePaint.descent() + mValuePaint.ascent()) / 2f
            canvas.drawText(label, mPlotLeft - 3f * mDensity, labelY, mValuePaint)
        }
        mValuePaint.textAlign = Paint.Align.CENTER
        mValuePaint.color = mAccentColor

        // Draw bars
        mClipRect.set(mPlotLeft, mPlotTop, mPlotLeft + mPlotWidth, mPlotTop + mPlotHeight)
        val saveCount = canvas.save()
        canvas.clipRect(mClipRect)

        val barSlotWidth = mPlotWidth / numBars
        val barWidth = barSlotWidth * 0.6f
        val barGap = barSlotWidth * 0.4f

        for (i in 0 until numBars) {
            val gain = mGains[i].coerceIn(0f, 9999f)
            // Scale bar height to mUpperLimit (THD can exceed 100%)
            val barHeight = (gain / mUpperLimit).coerceIn(0f, 1f) * mPlotHeight
            val barCenterX = mPlotLeft + barSlotWidth * (i + 0.5f)
            val barLeft = barCenterX - barWidth / 2f
            val barTop = zeroY - barHeight
            val barRight = barCenterX + barWidth / 2f

            // THD bar (index 9) uses a distinct color
            val isThdBar = (i == 9)
            val barColor = if (isThdBar) {
                if (mIsDarkMode) Color.argb(255, 255, 180, 60) else Color.argb(255, 200, 120, 0)
            } else {
                mAccentColor
            }

            // Gradient fill
            mBarFillPaint.shader = LinearGradient(
                0f, barTop, 0f, zeroY,
                intArrayOf(barColor, adjustAlpha(barColor, 30)),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )

            // Rounded rect bar
            val rectF = RectF(barLeft, barTop, barRight, zeroY)
            val r = barWidth * 0.15f
            canvas.drawRoundRect(rectF, r, r, mBarFillPaint)

            // Outline THD bar with its color, normal bars with accent
            val savedColor = mBarPaint.color
            if (isThdBar) mBarPaint.color = barColor
            canvas.drawRoundRect(rectF, r, r, mBarPaint)
            if (isThdBar) mBarPaint.color = savedColor

            // Value text above bar
            if (gain > 0.5f) {
                val valueText = if (isThdBar) {
                    "${"%.1f".format(gain)}%"
                } else {
                    "${gain.roundToInt()}%"
                }
                val savedValueColor = mValuePaint.color
                if (isThdBar) mValuePaint.color = barColor
                val textY = barTop - 2f * mDensity - mValuePaint.descent()
                if (textY > mPlotTop + mValuePaint.textSize) {
                    canvas.drawText(valueText, barCenterX, textY, mValuePaint)
                }
                if (isThdBar) mValuePaint.color = savedValueColor
            }
        }

        canvas.restoreToCount(saveCount)

        // Bar labels (harmonic / sub-harmonic names)
        for (i in 0 until numBars) {
            val barCenterX = mPlotLeft + barSlotWidth * (i + 0.5f)
            val labelY = zeroY + mLabelPaint.textSize + 4f * mDensity
            canvas.drawText(barLabels[i], barCenterX, labelY, mLabelPaint)
        }

        // Info text: crossover, mix, and THD
        val title = if (isSubHarmonic) "Sub-Harmonic" else "Harmonic"
        val crossoverText = if (mCrossoverHz >= 1000f) {
            "${"%.1f".format(mCrossoverHz / 1000f)} kHz"
        } else {
            "${mCrossoverHz.roundToInt()} Hz"
        }
        val thdText = "THD: ${"%.1f".format(mGains[9])}%"
        val infoText = "$title  ·  Xover: $crossoverText  ·  Mix: ${mMixPercent.roundToInt()}%  ·  $thdText"
        canvas.drawText(infoText, mPlotLeft, mPlotTop - 4f * mDensity + mInfoPaint.textSize, mInfoPaint)
    }

    private fun adjustAlpha(color: Int, alpha: Int): Int {
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }
}
