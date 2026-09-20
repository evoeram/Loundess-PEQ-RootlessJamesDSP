/*
 * spl_response.h — Вычисление амплитудно-частотной характеристики (SPL)
 * из импульсной характеристики.
 *
 * Pipeline:
 *  1. FFT windowed IR → комплексный спектр
 *  2. Magnitude → dB: SPL(f) = 20*log10(|H(f)|)
 *  3. Опционально: сглаживание (variable smoothing)
 *
 * Использует kissfft (real FFT).
 */

#ifndef SPL_RESPONSE_H
#define SPL_RESPONSE_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Результат вычисления SPL.
 */
typedef struct {
    float *frequencies;  /* Массив частот (Гц), длина numBins */
    float *spl;          /* Массив SPL (дБ), длина numBins */
    float *phase;        /* Массив фазы (градусы), длина numBins */
    int numBins;         /* Количество частотных бинов: fftSize/2 + 1 */
    int fftSize;         /* Размер FFT */
    int sampleRate;      /* Частота дискретизации */
} spl_result_t;

/*
 * Вычислить SPL и фазу из импульсной характеристики.
 *
 * ir:          массив сэмплов IR (уже оконный)
 * irLen:       длина IR
 * sampleRate:  частота дискретизации
 * result:      выходная структура (память выделяется malloc'ом)
 *
 * fftSize = следующая степень двойки >= irLen.
 *
 * Возвращает 0 при успехе.
 */
int spl_compute(const float *ir, int irLen, int sampleRate,
                spl_result_t *result);

/*
 * Вычислить SPL с заданным размером FFT (zero-padding до fftSize).
 */
int spl_compute_with_fft_size(const float *ir, int irLen, int sampleRate,
                               int fftSize, spl_result_t *result);

/*
 * Применить fractional-octave сглаживание к SPL.
 *
 * spl:        массив SPL (дБ), модифицируется in-place
 * frequencies: массив частот (Гц)
 * numBins:    количество бинов
 * lowFreq:    граница переключения smoothing (Гц, например 200)
 * lowFraction: дробь октавы для НЧ (например, 1.0/48.0)
 * highFraction: дробь октавы для ВЧ (например, 1.0/3.0)
 */
void spl_smoothing_variable(float *spl, const float *frequencies,
                             int numBins, float lowFreq,
                             float lowFraction, float highFraction);

/*
 * Применить сглаживание заданного типа.
 *
 * spl:        массив SPL (дБ), модифицируется in-place
 * frequencies: массив частот (Гц)
 * numBins:    количество бинов
 * type:       тип сглаживания:
 *             0 = none (без сглаживания)
 *             1 = 1/1 octave, 2 = 1/2, 3 = 1/3, 4 = 1/6,
 *             5 = 1/12, 6 = 1/24, 7 = 1/48
 *             8 = variable (1/48 на НЧ, 1/3 на ВЧ)
 *             9 = psychoacoustic
 *             10 = ERB
 */
void spl_smoothing_by_type(float *spl, const float *frequencies,
                           int numBins, int type);

/*
 * Освободить память, выделенную в spl_compute.
 */
void spl_free(spl_result_t *result);

#ifdef __cplusplus
}
#endif

#endif /* SPL_RESPONSE_H */
