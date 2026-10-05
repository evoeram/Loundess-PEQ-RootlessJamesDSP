package me.timschneeberger.rootlessjamesdsp.view

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.withStyledAttributes
import androidx.core.os.bundleOf
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBandList
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqChannel
import me.timschneeberger.rootlessjamesdsp.utils.ParametricEqResponseCalculator
import me.timschneeberger.rootlessjamesdsp.utils.extensions.CompatExtensions.getParcelableAs
import kotlin.math.*

class ParametricEqSurface(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private val mGraphBackground = Paint()
    private val mGridLineMajor = Paint()
    private val mGridLineMinor = Paint()
    private val mGridLineZeroDb = Paint()
    private val mFreqLabelPaint = Paint()
    private val mDbLabelPaint = Paint()
    private val mAxisLabelText = Paint()
    private val mLegendTextPaint = Paint()
    private val mCurveLPaint = Paint()
    private val mCurveRPaint = Paint()
    private val mFillLPaint = Paint()
    private val mFillRPaint = Paint()
    private val mClipFillPaint = Paint()
    private val mFadePaint = Paint()

    private val mMeasurementPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
        color = Color.parseColor("#4CAF50")
        alpha = 200
        pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f)
    }
    private val mMeasurementRPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
        color = Color.parseColor("#2196F3")
        alpha = 200
        pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f)
    }
    private val mTargetPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        isAntiAlias = true
        color = Color.parseColor("#FF9800")
        alpha = 180
    }
    private val mCorrectedPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        isAntiAlias = true
        color = Color.parseColor("#9C27B0")
        alpha = 220
    }

    private var mViewWidth = 0f
    private var mViewHeight = 0f
    private var mDensity = 1f
    private var mIsDarkMode = false

    private val padLeftProp = 16f / 800f
    private val padRightProp = 38f / 800f
    private val padTopProp = 26f / 346f
    private val padBottomProp = 36f / 346f

    private var mPlotLeft = 0f
    private var mPlotTop = 0f
    private var mPlotWidth = 0f
    private var mPlotHeight = 0f

    private val crinXvals = intArrayOf(2, 3, 4, 5, 6, 8, 10, 15)
    private val tickPattern = intArrayOf(3, 0, 0, 1, 0, 0, 2, 0)
    private val tickThicknessBase = floatArrayOf(0.2f, 0.4f, 0.4f, 0.9f, 1.5f)

    private data class XTick(val freq: Double, val type: Int, val label: String?)
    private val xTicks = mutableListOf<XTick>()

    private var mFrequencies = FloatArray(0)
    private var mLeftResponseDb = FloatArray(0)
    private var mRightResponseDb = FloatArray(0)
    private var mPreampDb = 0f
    @Suppress("unused")
    private var mChannelsDiffer = false

    private var mMaxDb = 3f
    private var mMinDb = -3f

    private var mIsClipping = false
    var onClippingChanged: ((Boolean) -> Unit)? = null

    private var mMeasurementFreqs = FloatArray(0)
    private var mMeasurementSpl = FloatArray(0)
    private var mHasMeasurement = false

    private var mMeasurementRFreqs = FloatArray(0)
    private var mMeasurementRSpl = FloatArray(0)
    private var mHasMeasurementR = false

    private var mTargetFreqs = FloatArray(0)
    private var mTargetSpl = FloatArray(0)
    private var mHasTarget = false

    private var mCorrectedFreqs = FloatArray(0)
    private var mCorrectedSpl = FloatArray(0)
    private var mHasCorrected = false

    private val calculator = ParametricEqResponseCalculator()
    private var mHasBands = false

    private val mLeftColor: Int
    private val mRightColor: Int

    init {
        mDensity = context?.resources?.displayMetrics?.density ?: 1f
        mIsDarkMode = (context?.resources?.configuration?.uiMode
            ?: Configuration.UI_MODE_NIGHT_NO) and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

        val accentColor = getColor(android.R.attr.colorAccent)
        mLeftColor = accentColor
        mRightColor = shiftHue(accentColor, 140f)

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
        // Remove duplicate edge ticks (20 Hz = 2×10¹, 20000 Hz = 2×10⁴)
        // and replace with clean boundary labels
        xTicks.removeAll { it.freq == 20.0 || it.freq == 20000.0 }
        xTicks.add(0, XTick(20.0, 4, "20"))
        xTicks.add(XTick(20000.0, 4, "20k"))
    }

    private fun getTickType(i: Int): Int {
        if (i == 0 || i == 3 * 8) return 4
        return tickPattern[i % 8]
    }

    private fun formatXLabel(f: Double): String {
        return when {
            f >= 1000.0 -> "${(f / 1000.0).toInt()} kHz"
            else -> "${f.toInt()} Hz"
        }
    }

    private fun initPaints() {
        val dark = mIsDarkMode
        val bgColor = if (dark) Color.argb(255, 30, 30, 32) else Color.WHITE
        mGraphBackground.color = bgColor
        mGraphBackground.style = Paint.Style.FILL
        mGraphBackground.isAntiAlias = true

        val gridMinorColor = if (dark) Color.argb(28, 200, 200, 200) else Color.argb(22, 51, 51, 51)
        val gridMajorColor = if (dark) Color.argb(55, 200, 200, 200) else Color.argb(45, 51, 51, 51)
        val gridZeroColor = if (dark) Color.argb(100, 220, 220, 220) else Color.argb(100, 85, 85, 85)

        mGridLineMinor.color = gridMinorColor
        mGridLineMinor.style = Paint.Style.STROKE
        mGridLineMinor.strokeWidth = 0.5f * mDensity

        mGridLineMajor.color = gridMajorColor
        mGridLineMajor.style = Paint.Style.STROKE
        mGridLineMajor.strokeWidth = 1f * mDensity

        mGridLineZeroDb.color = gridZeroColor
        mGridLineZeroDb.style = Paint.Style.STROKE
        mGridLineZeroDb.strokeWidth = 1.5f * mDensity
        mGridLineZeroDb.pathEffect = DashPathEffect(floatArrayOf(14f, 7f), 0f)

        val textColor = if (dark) Color.argb(180, 220, 220, 220) else Color.argb(160, 51, 51, 51)

        mFreqLabelPaint.textAlign = Paint.Align.CENTER
        mFreqLabelPaint.textSize = sp(9f)
        mFreqLabelPaint.color = textColor
        mFreqLabelPaint.isAntiAlias = true

        mDbLabelPaint.textAlign = Paint.Align.RIGHT
        mDbLabelPaint.textSize = sp(9f)
        mDbLabelPaint.color = textColor
        mDbLabelPaint.isAntiAlias = true

        mAxisLabelText.textSize = sp(9f)
        mAxisLabelText.color = textColor
        mAxisLabelText.isAntiAlias = true

        mLegendTextPaint.textAlign = Paint.Align.LEFT
        mLegendTextPaint.textSize = sp(10f)
        mLegendTextPaint.isAntiAlias = true
        mLegendTextPaint.isFakeBoldText = true

        mCurveLPaint.color = mLeftColor
        mCurveLPaint.style = Paint.Style.STROKE
        mCurveLPaint.strokeWidth = 2f * mDensity
        mCurveLPaint.isAntiAlias = true
        mCurveLPaint.strokeCap = Paint.Cap.ROUND
        mCurveLPaint.strokeJoin = Paint.Join.ROUND

        mCurveRPaint.color = mRightColor
        mCurveRPaint.style = Paint.Style.STROKE
        mCurveRPaint.strokeWidth = 2f * mDensity
        mCurveRPaint.isAntiAlias = true
        mCurveRPaint.strokeCap = Paint.Cap.ROUND
        mCurveRPaint.strokeJoin = Paint.Join.ROUND

        mFillLPaint.color = mLeftColor
        mFillLPaint.style = Paint.Style.FILL
        mFillLPaint.alpha = 22

        mFillRPaint.color = mRightColor
        mFillRPaint.style = Paint.Style.FILL
        mFillRPaint.alpha = 22

        mClipFillPaint.color = Color.parseColor("#FF4444")
        mClipFillPaint.style = Paint.Style.FILL
        mClipFillPaint.alpha = 38
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

    private fun shiftHue(color: Int, degrees: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[0] = (hsv[0] + degrees) % 360f
        if (hsv[0] < 0f) hsv[0] += 360f
        return Color.HSVToColor(Color.alpha(color), hsv)
    }

    override fun onSaveInstanceState() = bundleOf(
        "super" to super.onSaveInstanceState(),
        STATE_FREQ to mFrequencies,
        STATE_LEFT to mLeftResponseDb,
        STATE_RIGHT to mRightResponseDb,
        STATE_PREAMP to mPreampDb,
        STATE_DIFFER to mChannelsDiffer
    )

    override fun onRestoreInstanceState(state: Parcelable?) {
        super.onRestoreInstanceState((state as Bundle).getParcelableAs("super"))
        mFrequencies = state.getFloatArray(STATE_FREQ) ?: FloatArray(0)
        mLeftResponseDb = state.getFloatArray(STATE_LEFT) ?: FloatArray(0)
        mRightResponseDb = state.getFloatArray(STATE_RIGHT) ?: FloatArray(0)
        mPreampDb = state.getFloat(STATE_PREAMP, 0f)
        mChannelsDiffer = state.getBoolean(STATE_DIFFER, false)
        updateDbRange()
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

    private val mPathL = Path()
    private val mPathR = Path()
    private val mFillPathL = Path()
    private val mFillPathR = Path()
    private val mClipPathL = Path()
    private val mClipPathR = Path()
    private val mClipRect = RectF()
    private val mBgRect = RectF()

    override fun onDraw(canvas: Canvas) {
        mPathL.rewind()
        mPathR.rewind()
        mFillPathL.rewind()
        mFillPathR.rewind()
        mClipPathL.rewind()
        mClipPathR.rewind()

        val preamp = mPreampDb
        val zeroY = mPlotTop + projectY(0f) * mPlotHeight

        val cornerRadius = 4f / 800f * mViewWidth
        mBgRect.set(0f, 0f, mViewWidth, mViewHeight)
        canvas.drawRoundRect(mBgRect, cornerRadius, cornerRadius, mGraphBackground)

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
                canvas.drawText(dbLabel, mViewWidth - 4f * mDensity, labelY, mDbLabelPaint)
            }
            db += step
        }

        canvas.save()
        val dbAxisX = mPlotLeft + mPlotWidth + (mViewWidth - mPlotLeft - mPlotWidth) / 2f
        val dbAxisY = mPlotTop + mPlotHeight / 2f
        canvas.rotate(-90f, dbAxisX, dbAxisY)
        mAxisLabelText.textAlign = Paint.Align.CENTER
        canvas.drawText("dB", dbAxisX, dbAxisY, mAxisLabelText)
        canvas.restore()

        for ((index, tick) in xTicks.withIndex()) {
            val x = mPlotLeft + projectX(tick.freq) * mPlotWidth
            val thickness = tickThicknessBase[tick.type] * mDensity

            mGridLineMinor.strokeWidth = thickness
            canvas.drawLine(x, mPlotTop, x, mPlotTop + mPlotHeight, mGridLineMinor)

            if (tick.label != null) {
                val freqLabelY = mPlotTop + mPlotHeight + mPlotHeight * 0.1f
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

        mClipRect.set(mPlotLeft, mPlotTop, mPlotLeft + mPlotWidth, mPlotTop + mPlotHeight)
        val saveCount = canvas.save()
        canvas.clipRect(mClipRect)

        if (mHasMeasurement && mMeasurementFreqs.isNotEmpty()) {
            canvas.drawPath(buildOverlayPath(mMeasurementFreqs, mMeasurementSpl), mMeasurementPaint)
        }

        if (mHasMeasurementR && mMeasurementRFreqs.isNotEmpty()) {
            canvas.drawPath(buildOverlayPath(mMeasurementRFreqs, mMeasurementRSpl), mMeasurementRPaint)
        }

        if (mHasTarget && mTargetFreqs.isNotEmpty()) {
            canvas.drawPath(buildOverlayPath(mTargetFreqs, mTargetSpl), mTargetPaint)
        }

        if (mHasCorrected && mCorrectedFreqs.isNotEmpty()) {
            canvas.drawPath(buildOverlayPath(mCorrectedFreqs, mCorrectedSpl), mCorrectedPaint)
        }

        if (mFrequencies.isNotEmpty() && mHasBands) {
            buildCurvePath(mPathL, mFrequencies, mLeftResponseDb, preamp)
            buildCurvePath(mPathR, mFrequencies, mRightResponseDb, preamp)

            if (mLeftResponseDb.isNotEmpty()) {
                buildFillPath(mFillPathL, mPathL, mFrequencies, zeroY)
                canvas.drawPath(mFillPathL, mFillLPaint)
            }
            if (mRightResponseDb.isNotEmpty()) {
                buildFillPath(mFillPathR, mPathR, mFrequencies, zeroY)
                canvas.drawPath(mFillPathR, mFillRPaint)
            }

            if (mIsClipping) {
                if (mLeftResponseDb.isNotEmpty()) {
                    buildClipFillPath(mClipPathL, mFrequencies, mLeftResponseDb, preamp, zeroY)
                    canvas.drawPath(mClipPathL, mClipFillPaint)
                }
                if (mRightResponseDb.isNotEmpty()) {
                    buildClipFillPath(mClipPathR, mFrequencies, mRightResponseDb, preamp, zeroY)
                    canvas.drawPath(mClipPathR, mClipFillPaint)
                }
            }

            if (mRightResponseDb.isNotEmpty()) canvas.drawPath(mPathR, mCurveRPaint)
            if (mLeftResponseDb.isNotEmpty()) canvas.drawPath(mPathL, mCurveLPaint)
        }

        canvas.restoreToCount(saveCount)

        drawLegend(canvas)
    }

    private fun buildCurvePath(path: Path, freqs: FloatArray, response: FloatArray, preamp: Float) {
        if (freqs.isEmpty() || response.isEmpty()) return
        path.moveTo(
            mPlotLeft + projectX(freqs[0].toDouble()) * mPlotWidth,
            mPlotTop + projectY(response[0] + preamp) * mPlotHeight
        )
        for (i in 1 until freqs.size) {
            path.lineTo(
                mPlotLeft + projectX(freqs[i].toDouble()) * mPlotWidth,
                mPlotTop + projectY(response[i] + preamp) * mPlotHeight
            )
        }
    }

    private fun buildOverlayPath(freqs: FloatArray, spl: FloatArray): Path {
        val path = Path()
        if (freqs.isEmpty()) return path
        path.moveTo(
            mPlotLeft + projectX(freqs[0].toDouble()) * mPlotWidth,
            mPlotTop + projectY(spl[0]) * mPlotHeight
        )
        for (i in 1 until freqs.size) {
            path.lineTo(
                mPlotLeft + projectX(freqs[i].toDouble()) * mPlotWidth,
                mPlotTop + projectY(spl[i]) * mPlotHeight
            )
        }
        return path
    }

    private fun buildFillPath(fillPath: Path, curvePath: Path, freqs: FloatArray, zeroY: Float) {
        fillPath.addPath(curvePath)
        val lastX = mPlotLeft + projectX(freqs.last().toDouble()) * mPlotWidth
        val firstX = mPlotLeft + projectX(freqs.first().toDouble()) * mPlotWidth
        fillPath.lineTo(lastX, zeroY)
        fillPath.lineTo(firstX, zeroY)
        fillPath.close()
    }

    private fun buildClipFillPath(
        clipPath: Path, freqs: FloatArray, response: FloatArray,
        preamp: Float, zeroY: Float
    ) {
        var inClip = false
        for (i in freqs.indices) {
            val x = mPlotLeft + projectX(freqs[i].toDouble()) * mPlotWidth
            val y = mPlotTop + projectY(response[i] + preamp) * mPlotHeight
            val aboveZero = response[i] + preamp > 0f

            if (aboveZero && !inClip) {
                clipPath.moveTo(x, zeroY)
                clipPath.lineTo(x, y)
                inClip = true
            } else if (aboveZero && inClip) {
                clipPath.lineTo(x, y)
            } else if (!aboveZero && inClip) {
                clipPath.lineTo(x, zeroY)
                clipPath.close()
                inClip = false
            }
        }
        if (inClip) {
            val lastX = mPlotLeft + projectX(freqs.last().toDouble()) * mPlotWidth
            clipPath.lineTo(lastX, zeroY)
            clipPath.close()
        }
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

    private fun drawLegend(canvas: Canvas) {
        val dotRadius = 4.5f * mDensity
        val textH = mLegendTextPaint.textSize
        val legendY = mPlotTop * 0.45f + textH * 0.6f
        val labelGap = 8f * mDensity
        val itemGap = 20f * mDensity
        var x = mPlotLeft

        if (mHasBands) {
            mLegendTextPaint.color = mLeftColor
            canvas.drawCircle(x + dotRadius, legendY - dotRadius * 0.5f, dotRadius, mLegendTextPaint)
            canvas.drawText("L", x + dotRadius * 2 + labelGap, legendY, mLegendTextPaint)
            x += dotRadius * 2 + labelGap + mLegendTextPaint.measureText("L") + itemGap

            mLegendTextPaint.color = mRightColor
            canvas.drawCircle(x + dotRadius, legendY - dotRadius * 0.5f, dotRadius, mLegendTextPaint)
            canvas.drawText("R", x + dotRadius * 2 + labelGap, legendY, mLegendTextPaint)
            x += dotRadius * 2 + labelGap + mLegendTextPaint.measureText("R") + itemGap
        }

        if (mHasMeasurement) {
            mLegendTextPaint.color = mMeasurementPaint.color
            canvas.drawCircle(x + dotRadius, legendY - dotRadius * 0.5f, dotRadius, mLegendTextPaint)
            canvas.drawText("Meas L", x + dotRadius * 2 + labelGap, legendY, mLegendTextPaint)
            x += dotRadius * 2 + labelGap + mLegendTextPaint.measureText("Meas L") + itemGap
        }

        if (mHasMeasurementR) {
            mLegendTextPaint.color = mMeasurementRPaint.color
            canvas.drawCircle(x + dotRadius, legendY - dotRadius * 0.5f, dotRadius, mLegendTextPaint)
            canvas.drawText("Meas R", x + dotRadius * 2 + labelGap, legendY, mLegendTextPaint)
            x += dotRadius * 2 + labelGap + mLegendTextPaint.measureText("Meas R") + itemGap
        }

        if (mHasTarget) {
            mLegendTextPaint.color = mTargetPaint.color
            canvas.drawCircle(x + dotRadius, legendY - dotRadius * 0.5f, dotRadius, mLegendTextPaint)
            canvas.drawText("Target", x + dotRadius * 2 + labelGap, legendY, mLegendTextPaint)
            x += dotRadius * 2 + labelGap + mLegendTextPaint.measureText("Target") + itemGap
        }

        if (mHasCorrected) {
            mLegendTextPaint.color = mCorrectedPaint.color
            canvas.drawCircle(x + dotRadius, legendY - dotRadius * 0.5f, dotRadius, mLegendTextPaint)
            canvas.drawText("Corrected", x + dotRadius * 2 + labelGap, legendY, mLegendTextPaint)
        }
    }

    private fun formatDbLabel(db: Float): String {
        val dbInt = db.roundToInt()
        return if (dbInt > 0) "+$dbInt" else "$dbInt"
    }

    fun setBands(bands: ParametricEqBandList, preampDb: Double = mPreampDb.toDouble()) {
        mPreampDb = preampDb.toFloat()
        mChannelsDiffer = bands.any { it.channel != ParametricEqChannel.LEFT_RIGHT }
        mHasBands = bands.isNotEmpty()

        val response = calculator.compute(bands.toList(), preampDb)
        mFrequencies = response.frequencies.map { it.toFloat() }.toFloatArray()
        mLeftResponseDb = response.leftResponseDb.map { it.toFloat() }.toFloatArray()
        mRightResponseDb = response.rightResponseDb.map { it.toFloat() }.toFloatArray()

        updateDbRangeWithMeasurement()
        postInvalidate()
    }

    fun setPreampDb(preampDb: Double) {
        mPreampDb = preampDb.toFloat()
        updateDbRangeWithMeasurement()
        postInvalidate()
    }

    fun setMeasurementData(freqs: FloatArray, spl: FloatArray) {
        require(freqs.size == spl.size) { "freqs and spl must have same size" }
        mMeasurementFreqs = freqs
        mMeasurementSpl = spl
        mHasMeasurement = freqs.isNotEmpty()
        updateDbRangeWithMeasurement()
        postInvalidate()
    }

    fun clearMeasurementData() {
        mHasMeasurement = false
        mMeasurementFreqs = FloatArray(0)
        mMeasurementSpl = FloatArray(0)
        updateDbRangeWithMeasurement()
        postInvalidate()
    }

    fun setMeasurementDataR(freqs: FloatArray, spl: FloatArray) {
        require(freqs.size == spl.size) { "freqs and spl must have same size" }
        mMeasurementRFreqs = freqs
        mMeasurementRSpl = spl
        mHasMeasurementR = freqs.isNotEmpty()
        updateDbRangeWithMeasurement()
        postInvalidate()
    }

    fun clearMeasurementDataR() {
        mHasMeasurementR = false
        mMeasurementRFreqs = FloatArray(0)
        mMeasurementRSpl = FloatArray(0)
        updateDbRangeWithMeasurement()
        postInvalidate()
    }

    fun setTargetCurve(freqs: FloatArray, targetDb: FloatArray) {
        require(freqs.size == targetDb.size) { "freqs and targetDb must have same size" }
        mTargetFreqs = freqs
        mTargetSpl = targetDb
        mHasTarget = freqs.isNotEmpty()
        updateDbRangeWithMeasurement()
        postInvalidate()
    }

    fun clearTargetCurve() {
        mHasTarget = false
        mTargetFreqs = FloatArray(0)
        mTargetSpl = FloatArray(0)
        updateDbRangeWithMeasurement()
        postInvalidate()
    }

    fun setCorrectedFR(freqs: FloatArray, spl: FloatArray) {
        require(freqs.size == spl.size) { "freqs and spl must have same size" }
        mCorrectedFreqs = freqs
        mCorrectedSpl = spl
        mHasCorrected = freqs.isNotEmpty()
        updateDbRangeWithMeasurement()
        postInvalidate()
    }

    fun clearCorrectedFR() {
        mHasCorrected = false
        mCorrectedFreqs = FloatArray(0)
        mCorrectedSpl = FloatArray(0)
        updateDbRangeWithMeasurement()
        postInvalidate()
    }

    private fun updateDbRangeWithMeasurement() {
        val allValues = mutableListOf<Float>()
        val preamp = mPreampDb
        for (v in mLeftResponseDb) allValues.add(v + preamp)
        for (v in mRightResponseDb) allValues.add(v + preamp)
        if (mHasMeasurement && mMeasurementSpl.isNotEmpty()) for (v in mMeasurementSpl) allValues.add(v)
        if (mHasMeasurementR && mMeasurementRSpl.isNotEmpty()) for (v in mMeasurementRSpl) allValues.add(v)
        if (mHasTarget && mTargetSpl.isNotEmpty()) for (v in mTargetSpl) allValues.add(v)
        if (mHasCorrected && mCorrectedSpl.isNotEmpty()) for (v in mCorrectedSpl) allValues.add(v)

        if (allValues.isEmpty()) {
            mMaxDb = 3f; mMinDb = -3f; updateClipping(false); return
        }
        val maxVal = allValues.maxOrNull() ?: 0f
        val minVal = allValues.minOrNull() ?: 0f
        mMaxDb = ceil((maxVal + 3f) / 3f) * 3f
        if (mMaxDb < 3f) mMaxDb = 3f
        mMinDb = floor((minVal - 3f) / 3f) * 3f
        if (mMinDb > -3f) mMinDb = -3f
        val peqGains = (mLeftResponseDb.toList() + mRightResponseDb.toList()).map { it + preamp }
        val clipping = peqGains.any { it > 0.01f } || preamp > 0.01f
        updateClipping(clipping)
    }

    private fun updateDbRange() {
        val preamp = mPreampDb
        val allGains = (mLeftResponseDb.toList() + mRightResponseDb.toList()).map { it + preamp }
        if (allGains.isEmpty()) { mMaxDb = 3f; mMinDb = -3f; updateClipping(false); return }
        val maxVal = allGains.maxOrNull() ?: 0f
        val minVal = allGains.minOrNull() ?: 0f
        mMaxDb = ceil((maxVal + 3f) / 3f) * 3f
        if (mMaxDb < 3f) mMaxDb = 3f
        mMinDb = floor((minVal - 3f) / 3f) * 3f
        if (mMinDb > -3f) mMinDb = -3f
        val clipping = allGains.any { it > 0.01f } || preamp > 0.01f
        updateClipping(clipping)
    }

    private fun updateClipping(clipping: Boolean) {
        if (mIsClipping != clipping) {
            mIsClipping = clipping
            onClippingChanged?.invoke(clipping)
        }
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
        private const val STATE_FREQ = "peq_curve_freq"
        private const val STATE_LEFT = "peq_curve_left"
        private const val STATE_RIGHT = "peq_curve_right"
        private const val STATE_PREAMP = "peq_curve_preamp"
        private const val STATE_DIFFER = "peq_curve_differ"
        private const val MIN_FREQ = 20.0
        private const val MAX_FREQ = 20000.0
    }
}
