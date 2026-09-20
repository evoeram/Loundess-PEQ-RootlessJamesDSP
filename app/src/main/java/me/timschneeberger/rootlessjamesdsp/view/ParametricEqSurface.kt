package me.timschneeberger.rootlessjamesdsp.view

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.withStyledAttributes
import androidx.core.os.bundleOf
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBandList
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqChannelMode
import me.timschneeberger.rootlessjamesdsp.utils.ParametricEqResponseCalculator
import me.timschneeberger.rootlessjamesdsp.utils.extensions.CompatExtensions.getParcelableAs
import kotlin.math.*

/**
 * Preview graph for the Parametric EQ showing independent L and R frequency-response curves.
 *
 * Features:
 *  - Y-axis dB labels ("+3 dB", "0 dB", "-3 dB", …) on the left
 *  - X-axis frequency labels ("20 Hz", "1 kHz", "20 kHz", …) on the bottom
 *  - Adaptive Y-axis range: top = max(L,R) + 3 dB, bottom = min(L,R) - 3 dB
 *  - 0 dB reference line (dashed)
 *  - Red warning fill when curves exceed 0 dB (clipping indicator)
 *  - All paddings computed dynamically from measured text widths — no clipping
 *
 * Layout:
 *   ┌─────────────────────────────────────────────────────┐
 *   │  PAD_TOP (legend ● L  ● R)                          │
 *   │         ┌──────────────────────────────────────┐    │
 *   │  dB     │ PLOT AREA (grid, curves, fills)      │    │
 *   │  labels │                                      │ PAD_R
 *   │  PAD_L  │                                      │    │
 *   │         └──────────────────────────────────────┘    │
 *   │         20 Hz  50 Hz  ...  20 kHz  (freq labels)    │
 *   │  PAD_BOTTOM                                         │
 *   └─────────────────────────────────────────────────────┘
 */
