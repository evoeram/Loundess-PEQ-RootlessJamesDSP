package me.timschneeberger.rootlessjamesdsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * Визуализация входящего аудиосигнала в реальном времени.
 *
 * Отображает осциллограмму записываемого сигнала с микрофона.
 * Обновляется через [updateSamples] — вызывается из потока записи
 * с новыми сэмплами. Внутри View прореживает данные для отображения.
 *
 * Цвет: accent (primary) для сигнала, outline для центральной линии.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val signalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF4CAF50.toInt() // зелёный — будет перекрашен из accent
    }

    private val centerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        alpha = 60
    }

    /** Текущий отображаемый буфер сэмплов (прореженный). */
    private var displayBuffer: FloatArray = FloatArray(0)

    /** Максимальное количество отображаемых точек (ширина view). */
    private val maxDisplayPoints = 512

    /** Пик по модулю для масштабирования. */
    private var peakAmplitude = 0.001f

    /** Кольцевой накопительный буфер последних сэмплов. */
    private val ringBuffer = FloatArray(4096)
    private var ringWritePos = 0
    private var ringCount = 0

    init {
        // Пытаемся получить accent color из темы
        val ta = context.obtainStyledAttributes(
            intArrayOf(android.R.attr.colorAccent, android.R.attr.colorPrimary)
        )
        val accentColor = ta.getColor(0, 0xFF4CAF50.toInt())
        val primaryColor = ta.getColor(1, accentColor)
        ta.recycle()
        signalPaint.color = primaryColor

        // colorOutline — ресурс Material3, получаем через typedValue
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorOutline, typedValue, true
        )
        val outlineColor = typedValue.resourceId
        if (outlineColor != 0) {
            centerLinePaint.color = context.getColor(outlineColor)
        } else {
            centerLinePaint.color = typedValue.data.toInt()
        }
    }

    /**
     * Обновить отображение новыми сэмплами.
     * Вызывается из потока записи (не UI).
     * Потокобопасно: только запись в ringBuffer, отрисовка читает snapshot.
     *
     * @param samples массив новых сэмплов (float, -1..1)
     * @param offset смещение в массиве
     * @param length количество сэмплов
     */
    @Synchronized
    fun updateSamples(samples: FloatArray, offset: Int = 0, length: Int = samples.size) {
        // Копируем в кольцевой буфер
        for (i in offset until offset + length) {
            ringBuffer[ringWritePos] = samples[i]
            ringWritePos = (ringWritePos + 1) % ringBuffer.size
            if (ringCount < ringBuffer.size) ringCount++
        }

        // Прореживание для отображения
        val displayLen = min(maxDisplayPoints, ringCount)
        if (displayLen <= 0) return

        displayBuffer = FloatArray(displayLen)
        val step = ringCount.toFloat() / displayLen
        peakAmplitude = 0.001f

        for (i in 0 until displayLen) {
            val srcIdx = ((ringWritePos - ringCount + (i * step).toInt()) % ringBuffer.size)
            val idx = if (srcIdx < 0) srcIdx + ringBuffer.size else srcIdx
            val sample = ringBuffer[idx]
            displayBuffer[i] = sample
            val absSample = abs(sample)
            if (absSample > peakAmplitude) peakAmplitude = absSample
        }

        // Триггерим перерисовку на UI-потоке
        postInvalidateOnAnimation()
    }

    /** Очистить визуализацию. */
    @Synchronized
    fun clear() {
        ringWritePos = 0
        ringCount = 0
        displayBuffer = FloatArray(0)
        peakAmplitude = 0.001f
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f

        // Центральная линия
        canvas.drawLine(0f, centerY, w, centerY, centerLinePaint)

        if (displayBuffer.isEmpty()) return

        // Масштаб: чтобы пиковый сигнал занимал ~80% высоты
        val scale = (h * 0.4f) / peakAmplitude

        val path = Path()
        val xStep = w / displayBuffer.size

        for (i in displayBuffer.indices) {
            val x = i * xStep
            val y = centerY - (displayBuffer[i] * scale)
            if (i == 0) path.moveTo(x, y)
            else path.lineTo(x, y)
        }

        canvas.drawPath(path, signalPaint)
    }
}
