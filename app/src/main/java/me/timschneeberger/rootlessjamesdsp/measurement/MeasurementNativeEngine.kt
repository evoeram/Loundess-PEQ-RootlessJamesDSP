package me.timschneeberger.rootlessjamesdsp.measurement

/**
 * Нативный мост к C/NDK measurement engine (libmeasurement.so).
 *
 * Предоставляет:
 *  - Генерацию log sweep и обратного фильтра Фарины
 *  - Деконволюцию записи → импульсная характеристика
 *  - Вычисление SPL и фазы из IR
 *  - Variable fractional-octave сглаживание
 *
 * Использование:
 * 1. createContext() → handle
 * 2. generateSweep() + generateInverseFilter()
 * 3. Воспроизвести sweep, записать через микрофон
 * 4. deconvolve(handle, recorded, inverseFilter, sampleRate)
 * 5. computeSpl(handle, fftSize)
 * 6. getSpl(handle), getSplFrequencies(handle)
 * 7. applySmoothing(handle, ...)
 * 8. destroyContext(handle)
 */
class MeasurementNativeEngine {

    companion object {
        init {
            System.loadLibrary("measurement")
        }
    }

    /** Создать нативный контекст измерения. Возвращает handle. */
    external fun createContext(): Long

    /** Освободить нативный контекст. */
    external fun destroyContext(handle: Long)

    /** Сгенерировать логарифмический свип. */
    external fun generateSweep(f1: Double, f2: Double, duration: Double, sampleRate: Int): FloatArray

    /** Сгенерировать обратный фильтр Фарины. */
    external fun generateInverseFilter(f1: Double, f2: Double, duration: Double, sampleRate: Int): FloatArray

    /**
     * Выполнить деконволюцию: запись + обратный фильтр → импульсная характеристика.
     * @return 0 при успехе, отрицательный код при ошибке.
     */
    external fun deconvolve(handle: Long, recorded: FloatArray, inverseFilter: FloatArray, sampleRate: Int): Int

    /** Получить импульсную характеристику как FloatArray. */
    external fun getIr(handle: Long): FloatArray

    /**
     * Вычислить SPL и фазу из IR.
     * @param fftSize размер FFT (0 = авто, следующая степень двойки >= irLen)
     * @return 0 при успехе
     */
    external fun computeSpl(handle: Long, fftSize: Int): Int

    /** Получить массив частот (Гц) SPL-спектра. */
    external fun getSplFrequencies(handle: Long): FloatArray

    /** Получить массив SPL (дБ). */
    external fun getSpl(handle: Long): FloatArray

    /** Получить массив фазы (градусы). */
    external fun getSplPhase(handle: Long): FloatArray

    /**
     * Применить variable fractional-octave сглаживание к SPL.
     * @param lowFreq граница переключения (Гц, обычно 200)
     * @param lowFraction дробь октавы для НЧ (например 1f/48f)
     * @param highFraction дробь октавы для ВЧ (например 1f/3f)
     */
    external fun applySmoothing(handle: Long, lowFreq: Float, lowFraction: Float, highFraction: Float)

    /**
     * Применить сглаживание заданного типа.
     * @param type тип сглаживания (см. SmoothingType.nativeId)
     */
    external fun applySmoothingByType(handle: Long, type: Int)
}

/**
 * Тип сглаживания графика SPL.
 *
 * @param nativeId идентификатор для native code
 * @param displayName отображаемое имя
 */
enum class SmoothingType(val nativeId: Int, val displayName: String) {
    NONE(0, "Remove smoothing"),
    OCTAVE_1_1(1, "1/1 smoothing"),
    OCTAVE_1_2(2, "1/2 smoothing"),
    OCTAVE_1_3(3, "1/3 smoothing"),
    OCTAVE_1_6(4, "1/6 smoothing"),
    OCTAVE_1_12(5, "1/12 smoothing"),
    OCTAVE_1_24(6, "1/24 smoothing"),
    OCTAVE_1_48(7, "1/48 smoothing"),
    VARIABLE(8, "Var smoothing"),
    PSYCHOACOUSTIC(9, "Psychoacoustic"),
    ERB(10, "ERB smoothing")
}
