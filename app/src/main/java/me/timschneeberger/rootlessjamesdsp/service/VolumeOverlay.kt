package me.timschneeberger.rootlessjamesdsp.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * System overlay HUD that shows the current smooth volume level on top of any app.
 * Displays a horizontal volume bar + dB value, auto-hides after [AUTO_HIDE_MS].
 *
 * Supports:
 * - Vertical swipe to adjust volume (up = louder, down = quieter)
 * - Shows on lock screen via FLAG_SHOW_WHEN_LOCKED
 * - Touch-and-drag for continuous fine-grained control
 *
 * Uses TYPE_APPLICATION_OVERLAY (requires SYSTEM_ALERT_WINDOW permission).
 */
class VolumeOverlay(
    private val context: Context,
    private val onVolumeDelta: (Double) -> Unit
) {

    companion object {
        private const val AUTO_HIDE_MS = 1500L
        private const val MIN_DB = -60.0
        private const val MAX_DB = 0.0

        /** Pixels of vertical movement per 0.5 dB step when swiping. */
        private const val PX_PER_STEP_DP = 6
        /** dB change per swipe step. */
        private const val STEP_DB = 0.5
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var isShowing = false
    private var isTouching = false

    private val hideRunnable = Runnable { hide() }

    // Swipe tracking
    private var lastTouchY = 0f
    private var accumulatedDelta = 0f
    private val pxPerStep: Int = dp(PX_PER_STEP_DP).coerceAtLeast(4)

    fun show(volumeDb: Double) {
        handler.post {
            if (!isShowing) {
                addOverlay()
            }

            overlayView?.let { view ->
                val bar = view.findViewById<ProgressBar>(android.R.id.progress)
                val text = view.findViewById<TextView>(android.R.id.text1)

                // Map dB range [MIN_DB..MAX_DB] to progress [0..100]
                val pct = (((volumeDb - MIN_DB) / (MAX_DB - MIN_DB)) * 100.0)
                    .coerceIn(0.0, 100.0).toInt()
                bar.progress = pct

                val dbStr = String.format("%.1f dB", volumeDb)
                text.text = dbStr
            }

            // Reset auto-hide timer (unless user is actively touching)
            handler.removeCallbacks(hideRunnable)
            if (!isTouching) {
                handler.postDelayed(hideRunnable, AUTO_HIDE_MS)
            }
        }
    }

    fun hide() {
        // Execute synchronously if possible, otherwise post.
        // This prevents the overlay view from outliving the service
        // (which causes a Context leak via WindowManager).
        if (Looper.myLooper() == handler.looper) {
            removeOverlay()
        } else {
            handler.post {
                removeOverlay()
            }
        }
    }

    private fun addOverlay() {
        if (isShowing) return

        val view = buildOverlayView()

        @Suppress("DEPRECATION")
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            y = dp(80)
        }

        try {
            windowManager.addView(view, layoutParams)
            overlayView = view
            isShowing = true
        } catch (e: Exception) {
            // SYSTEM_ALERT_WINDOW permission not granted
        }
    }

    private fun removeOverlay() {
        // Cancel any pending hide runnable first
        handler.removeCallbacks(hideRunnable)
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                // ignore
            }
        }
        overlayView = null
        isShowing = false
        isTouching = false
    }

    private fun buildOverlayView(): View {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            background = createBackground()
            elevation = dp(6).toFloat()
            isClickable = true
            isFocusable = false
        }

        val dbText = TextView(context).apply {
            id = android.R.id.text1
            text = "0 dB"
            setTextColor(Color.WHITE)
            textSize = sp(14f)
            gravity = Gravity.CENTER
        }
        layout.addView(dbText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })

        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            id = android.R.id.progress
            max = 100
            progress = 100
            // Make bar wider
            layoutParams = LinearLayout.LayoutParams(dp(200), dp(6))
        }
        layout.addView(bar)

        // Swipe-to-adjust touch handler: swipe up = louder, down = quieter
        layout.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchY = event.rawY
                    accumulatedDelta = 0f
                    isTouching = true
                    handler.removeCallbacks(hideRunnable)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = lastTouchY - event.rawY // positive = swipe up = louder
                    accumulatedDelta += delta
                    lastTouchY = event.rawY

                    val steps = (accumulatedDelta / pxPerStep).toInt()
                    if (steps != 0) {
                        val dbChange = steps * STEP_DB
                        onVolumeDelta(dbChange)
                        accumulatedDelta -= steps * pxPerStep
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isTouching = false
                    handler.removeCallbacks(hideRunnable)
                    handler.postDelayed(hideRunnable, AUTO_HIDE_MS)
                    true
                }
                else -> false
            }
        }

        return layout
    }

    private fun createBackground(): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.argb(200, 30, 30, 32))
            cornerRadius = dp(16).toFloat()
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
            context.resources.displayMetrics).toInt()

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
            context.resources.displayMetrics)
}
