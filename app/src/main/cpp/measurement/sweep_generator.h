/*
 * sweep_generator.h — Генератор логарифмического синусоидального свипа
 * для акустических измерений методом Фарины.
 *
 * Log sine sweep: частота растёт экспоненциально от f1 до f2 за время T.
 * Мгновенная частота: f(t) = f1 * exp(beta * t), где beta = ln(f2/f1) / T.
 * Фаза: phi(t) = 2*pi*f1/beta * (exp(beta*t) - 1).
 * Сигнал: s(t) = sin(phi(t)).
 *
 * Спектр log sweep имеет спад -3 dB/octave (розовый шум).
 * Обратный фильтр Фарины компенсирует этот спад.
 */

#ifndef SWEEP_GENERATOR_H
#define SWEEP_GENERATOR_H

#include <stdint.h>
#include <stddef.h>
#include <math.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Параметры логарифмического свипа.
 */
typedef struct {
    double f1;        /* Начальная частота, Гц (например, 20) */
    double f2;        /* Конечная частота, Гц (например, 20000) */
    double duration;  /* Длительность, секунды (например, 5.0) */
    int sampleRate;   /* Частота дискретизации, Гц (например, 48000) */
    /* Производные параметры (вычисляются в sweep_init) */
    double beta;      /* Скорость роста частоты: ln(f2/f1) / T */
    int numSamples;   /* Количество сэмплов: duration * sampleRate */
} sweep_params_t;

/*
 * Инициализация параметров свипа.
 * Заполняет производные поля (beta, numSamples).
 */
void sweep_init(sweep_params_t *params,
                double f1, double f2, double duration, int sampleRate);

/*
 * Генерация логарифмического свипа.
 * buffer: выходной массив длиной params->numSamples.
 *         Должен быть выделен вызывающим кодом.
 *
 * s(t) = sin(2*pi*f1/beta * (exp(beta*t) - 1))
 */
void sweep_generate(const sweep_params_t *params, float *buffer);

/*
 * Генерация обратного фильтра Фарины.
 *
 * s_inv(t) = s(T - t) * (f1 / f(T-t))
 *          = s(T - t) * (f1 / (f2 * exp(-beta * t)))
 *          = s(T - t) * exp(beta * t) * (f1 / f2)
 *
 * Это time-reversed sweep с амплитудной модуляцией +6 dB/octave,
 * которая компенсирует спад -3 dB/octave спектра log sweep.
 *
 * buffer: выходной массив длиной params->numSamples.
 */
void sweep_generate_inverse_filter(const sweep_params_t *params, float *buffer);

/*
 * Получить мгновенную частоту свипа в момент времени t.
 * f(t) = f1 * exp(beta * t)
 */
static inline double sweep_instantaneous_freq(const sweep_params_t *params, double t)
{
    return params->f1 * exp(params->beta * t);
}

#ifdef __cplusplus
}
#endif

#endif /* SWEEP_GENERATOR_H */
