package me.timschneeberger.rootlessjamesdsp.smoothvolume

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import timber.log.Timber
import kotlin.math.abs

/**
 * Canvas-drawn floating Smooth Volume HUD.
 *
 * Renders all four variants (Compact/Expanded x Vertical/Horizontal) plus the
 * theme/size/opacity/corner-radius customization from [SmoothVolumeHudConfig].
 * One view class keeps the overlay window cheap: the window is created once
 * and only the drawing mode changes.
 *
 * Interaction model:
 *  - drag along the main axis (vertical HUD: up/down, horizontal: left/right)
 *    adjusts the gain by the configured swipe step (acceleration optional,
 *    direction reversible);
 *  - tap / double tap / long press run the configured [HudTapAction]
 *    (default tap: expand/collapse);
 *  - in Expanded, the slider area can be dragged to an absolute position;
 *  - the pill in the corner is the drag handle for moving the HUD (custom
 *    position) — drags starting there never change the gain.
 *
 * The same view is used for the in-app live preview with [previewMode]:
 * in preview mode callbacks still fire (so the user can test gestures) but
 * the caller must not apply real gain changes.
 */
class SmoothVolumeHudView(
    context: Context,
    var config: SmoothVolumeHudConfig,
    private val previewMode: Boolean = false,
) : View(context) {

    interface Callbacks {
        /** Called with the snapped absolute gain (dB) requested by the user. */
        fun onGainRequested(db: Double)
        /** Called with a relative delta (dB) requested by the user. */
        fun onAdjustRequested(deltaDb: Double)
        fun onMuteRequested()
        fun onUnmuteRequested()
        fun onResetRequested()
        fun onOpenAppRequested()
        /** User tapped the collapse affordance in Expanded. */
        fun onCollapseRequested()
        /** Expand/collapse toggled from inside the view (tap action); host must resize the window. */
        fun onExpandStateChanged(expanded: Boolean)
        /** HUD moved in custom-position mode; screen coords. */
        fun onMoved(x: Int, y: Int)
        /** Drag of the HUD ended (persist position). */
        fun onMoveEnded()
    }

    var callbacks: Callbacks? = null
    /** Current displayed state; set by the host (controller state flow). */
    var gainDb: Double = 0.0
        set(value) {
            field = value
            invalidate()
        }
    var muted: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var expanded: Boolean = config.style == HudStyle.EXPANDED
        set(value) {
            if (field != value) {
                field = value
                computeGeometry()
                requestLayout()
                invalidate()
                callbacks?.onExpandStateChanged(value)
            }
        }

    //region Geometry (px, computed on size change)

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private var scale = config.size.scale()

    private var panelW = 0f
    private var panelH = 0f
    private val panelRect = RectF()
    private var trackRect = RectF()
    private var muteRect = RectF()
    private var resetRect = RectF()
    private var closeRect = RectF()
    private var handleRect = RectF()
    private var fillPath = Path()

    //endregion

    //region Paints / theme

    private var bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val rect = RectF()

    private var colorSurface = Color.argb(235, 30, 30, 32)
    private var colorOnSurface = Color.WHITE
    private var colorAccent = Color.rgb(0x7c, 0xb1, 0xff)
    private var colorMuted = Color.rgb(0xff, 0x6e, 0x6e)
    private var colorTrackDim = Color.argb(60, 255, 255, 255)

    private fun resolveThemeColors() {
        val dark = when (config.theme) {
            HudTheme.DARK -> true
            HudTheme.LIGHT -> false
            HudTheme.SYSTEM -> (resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        colorSurface = if (dark) Color.argb(230, 28, 28, 30) else Color.argb(235, 245, 245, 247)
        colorOnSurface = if (dark) Color.WHITE else Color.argb(0xE6, 0x10, 0x10, 0x12)
        colorAccent = if (dark) Color.rgb(0x7c, 0xb1, 0xff) else Color.rgb(0x2b, 0x62, 0xc9)
        colorMuted = if (dark) Color.rgb(0xff, 0x7a, 0x7a) else Color.rgb(0xc0, 0x35, 0x35)
        colorTrackDim = if (dark) Color.argb(55, 255, 255, 255) else Color.argb(45, 0, 0, 0)
    }

    //endregion

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    //region Gesture handling

    private var trackingTrack = false      // drag on the slider/track (absolute)
    private var movingPanel = false        // drag on the handle (reposition)
    private var swipeAxisStart = 0f
    private var lastSentSteps = 0
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            when {
                muteRect.contains(e.x, e.y) -> { haptic(); if (muted) callbacks?.onUnmuteRequested() else callbacks?.onMuteRequested() }
                resetRect.contains(e.x, e.y) -> { haptic(); callbacks?.onResetRequested() }
                closeRect.contains(e.x, e.y) -> { haptic(); callbacks?.onCollapseRequested() }
                handleRect.contains(e.x, e.y) -> { /* handle tap: nothing */ }
                else -> runTapAction(config.tapAction)
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (muteRect.contains(e.x, e.y) || resetRect.contains(e.x, e.y) || closeRect.contains(e.x, e.y)) return true
            runTapAction(config.doubleTapAction)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (muteRect.contains(e.x, e.y) || resetRect.contains(e.x, e.y) || closeRect.contains(e.x, e.y)) return
            haptic()
            runTapAction(config.longPressAction)
        }
    })

    private fun runTapAction(action: HudTapAction) {
        when (action) {
            HudTapAction.NONE -> {}
            HudTapAction.EXPAND -> {
                if (expanded) callbacks?.onCollapseRequested() else expanded = true
                haptic()
            }
            HudTapAction.MUTE -> { if (muted) callbacks?.onUnmuteRequested() else callbacks?.onMuteRequested() }
            HudTapAction.RESET -> callbacks?.onResetRequested()
            HudTapAction.OPEN_APP -> callbacks?.onOpenAppRequested()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                when {
                    closeRect.contains(event.x, event.y) && expanded -> {}
                    resetRect.contains(event.x, event.y) && expanded -> {}
                    muteRect.contains(event.x, event.y) -> {}
                    handleRect.contains(event.x, event.y) -> {
                        movingPanel = true
                    }
                    expanded && trackRect.contains(event.x, event.y) -> {
                        trackingTrack = true
                        applyTrackPosition(event.x, event.y)
                    }
                    else -> {
                        // Start a swipe gesture anywhere else on the panel
                        swipeAxisStart = if (config.orientation == HudOrientation.VERTICAL) event.y else event.x
                        lastSentSteps = 0
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (movingPanel) {
                    callbacks?.onMoved((event.rawX - event.x).toInt(), (event.rawY - event.y).toInt())
                    return true
                }
                if (trackingTrack) {
                    applyTrackPosition(event.x, event.y)
                    return true
                }
                // Total offset from gesture start along the main axis, oriented
                // so that "louder" is positive (vertical: up; horizontal: right).
                val axis = if (config.orientation == HudOrientation.VERTICAL) event.y else event.x
                var oriented = axis - swipeAxisStart
                if (config.orientation == HudOrientation.VERTICAL) oriented = -oriented
                if (config.reverseSwipe) oriented = -oriented
                val pxPerStep = dp(PX_PER_STEP_DP) * scale
                val steps = (oriented / pxPerStep).toInt()
                // Acceleration: past a fast-drag threshold, steps count double.
                val fastPx = dp(PX_PER_STEP_DP * 8f) * scale
                val extraSteps = if (config.swipeAcceleration && abs(oriented) > fastPx)
                    (oriented.toInt() - if (oriented > 0) fastPx.toInt() else -fastPx.toInt()) / pxPerStep.toInt() else 0
                val totalSteps = steps + extraSteps
                if (totalSteps != lastSentSteps) {
                    val deltaDb = (totalSteps - lastSentSteps) * config.swipeStepDb
                    lastSentSteps = totalSteps
                    if (deltaDb != 0.0) {
                        callbacks?.onAdjustRequested(deltaDb)
                        haptic()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (movingPanel) callbacks?.onMoveEnded()
                movingPanel = false
                trackingTrack = false
                lastSentSteps = 0
                // The throttled controller guarantees the final value is applied.
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun applyTrackPosition(x: Float, y: Float) {
        val pos = if (config.orientation == HudOrientation.VERTICAL)
            1.0 - (y - trackRect.top) / trackRect.height().toDouble()
        else
            (x - trackRect.left) / trackRect.width().toDouble()
        val db = SmoothVolumeMath.positionToDb(pos.coerceIn(0.0, 1.0))
        if (!SmoothVolumeMath.sameStep(db, gainDb)) {
            callbacks?.onGainRequested(db)
            haptic()
        }
    }

    private fun haptic() {
        if (config.haptics == HudHaptics.OFF) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val constant = if (config.haptics == HudHaptics.LIGHT)
                HapticFeedbackConstants.KEYBOARD_TAP else HapticFeedbackConstants.VIRTUAL_KEY
            performHapticFeedback(constant)
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(VibrationEffect.createOneShot(
                if (config.haptics == HudHaptics.LIGHT) 8L else 20L, 60))
        }
    }

    //endregion

    //region Layout / drawing

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w: Float
        val h: Float
        if (!expanded) {
            if (config.orientation == HudOrientation.VERTICAL) {
                w = dp(64f) * scale; h = dp(190f) * scale
            } else {
                w = dp(150f) * scale; h = dp(44f) * scale
            }
        } else {
            if (config.orientation == HudOrientation.VERTICAL) {
                w = dp(84f) * scale; h = dp(260f) * scale
            } else {
                w = dp(230f) * scale; h = dp(110f) * scale
            }
        }
        setMeasuredDimension(w.toInt(), h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeGeometry()
    }

    fun reconfigure(newConfig: SmoothVolumeHudConfig) {
        config = newConfig
        scale = config.size.scale()
        expanded = config.style == HudStyle.EXPANDED
        resolveThemeColors()
        requestLayout()
        invalidate()
    }

    /** Panel size in px for the host to size the window. */
    fun measurePanel(): Pair<Int, Int> {
        measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        return measuredWidth to measuredHeight
    }

    private fun computeGeometry() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        panelW = w
        panelH = h
        panelRect.set(0f, 0f, w, h)

        val pad = dp(10f) * scale
        val handle = dp(14f) * scale

        if (!expanded) {
            if (config.orientation == HudOrientation.VERTICAL) {
                trackRect.set(pad, pad + dp(40f) * scale, w - pad, h - pad - dp(28f) * scale)
                muteRect.set(pad, h - pad - dp(22f) * scale, pad + dp(22f) * scale, h - pad)
                handleRect.set(w - pad - handle, pad, w - pad, pad + handle)
            } else {
                trackRect.set(pad + dp(64f) * scale, pad + dp(6f) * scale, w - pad - dp(30f) * scale, pad + dp(26f) * scale)
                muteRect.set(pad, (h - dp(24f) * scale) / 2f, pad + dp(22f) * scale, (h + dp(24f) * scale) / 2f)
                handleRect.set(w - pad - handle, (h - handle) / 2f, w - pad, (h + handle) / 2f)
            }
            resetRect.setEmpty()
            closeRect.setEmpty()
        } else {
            if (config.orientation == HudOrientation.VERTICAL) {
                closeRect.set(w - pad - handle, pad, w - pad, pad + handle)
                trackRect.set(w / 2f - dp(9f) * scale, pad + dp(56f) * scale, w / 2f + dp(9f) * scale, h - pad - dp(44f) * scale)
                muteRect.set(pad, h - pad - dp(26f) * scale, pad + dp(44f) * scale, h - pad)
                resetRect.set(w / 2f - dp(30f) * scale, h - pad - dp(26f) * scale, w / 2f + dp(30f) * scale, h - pad)
            } else {
                closeRect.set(w - pad - handle, pad, w - pad, pad + handle)
                trackRect.set(pad + dp(8f) * scale, pad + dp(52f) * scale, w - pad - dp(8f) * scale, pad + dp(76f) * scale)
                muteRect.set(pad, h - pad - dp(26f) * scale, pad + dp(44f) * scale, h - pad)
                resetRect.set(w / 2f - dp(30f) * scale, h - pad - dp(26f) * scale, w / 2f + dp(30f) * scale, h - pad)
            }
            handleRect.setEmpty()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = dp(config.cornerRadius.radiusDp() * scale)
        resolveThemeColors()

        bgPaint.color = colorSurface
        bgPaint.alpha = (config.opacityPercent * 255 / 100).coerceIn(0, 255)
        canvas.drawRoundRect(panelRect, radius, radius, bgPaint)

        drawSpeakerIcon(canvas)

        // Value text
        textPaint.color = if (muted) colorMuted else colorOnSurface
        smallTextPaint.color = colorOnSurface

        if (!expanded) {
            if (config.orientation == HudOrientation.VERTICAL) {
                textPaint.textSize = dp(13f) * scale
                val label = if (muted) "MUTE" else SmoothVolumeMath.formatDb(gainDb)
                // Horizontal readout between the speaker icon and the track
                drawCenteredText(canvas, label, panelRect.centerX(), padTop() + dp(26f) * scale, textPaint)
                drawTrack(canvas)
                drawMuteButton(canvas)
                drawHandle(canvas)
            } else {
                textPaint.textSize = dp(13f) * scale
                val label = if (muted) "MUTE" else SmoothVolumeMath.formatDb(gainDb)
                drawCenteredText(canvas, label, muteRect.right + dp(34f) * scale, trackRect.centerY(), textPaint)
                drawTrack(canvas)
                drawMuteButton(canvas)
                drawHandle(canvas)
            }
        } else {
            // Title
            smallTextPaint.textSize = dp(10f) * scale
            smallTextPaint.alpha = 160
            drawCenteredText(canvas, context.getString(me.timschneeberger.rootlessjamesdsp.R.string.smooth_volume_hud_title),
                panelRect.centerX(), padTop() + dp(10f) * scale, smallTextPaint)
            // Big value
            textPaint.textSize = dp(20f) * scale
            val label = if (muted) "MUTE" else SmoothVolumeMath.formatDb(gainDb)
            drawCenteredText(canvas, label, panelRect.centerX(), padTop() + dp(34f) * scale, textPaint)
            drawTrack(canvas)
            drawMuteButton(canvas)
            drawResetButton(canvas)
            drawCloseButton(canvas)
        }
    }

    private fun padTop() = dp(10f) * scale

    private fun drawCenteredText(canvas: Canvas, text: String, cx: Float, cy: Float, paint: Paint) {
        val tw = paint.measureText(text)
        val fm = paint.fontMetrics
        canvas.drawText(text, cx - tw / 2f, cy - (fm.ascent + fm.descent) / 2f, paint)
    }

    private fun drawTrack(canvas: Canvas) {
        val radius = (trackRect.height() / 2f).coerceAtLeast(dp(4f))
        trackPaint.color = colorTrackDim
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)
        val pos = SmoothVolumeMath.dbToPosition(gainDb).toFloat()
        fillPaint.color = if (muted) colorMuted else colorAccent
        if (config.orientation == HudOrientation.VERTICAL) {
            val fillH = trackRect.height() * pos
            rect.set(trackRect.left, trackRect.bottom - fillH, trackRect.right, trackRect.bottom)
        } else {
            val fillW = trackRect.width() * pos
            rect.set(trackRect.left, trackRect.top, trackRect.left + fillW, trackRect.bottom)
        }
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        // Knob
        fillPaint.color = colorOnSurface
        if (config.orientation == HudOrientation.VERTICAL) {
            val cy = trackRect.bottom - trackRect.height() * pos
            canvas.drawCircle(trackRect.centerX(), cy, dp(5f) * scale, fillPaint)
        } else {
            val cx = trackRect.left + trackRect.width() * pos
            canvas.drawCircle(cx, trackRect.centerY(), dp(5f) * scale, fillPaint)
        }
    }

    private fun drawMuteButton(canvas: Canvas) {
        iconPaint.color = if (muted) colorMuted else colorOnSurface
        val cx = muteRect.centerX()
        val cy = muteRect.centerY()
        val r = dp(5f) * scale
        // Speaker glyph
        rect.set(cx - r * 1.4f, cy - r, cx - r * 0.4f, cy + r)
        canvas.drawRect(rect, iconPaint)
        fillPath.reset()
        fillPath.moveTo(cx - r * 0.4f, cy)
        fillPath.lineTo(cx + r * 0.4f, cy - r * 1.4f)
        fillPath.lineTo(cx + r * 0.4f, cy + r * 1.4f)
        fillPath.close()
        if (muted) {
            canvas.drawLine(cx + r * 0.6f, cy - r, cx + r * 1.6f, cy + r, iconPaint)
            canvas.drawLine(cx + r * 1.6f, cy - r, cx + r * 0.6f, cy + r, iconPaint)
        } else {
            canvas.drawArc(cx + r * 0.3f, cy - r * 0.9f, cx + r * 1.5f, cy + r * 0.9f, -50f, 100f, false, iconPaint)
        }
    }

    private fun drawResetButton(canvas: Canvas) {
        if (resetRect.isEmpty) return
        trackPaint.color = colorTrackDim
        canvas.drawRoundRect(resetRect, dp(6f) * scale, dp(6f) * scale, trackPaint)
        smallTextPaint.textSize = dp(10f) * scale
        smallTextPaint.alpha = 220
        smallTextPaint.color = colorOnSurface
        drawCenteredText(canvas, "0 dB", resetRect.centerX(), resetRect.centerY(), smallTextPaint)
    }

    private fun drawCloseButton(canvas: Canvas) {
        if (closeRect.isEmpty) return
        iconPaint.color = colorOnSurface
        val cx = closeRect.centerX()
        val cy = closeRect.centerY()
        val r = dp(4.5f) * scale
        canvas.drawLine(cx - r, cy - r, cx + r, cy + r, iconPaint)
        canvas.drawLine(cx + r, cy - r, cx - r, cy + r, iconPaint)
    }

    private fun drawHandle(canvas: Canvas) {
        if (handleRect.isEmpty) return
        iconPaint.color = colorTrackDim
        val cx = handleRect.centerX()
        val cy = handleRect.centerY()
        val r = dp(3f) * scale
        if (config.orientation == HudOrientation.VERTICAL) {
            for (i in -1..1) canvas.drawLine(cx - r, cy + i * dp(2.5f) * scale, cx + r, cy + i * dp(2.5f) * scale, iconPaint)
        } else {
            for (i in -1..1) canvas.drawLine(cx + i * dp(2.5f) * scale, cy - r, cx + i * dp(2.5f) * scale, cy + r, iconPaint)
        }
    }

    private fun drawSpeakerIcon(canvas: Canvas) {
        if (expanded) return
        iconPaint.color = if (muted) colorMuted else colorOnSurface
        val cx: Float
        val cy: Float
        if (config.orientation == HudOrientation.VERTICAL) {
            cx = panelRect.centerX()
            cy = panelRect.top + dp(20f) * scale
        } else {
            cx = muteRect.centerX()
            cy = muteRect.centerY()
            return // compact horizontal: mute button doubles as the icon
        }
        val r = dp(5f) * scale
        rect.set(cx - r * 1.4f, cy - r, cx - r * 0.4f, cy + r)
        canvas.drawRect(rect, iconPaint)
        fillPath.reset()
        fillPath.moveTo(cx - r * 0.4f, cy)
        fillPath.lineTo(cx + r * 0.4f, cy - r * 1.4f)
        fillPath.lineTo(cx + r * 0.4f, cy + r * 1.4f)
        fillPath.close()
        canvas.drawPath(fillPath, iconPaint)
        if (muted) {
            canvas.drawLine(cx + r * 0.6f, cy - r, cx + r * 1.6f, cy + r, iconPaint)
            canvas.drawLine(cx + r * 1.6f, cy - r, cx + r * 0.6f, cy + r, iconPaint)
        } else {
            canvas.drawArc(cx + r * 0.3f, cy - r * 0.9f, cx + r * 1.5f, cy + r * 0.9f, -50f, 100f, false, iconPaint)
        }
    }

    //endregion

    companion object {
        /** Base drag distance for one swipe step, in dp. */
        const val PX_PER_STEP_DP = 14f
    }
}