class ParametricEqSurface(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    // Paints
    private val mGridLinePaint = Paint()
    private val mZeroDbLinePaint = Paint()
    private val mFreqLabelPaint = Paint()
    private val mDbLabelPaint = Paint()
    private val mLegendTextPaint = Paint()
    private val mCurveLPaint = Paint()
    private val mCurveRPaint = Paint()
    private val mFillLPaint = Paint()
    private val mFillRPaint = Paint()
    private val mClipFillPaint = Paint()

    // View dimensions
    private var mViewWidth = 0f
    private var mViewHeight = 0f

    // Plot area (computed dynamically in onLayout)
    private var mPadTop = 32f
    private var mPadBottom = 24f
    private var mPadLeft = 48f
    private var mPadRight = 24f
    private var mPlotLeft = 0f
    private var mPlotTop = 0f
    private var mPlotWidth = 0f
    private var mPlotHeight = 0f

    // Response data
    private var mFrequencies = FloatArray(0)
    private var mLeftResponseDb = FloatArray(0)
    private var mRightResponseDb = FloatArray(0)
    private var mPreampDb = 0f
    @Suppress("unused")
    private var mChannelsDiffer = false

    // Y-axis range (asymmetric)
    private var mMaxDb = 3f
    private var mMinDb = -3f

    // Clipping state
    private var mIsClipping = false

    /** Callback invoked when clipping state changes (curves exceed 0 dB). */
    var onClippingChanged: ((Boolean) -> Unit)? = null

    // ── Measurement data (опциональные слои) ──
    // Измеренная АЧХ (raw или calibrated SPL) — основной канал
    private var mMeasurementFreqs = FloatArray(0)
    private var mMeasurementSpl = FloatArray(0)
    private var mHasMeasurement = false

    // Второй канал измерения (для режима L/R: правый канал)
    private var mMeasurementRFreqs = FloatArray(0)
    private var mMeasurementRSpl = FloatArray(0)
    private var mHasMeasurementR = false

    // Целевая кривая (target curve)
    private var mTargetFreqs = FloatArray(0)
    private var mTargetSpl = FloatArray(0)
    private var mHasTarget = false

    // Paints для measurement и target слоёв
    private val mMeasurementPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
        color = Color.parseColor("#4CAF50") // зелёный
        alpha = 200
        pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f)
    }
    private val mMeasurementRPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
        color = Color.parseColor("#2196F3") // синий
        alpha = 200
        pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f)
    }
    private val mTargetPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        isAntiAlias = true
        color = Color.parseColor("#FF9800") // оранжевый
        alpha = 180
    }

    // Calculator
    private val calculator = ParametricEqResponseCalculator()

    // Флаг: есть ли PEQ-полосы (для скрытия L/R в легенде)
    private var mHasBands = false

    // Colors
    private val mLeftColor: Int
    private val mRightColor: Int

    init {
        val accentColor = getColor(android.R.attr.colorAccent)
        mLeftColor = accentColor
        mRightColor = shiftHue(accentColor, 140f)

        mGridLinePaint.color = getColor(android.R.attr.colorControlHighlight)
        mGridLinePaint.style = Paint.Style.STROKE
        mGridLinePaint.strokeWidth = 1.5f
        mGridLinePaint.alpha = 100

        mZeroDbLinePaint.color = getColor(android.R.attr.textColorSecondary)
        mZeroDbLinePaint.style = Paint.Style.STROKE
        mZeroDbLinePaint.strokeWidth = 2f
        mZeroDbLinePaint.alpha = 160
        mZeroDbLinePaint.pathEffect = DashPathEffect(floatArrayOf(14f, 7f), 0f)

        mFreqLabelPaint.textAlign = Paint.Align.CENTER
        mFreqLabelPaint.textSize = sp(9f)
        mFreqLabelPaint.color = getColor(android.R.attr.textColorSecondary)
        mFreqLabelPaint.isAntiAlias = true

        mDbLabelPaint.textAlign = Paint.Align.RIGHT
        mDbLabelPaint.textSize = sp(9f)
        mDbLabelPaint.color = getColor(android.R.attr.textColorSecondary)
        mDbLabelPaint.isAntiAlias = true

        mLegendTextPaint.textAlign = Paint.Align.LEFT
        mLegendTextPaint.textSize = sp(11f)
        mLegendTextPaint.isAntiAlias = true
        mLegendTextPaint.isFakeBoldText = true

        mCurveLPaint.color = mLeftColor
        mCurveLPaint.style = Paint.Style.STROKE
        mCurveLPaint.strokeWidth = 5f
        mCurveLPaint.isAntiAlias = true

        mCurveRPaint.color = mRightColor
        mCurveRPaint.style = Paint.Style.STROKE
        mCurveRPaint.strokeWidth = 5f
        mCurveRPaint.isAntiAlias = true

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

    // ── State save/restore ──────────────────────────────────────────

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

    // ── Layout ──────────────────────────────────────────────────────

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        mViewWidth = (right - left).toFloat()
        mViewHeight = (bottom - top).toFloat()

        // ── Dynamic paddings based on measured label text widths ──
        // Left: must fit dB labels ("+12 dB") + spacing.
        // First freq label is left-aligned to the plot edge, so it no longer
        // extends into this padding (avoids Hz/dB label conflict).
        val dbLabelW = mDbLabelPaint.measureText("+12 dB")
        mPadLeft = dbLabelW + 8f

        // Right: small margin. Last freq label is right-aligned to the plot
        // edge, so it no longer extends beyond the graph.
        mPadRight = 4f

        // Top: legend band
        mPadTop = 30f
        // Bottom: freq label band
        mPadBottom = sp(9f) + 12f

        mPlotLeft = mPadLeft
        mPlotTop = mPadTop
        mPlotWidth = (mViewWidth - mPadLeft - mPadRight).coerceAtLeast(0f)
        mPlotHeight = (mViewHeight - mPadTop - mPadBottom).coerceAtLeast(0f)
    }

    // ── Drawing ─────────────────────────────────────────────────────

    private val mPathL = Path()
    private val mPathR = Path()
    private val mFillPathL = Path()
    private val mFillPathR = Path()
    private val mClipPathL = Path()
    private val mClipPathR = Path()
    private val mClipRect = RectF()

    override fun onDraw(canvas: Canvas) {
        mPathL.rewind()
        mPathR.rewind()
        mFillPathL.rewind()
        mFillPathR.rewind()
        mClipPathL.rewind()
        mClipPathR.rewind()

        val preamp = mPreampDb
        val zeroY = mPlotTop + projectY(0f) * mPlotHeight

        // ── Grid: horizontal dB lines + Y-axis labels ──
        val step = computeDbStep()
        var db = floor(mMinDb / step) * step
        while (db <= mMaxDb + 0.01f) {
            val y = mPlotTop + projectY(db) * mPlotHeight
            if (abs(db) < 0.01f) {
                canvas.drawLine(mPlotLeft, y, mPlotLeft + mPlotWidth, y, mZeroDbLinePaint)
            } else {
                canvas.drawLine(mPlotLeft, y, mPlotLeft + mPlotWidth, y, mGridLinePaint)
            }
            // dB label on the left, vertically centered on the grid line
            val dbLabel = formatDbLabel(db)
            val labelY = y - (mDbLabelPaint.descent() + mDbLabelPaint.ascent()) / 2f
            canvas.drawText(dbLabel, mPlotLeft - 6f, labelY, mDbLabelPaint)
            db += step
        }

        // ── Grid: vertical frequency markers + X-axis labels ──
        val freqLabels = computeFreqLabels()
        val freqLabelY = mPlotTop + mPlotHeight + mPadBottom * 0.72f
        val firstFreq = freqLabels.firstOrNull()
        val lastFreq = freqLabels.lastOrNull()
        for (f in freqLabels) {
            val x = mPlotLeft + projectX(f) * mPlotWidth
            canvas.drawLine(x, mPlotTop, x, mPlotTop + mPlotHeight, mGridLinePaint)
            val label = formatFreqLabel(f)
            // Align extreme labels to the plot edges so they don't overflow
            // into the dB-label zone (left) or off the graph (right).
            when (f) {
                firstFreq -> {
                    mFreqLabelPaint.textAlign = Paint.Align.LEFT
                    canvas.drawText(label, mPlotLeft, freqLabelY, mFreqLabelPaint)
                }
                lastFreq -> {
                    mFreqLabelPaint.textAlign = Paint.Align.RIGHT
                    canvas.drawText(label, mPlotLeft + mPlotWidth, freqLabelY, mFreqLabelPaint)
                }
                else -> {
                    mFreqLabelPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText(label, x, freqLabelY, mFreqLabelPaint)
                }
            }
        }
        mFreqLabelPaint.textAlign = Paint.Align.CENTER

        // ── Clip to plot area for curves and fills ──
        mClipRect.set(mPlotLeft, mPlotTop, mPlotLeft + mPlotWidth, mPlotTop + mPlotHeight)
        val saveCount = canvas.save()
        canvas.clipRect(mClipRect)

        // ── Measurement curve (зелёный пунктир, под filter curves) ──
        if (mHasMeasurement && mMeasurementFreqs.isNotEmpty()) {
            val measPath = Path()
            measPath.moveTo(
                mPlotLeft + projectX(mMeasurementFreqs[0].toDouble()) * mPlotWidth,
                mPlotTop + projectY(mMeasurementSpl[0]) * mPlotHeight
            )
            for (i in 1 until mMeasurementFreqs.size) {
                measPath.lineTo(
                    mPlotLeft + projectX(mMeasurementFreqs[i].toDouble()) * mPlotWidth,
                    mPlotTop + projectY(mMeasurementSpl[i]) * mPlotHeight
                )
            }
            canvas.drawPath(measPath, mMeasurementPaint)
        }

        // ── Measurement R curve (синий пунктир, второй канал L/R) ──
        if (mHasMeasurementR && mMeasurementRFreqs.isNotEmpty()) {
            val measRPath = Path()
            measRPath.moveTo(
                mPlotLeft + projectX(mMeasurementRFreqs[0].toDouble()) * mPlotWidth,
                mPlotTop + projectY(mMeasurementRSpl[0]) * mPlotHeight
            )
            for (i in 1 until mMeasurementRFreqs.size) {
                measRPath.lineTo(
                    mPlotLeft + projectX(mMeasurementRFreqs[i].toDouble()) * mPlotWidth,
                    mPlotTop + projectY(mMeasurementRSpl[i]) * mPlotHeight
                )
            }
            canvas.drawPath(measRPath, mMeasurementRPaint)
        }

        // ── Target curve (оранжевый, под filter curves) ──
        if (mHasTarget && mTargetFreqs.isNotEmpty()) {
            val targetPath = Path()
            targetPath.moveTo(
                mPlotLeft + projectX(mTargetFreqs[0].toDouble()) * mPlotWidth,
                mPlotTop + projectY(mTargetSpl[0]) * mPlotHeight
            )
            for (i in 1 until mTargetFreqs.size) {
                targetPath.lineTo(
                    mPlotLeft + projectX(mTargetFreqs[i].toDouble()) * mPlotWidth,
                    mPlotTop + projectY(mTargetSpl[i]) * mPlotHeight
                )
            }
            canvas.drawPath(targetPath, mTargetPaint)
        }

        if (mFrequencies.isNotEmpty() && mHasBands) {
            buildCurvePath(mPathL, mFrequencies, mLeftResponseDb, preamp)
            buildCurvePath(mPathR, mFrequencies, mRightResponseDb, preamp)

            // Subtle fill: from curve to 0 dB line
            if (mLeftResponseDb.isNotEmpty()) {
                buildFillPath(mFillPathL, mPathL, mFrequencies, zeroY)
                canvas.drawPath(mFillPathL, mFillLPaint)
            }
            if (mRightResponseDb.isNotEmpty()) {
                buildFillPath(mFillPathR, mPathR, mFrequencies, zeroY)
                canvas.drawPath(mFillPathR, mFillRPaint)
            }

            // Red clipping fill: area between curve and 0 dB WHERE curve > 0 dB
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

            // Curve strokes (R first, then L on top)
            if (mRightResponseDb.isNotEmpty()) {
                canvas.drawPath(mPathR, mCurveRPaint)
            }
            if (mLeftResponseDb.isNotEmpty()) {
                canvas.drawPath(mPathL, mCurveLPaint)
            }
        }

        canvas.restoreToCount(saveCount)

        // ── Legend (outside clip, in top padding band) ──
        drawLegend(canvas)
    }

    /** Build a curve Path from frequency/response arrays. */
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

    /** Build a fill path from the curve to the 0 dB line. */
    private fun buildFillPath(fillPath: Path, curvePath: Path, freqs: FloatArray, zeroY: Float) {
        fillPath.addPath(curvePath)
        val lastX = mPlotLeft + projectX(freqs.last().toDouble()) * mPlotWidth
        val firstX = mPlotLeft + projectX(freqs.first().toDouble()) * mPlotWidth
        fillPath.lineTo(lastX, zeroY)
        fillPath.lineTo(firstX, zeroY)
        fillPath.close()
    }

    /**
     * Build a red clipping fill path: only the segments where the curve exceeds 0 dB.
     * Fills between the curve and the 0 dB line for portions where curve > 0.
     */
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

    /** Draw legend with small colored dots + L/R labels in the top padding band. */
    private fun drawLegend(canvas: Canvas) {
        val dotRadius = 4f
        val textH = mLegendTextPaint.textSize
        val legendY = mPadTop * 0.5f + textH / 3f
        val labelGap = 6f
        val itemGap = 20f
        var x = mPlotLeft

        // L dot + label (только если есть PEQ-полосы)
        if (mHasBands) {
            mLegendTextPaint.color = mLeftColor
            canvas.drawCircle(x + dotRadius, legendY - dotRadius * 0.5f, dotRadius, mLegendTextPaint)
            canvas.drawText("L", x + dotRadius * 2 + labelGap, legendY, mLegendTextPaint)
            x += dotRadius * 2 + labelGap + mLegendTextPaint.measureText("L") + itemGap

            // R dot + label
            mLegendTextPaint.color = mRightColor
            canvas.drawCircle(x + dotRadius, legendY - dotRadius * 0.5f, dotRadius, mLegendTextPaint)
            canvas.drawText("R", x + dotRadius * 2 + labelGap, legendY, mLegendTextPaint)
            x += dotRadius * 2 + labelGap + mLegendTextPaint.measureText("R") + itemGap
        }

        // Measurement dot + label (если есть)
        if (mHasMeasurement) {
            mLegendTextPaint.color = mMeasurementPaint.color
            canvas.drawCircle(x + dotRadius, legendY - dotRadius * 0.5f, dotRadius, mLegendTextPaint)
            canvas.drawText("Meas L", x + dotRadius * 2 + labelGap, legendY, mLegendTextPaint)
            x += dotRadius * 2 + labelGap + mLegendTextPaint.measureText("Meas L") + itemGap
        }

        // Measurement R dot + label (если есть)
        if (mHasMeasurementR) {
            mLegendTextPaint.color = mMeasurementRPaint.color
            canvas.drawCircle(x + dotRadius, legendY - dotRadius * 0.5f, dotRadius, mLegendTextPaint)
            canvas.drawText("Meas R", x + dotRadius * 2 + labelGap, legendY, mLegendTextPaint)
            x += dotRadius * 2 + labelGap + mLegendTextPaint.measureText("Meas R") + itemGap
        }

        // Target dot + label (если есть)
        if (mHasTarget) {
            mLegendTextPaint.color = mTargetPaint.color
            canvas.drawCircle(x + dotRadius, legendY - dotRadius * 0.5f, dotRadius, mLegendTextPaint)
            canvas.drawText("Target", x + dotRadius * 2 + labelGap, legendY, mLegendTextPaint)
        }
    }

    // ── Label formatting ────────────────────────────────────────────

    /** Format a dB value as "+3 dB", "0 dB", "-3 dB", etc. */
    private fun formatDbLabel(db: Float): String {
        val dbInt = db.roundToInt()
        return if (dbInt > 0) "+$dbInt dB" else "$dbInt dB"
    }

    /** Format a frequency as "20 Hz", "1 kHz", "20 kHz", etc. */
    private fun formatFreqLabel(freq: Double): String {
        return if (freq < 1000.0) {
            "${freq.toInt()} Hz"
        } else {
            "${(freq / 1000.0).roundToInt()} kHz"
        }
    }

    // ── Label selection ─────────────────────────────────────────────

    /** Choose which frequency labels to show based on plot width. */
    private fun computeFreqLabels(): DoubleArray {
        val allLabels = doubleArrayOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)

        // Measure the widest label to determine minimum spacing
        val widestLabel = mFreqLabelPaint.measureText("20 kHz")
        val minSpacing = widestLabel + 12f
        val maxLabels = (mPlotWidth / minSpacing).toInt().coerceAtLeast(4)

        val selected: MutableList<Double> = if (allLabels.size <= maxLabels) {
            allLabels.toMutableList()
        } else {
            // Subsample evenly, always including first and last
            val step = ceil(allLabels.size.toFloat() / maxLabels).toInt()
            val result = mutableListOf<Double>()
            var i = 0
            while (i < allLabels.size) {
                result.add(allLabels[i])
                i += step
            }
            // Ensure last label is always included
            if (result.last() != allLabels.last()) {
                result.add(allLabels.last())
            }
            result
        }

        // Remove overlaps accounting for edge alignments:
        // first = LEFT, last = RIGHT, others = CENTER.
        return removeOverlappingFreqLabels(selected).toDoubleArray()
    }

    /**
     * Greedily drop middle frequency labels whose rendered bounds overlap a
     * kept neighbor. First and last labels are always kept.
     */
    private fun removeOverlappingFreqLabels(labels: MutableList<Double>): List<Double> {
        if (labels.size <= 2) return labels
        val n = labels.size
        val gap = 4f

        val xs = FloatArray(n) { mPlotLeft + projectX(labels[it]) * mPlotWidth }
        val ws = FloatArray(n) { mFreqLabelPaint.measureText(formatFreqLabel(labels[it])) }

        fun leftEdge(i: Int) = when (i) {
            0 -> xs[i]                       // LEFT aligned
            n - 1 -> xs[i] - ws[i]           // RIGHT aligned
            else -> xs[i] - ws[i] / 2f       // CENTER aligned
        }
        fun rightEdge(i: Int) = when (i) {
            0 -> xs[i] + ws[i]               // LEFT aligned
            n - 1 -> xs[i]                   // RIGHT aligned
            else -> xs[i] + ws[i] / 2f       // CENTER aligned
        }

        val kept = mutableListOf(0)
        for (i in 1 until n - 1) {
            if (leftEdge(i) >= rightEdge(kept.last()) + gap) kept.add(i)
        }
        // Drop middle labels from the tail until the last label fits without overlap
        while (kept.size > 1 && leftEdge(n - 1) < rightEdge(kept.last()) + gap) {
            kept.removeAt(kept.size - 1)
        }
        kept.add(n - 1)
        return kept.map { labels[it] }
    }

    /** Compute a nice step size for dB grid lines based on the range. */
    private fun computeDbStep(): Float {
        val range = mMaxDb - mMinDb
        return when {
            range <= 12f -> 3f
            range <= 24f -> 3f
            range <= 48f -> 6f
            else -> 12f
        }
    }

    // ── Public API ──────────────────────────────────────────────────

    fun setBands(bands: ParametricEqBandList, preampDb: Double = mPreampDb.toDouble()) {
        mPreampDb = preampDb.toFloat()
        mChannelsDiffer = bands.any { it.channelMode != ParametricEqChannelMode.BOTH }
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

    // ── Measurement / Target API ─────────────────────────────────

    /**
     * Установить измеренную АЧХ для отображения поверх кривых фильтров.
     * @param freqs массив частот (Гц)
     * @param spl массив SPL (дБ)
     */
    fun setMeasurementData(freqs: FloatArray, spl: FloatArray) {
        require(freqs.size == spl.size) { "freqs and spl must have same size" }
        mMeasurementFreqs = freqs
        mMeasurementSpl = spl
        mHasMeasurement = freqs.isNotEmpty()
        updateDbRangeWithMeasurement()
        postInvalidate()
    }

    /** Очистить измеренную АЧХ. */
    fun clearMeasurementData() {
        mHasMeasurement = false
        mMeasurementFreqs = FloatArray(0)
        mMeasurementSpl = FloatArray(0)
        updateDbRangeWithMeasurement()
        postInvalidate()
    }

    /**
     * Установить измеренную АЧХ второго канала (R) для отображения.
     * Используется в режиме L/R для показа двух графиков одновременно.
     * @param freqs массив частот (Гц)
     * @param spl массив SPL (дБ)
     */
    fun setMeasurementDataR(freqs: FloatArray, spl: FloatArray) {
        require(freqs.size == spl.size) { "freqs and spl must have same size" }
        mMeasurementRFreqs = freqs
        mMeasurementRSpl = spl
        mHasMeasurementR = freqs.isNotEmpty()
        updateDbRangeWithMeasurement()
        postInvalidate()
    }

    /** Очистить измеренную АЧХ второго канала (R). */
    fun clearMeasurementDataR() {
        mHasMeasurementR = false
        mMeasurementRFreqs = FloatArray(0)
        mMeasurementRSpl = FloatArray(0)
        updateDbRangeWithMeasurement()
        postInvalidate()
    }

    /**
     * Установить целевую кривую для отображения.
     * @param freqs массив частот (Гц)
     * @param targetDb массив целевых усилений (дБ)
     */
    fun setTargetCurve(freqs: FloatArray, targetDb: FloatArray) {
        require(freqs.size == targetDb.size) { "freqs and targetDb must have same size" }
        mTargetFreqs = freqs
        mTargetSpl = targetDb
        mHasTarget = freqs.isNotEmpty()
        updateDbRangeWithMeasurement()
        postInvalidate()
    }

    /** Очистить целевую кривую. */
    fun clearTargetCurve() {
        mHasTarget = false
        mTargetFreqs = FloatArray(0)
        mTargetSpl = FloatArray(0)
        updateDbRangeWithMeasurement()
        postInvalidate()
    }

    /**
     * Пересчитать Y-axis диапазон с учётом ВСЕХ видимых графиков:
     * measurement (L+R), target curve и PEQ-фильтр.
     * Берёт max/min по всем трём, добавляет ±3 dB запас сверху и снизу.
     * Округляет до кратного 3 dB.
     */
    private fun updateDbRangeWithMeasurement() {
        val allValues = mutableListOf<Float>()

        // PEQ-кривые (L+R с preamp)
        val preamp = mPreampDb
        for (v in mLeftResponseDb) allValues.add(v + preamp)
        for (v in mRightResponseDb) allValues.add(v + preamp)

        // Measurement данные (L)
        if (mHasMeasurement && mMeasurementSpl.isNotEmpty()) {
            for (v in mMeasurementSpl) allValues.add(v)
        }
        // Measurement данные (R)
        if (mHasMeasurementR && mMeasurementRSpl.isNotEmpty()) {
            for (v in mMeasurementRSpl) allValues.add(v)
        }

        // Target curve
        if (mHasTarget && mTargetSpl.isNotEmpty()) {
            for (v in mTargetSpl) allValues.add(v)
        }

        if (allValues.isEmpty()) {
            mMaxDb = 3f
            mMinDb = -3f
            updateClipping(false)
            return
        }

        val maxVal = allValues.maxOrNull() ?: 0f
        val minVal = allValues.minOrNull() ?: 0f

        // ±3 dB запас, округление до кратного 3 dB
        mMaxDb = ceil((maxVal + 3f) / 3f) * 3f
        if (mMaxDb < 3f) mMaxDb = 3f

        mMinDb = floor((minVal - 3f) / 3f) * 3f
        if (mMinDb > -3f) mMinDb = -3f

        // Clipping: PEQ-кривая превышает 0 dB
        val peqGains = (mLeftResponseDb.toList() + mRightResponseDb.toList()).map { it + preamp }
        val clipping = peqGains.any { it > 0.01f } || preamp > 0.01f
        updateClipping(clipping)
    }

    // ── Scaling ─────────────────────────────────────────────────────

    /**
     * Compute adaptive Y-axis range.
     * Top = max(L, R) + 3 dB headroom (at least +3)
     * Bottom = min(L, R) - 3 dB headroom (at most -3)
     * 0 dB is always visible within the range.
     */
    private fun updateDbRange() {
        val preamp = mPreampDb
        val allGains = (mLeftResponseDb.toList() + mRightResponseDb.toList())
            .map { it + preamp }

        if (allGains.isEmpty()) {
            mMaxDb = 3f
            mMinDb = -3f
            updateClipping(false)
            return
        }

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

    /** Logarithmic X projection: log10(f) normalized to [0, 1]. */
    private fun projectX(frequency: Double): Float {
        val logMin = log10(MIN_FREQ)
        val logMax = log10(MAX_FREQ)
        val logF = log10(frequency.coerceIn(MIN_FREQ, MAX_FREQ))
        return ((logF - logMin) / (logMax - logMin)).toFloat()
    }

    /** Linear Y projection: 0 dB at its proportional position between min and max. */
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
