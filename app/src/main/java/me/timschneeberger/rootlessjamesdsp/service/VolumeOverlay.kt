package me.timschneeberger.rootlessjamesdsp.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.abs

/**
 * System overlay HUD that shows the current smooth volume level on top of any app.
 * Displays a horizontal volume bar + dB value, auto-hides after [AUTO_HIDE_MS].
 * Uses TYPE_APPLICATION_OVERLAY (requires SYSTEM_ALERT_WINDOW permission).
 */
class VolumeOverlay(private val context: Context) {

    companion object {
        private const val AUTO_HIDE_MS = 1500L
        private const val MIN_DB = -60.0
        private const val MAX_DB = 0.0
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var isShowing = false

    private val hideRunnable = Runnable { hide() }

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

                val dbStr = if (volumeDb >= 0) "0 dB" else "${volumeDb.toInt()} dB"
                text.text = dbStr
            }

            // Reset auto-hide timer
            handler.removeCallbacks(hideRunnable)
            handler.postDelayed(hideRunnable, AUTO_HIDE_MS)
        }
    }

    fun hide() {
        handler.post {
            removeOverlay()
        }
    }

    private fun addOverlay() {
        if (isShowing) return

        val view = buildOverlayView()

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
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
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                // ignore
            }
        }
        overlayView = null
        isShowing = false
        handler.removeCallbacks(hideRunnable)
    }

    private fun buildOverlayView(): View {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            background = createBackground()
            elevation = dp(6).toFloat()
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
