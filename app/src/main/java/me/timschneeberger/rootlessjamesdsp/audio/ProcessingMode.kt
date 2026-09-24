package me.timschneeberger.rootlessjamesdsp.audio

import me.timschneeberger.rootlessjamesdsp.latency.LatencyTuning
import me.timschneeberger.rootlessjamesdsp.utils.SdkCheck

/**
 * Режим обработки звука.
 *
 * [STANDARD] — оригинальный legacy capture loop (большие буферы, обычный приоритет).
 *   Все эффекты JamesDSP. Максимальная стабильность, но высокая задержка (~150–170 мс).
 *
 * [LOW_LATENCY] — оптимизированный capture loop (маленькие блоки, PERFORMANCE_MODE_LOW_LATENCY,
 *   QueueController). Все эффекты, задержка ~20–80 мс.
 *
 * [MOVIE] — Android EQ Mode (без capture loop, через системный DynamicsProcessing).
 *   Только EQ (GraphicEQ + PEQ), но идеальный A/V sync.
 *   Требует Android 9+ (Pie).
 */
enum class ProcessingMode(val value: Int) {
    STANDARD(0),
    LOW_LATENCY(1),
    MOVIE(2);

    companion object {
        fun fromInt(v: Int): ProcessingMode = entries.firstOrNull { it.value == v } ?: LOW_LATENCY
    }

    /** true если режим использует capture loop */
    fun usesCaptureLoop(): Boolean = this != MOVIE

    /** true если режим использует Android EQ (DynamicsProcessing) */
    fun usesAndroidEq(): Boolean = this == MOVIE

    /** true если режим доступен на этом устройстве */
    fun isAvailable(): Boolean = when (this) {
        MOVIE -> SdkCheck.isPie
        else -> true
    }

    /** true если Low-latency пресет активен */
    fun isLowLatency(): Boolean = this == LOW_LATENCY
}
