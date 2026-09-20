/*
 * farina_deconv.h — Деконволюция логарифмического свипа методом Фарины.
 *
 * Выполняет:
 *  1. FFT записанного сигнала r(t) и обратного фильтра s_inv(t)
 *  2. Умножение в частотной области: H(f) = FFT(r) · FFT(s_inv)
 *  3. IFFT → h(t) — импульсная характеристика
 *  4. Поиск первого пика (прямой звук) → сдвиг t0 в начало
 *  5. Обрезка THD (t < 0 после сдвига) левым окном Tukey
 *
 * Использует kissfft (уже в репозитории).
 */

#ifndef FARINA_DECONV_H
#define FARINA_DECONV_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Результат деконволюции.
 */
typedef struct {
    float *ir;            /* Импульсная характеристика (длина fftSize) */
    int fftSize;          /* Размер FFT (степень двойки) */
    int t0Sample;         /* Индекс сэмпла прямого звука (до сдвига) */
    int irValidLen;       /* Длина полезной части IR после обрезки */
    int sampleRate;       /* Частота дискретизации */
} deconv_result_t;

/*
 * Выполнить деконволюцию методом Фарины.
 *
 * recorded:   записанный PCM (длина numRecorded)
 * inverseFilter: обратный фильтр s_inv(t) (длина numFilter)
 * numRecorded:  количество сэмплов записи
 * numFilter:    количество сэмплов обратного фильтра
 * sampleRate:   частота дискретизации
 * result:       выходная структура (ir выделяется malloc'ом, освободить через deconv_free)
 *
 * fftSize выбирается как следующая степень двойки >= numRecorded + numFilter - 1.
 *
 * Возвращает 0 при успехе, отрицательный код при ошибке.
 */
int farina_deconvolve(const float *recorded,
                      const float *inverseFilter,
                      int numRecorded,
                      int numFilter,
                      int sampleRate,
                      deconv_result_t *result);

/*
 * Освободить память, выделенную в farina_deconvolve.
 */
void deconv_free(deconv_result_t *result);

#ifdef __cplusplus
}
#endif

#endif /* FARINA_DECONV_H */
