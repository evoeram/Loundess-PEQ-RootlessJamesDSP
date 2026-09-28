/*
 * farina_deconv.h — Деконволюция логарифмического свипа методом Фарины.
 *
 * Выполняет:
 *  1. FFT записанного сигнала r(t) и обратного фильтра s_inv(t)
 *  2. Умножение в частотной области: H(f) = FFT(r) · FFT(s_inv)
 *  3. IFFT → h(t) — импульсная характеристика
 *  4. Поиск первого пика (прямой звук) → сдвиг t0 в начало
 *  5. Обрезка THD (t < 0 после сдвига) левым окном Tukey (опционально)
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
    float *ir;            /* Импульсная характеристика (длина irValidLen) */
    int fftSize;          /* Размер FFT (степень двойки) */
    int t0Sample;         /* Индекс сэмпла прямого звука (до сдвига) */
    int irValidLen;       /* Длина полезной части IR после обрезки */
    int sampleRate;       /* Частота дискретизации */
} deconv_result_t;

/*
 * Режим оконной обработки IR.
 */
typedef enum {
    IR_WINDOW_NONE = 0,       /* Без окон */
    IR_WINDOW_LEFT_RIGHT = 1, /* Левое Tukey + правое Hann (по умолчанию) */
    IR_WINDOW_HANN = 2,       /* Полное окно Hann */
    IR_WINDOW_TUKEY = 3,      /* Полное окно Tukey (alpha=0.25) */
} ir_window_mode_t;

/*
 * Конфигурация деконволюции.
 */
typedef struct {
    int normalizeIr;           /* 1 = нормализовать IR к пику 1.0, 0 = сохранить масштаб */
    ir_window_mode_t windowMode; /* Режим окон */
    int maxIrLenMs;            /* Максимальная длина IR в мс (0 = без обрезки) */
    double arrivalThreshold;   /* Порог поиска прямого звука (0..1 от пика, 0=авто/поиск пика) */
    int leftWindowMs;          /* Длина левого Tukey окна в мс (для LEFT_RIGHT) */
    int rightWindowPercent;    /* Процент правого Hann окна от длины IR (для LEFT_RIGHT) */
} deconv_config_t;

/*
 * Выполнить деконволюцию методом Фарины с конфигурацией.
 *
 * recorded:      записанный PCM (длина numRecorded)
 * inverseFilter: обратный фильтр s_inv(t) (длина numFilter)
 * numRecorded:   количество сэмплов записи
 * numFilter:     количество сэмплов обратного фильтра
 * sampleRate:    частота дискретизации
 * config:        конфигурация (normalize, windows, maxIrLen, и т.д.)
 * result:        выходная структура (ir выделяется malloc'ом, освободить через deconv_free)
 *
 * Возвращает 0 при успехе, отрицательный код при ошибке.
 */
int farina_deconvolve_ex(const float *recorded,
                          const float *inverseFilter,
                          int numRecorded,
                          int numFilter,
                          int sampleRate,
                          const deconv_config_t *config,
                          deconv_result_t *result);

/*
 * Обратная совместимость: вызывает farina_deconvolve_ex с настройками по умолчанию.
 */
int farina_deconvolve(const float *recorded,
                      const float *inverseFilter,
                      int numRecorded,
                      int numFilter,
                      int sampleRate,
                      deconv_result_t *result);

/*
 * Освободить память, выделенную в farina_deconvolve / farina_deconvolve_ex.
 */
void deconv_free(deconv_result_t *result);

#ifdef __cplusplus
}
#endif

#endif /* FARINA_DECONV_H */
