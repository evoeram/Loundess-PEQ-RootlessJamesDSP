/*
 * sweep_generator.c — Реализация генератора логарифмического свипа
 * и обратного фильтра Фарины.
 */

#include "sweep_generator.h"
#include <string.h>

void sweep_init(sweep_params_t *params,
                double f1, double f2, double duration, int sampleRate)
{
    params->f1 = f1;
    params->f2 = f2;
    params->duration = duration;
    params->sampleRate = sampleRate;
    /* beta = ln(f2/f1) / T — скорость экспоненциального роста частоты */
    params->beta = log(f2 / f1) / duration;
    params->numSamples = (int)(duration * sampleRate + 0.5);
}

void sweep_generate(const sweep_params_t *params, float *buffer)
{
    const double dt = 1.0 / params->sampleRate;
    const double two_pi_f1_over_beta = 2.0 * M_PI * params->f1 / params->beta;

    for (int i = 0; i < params->numSamples; i++) {
        double t = i * dt;
        /* phi(t) = 2*pi*f1/beta * (exp(beta*t) - 1) */
        double phase = two_pi_f1_over_beta * (exp(params->beta * t) - 1.0);
        buffer[i] = (float)sin(phase);
    }
}

void sweep_generate_inverse_filter(const sweep_params_t *params, float *buffer)
{
    const double dt = 1.0 / params->sampleRate;
    const double two_pi_f1_over_beta = 2.0 * M_PI * params->f1 / params->beta;
    const double f2_over_f1 = params->f2 / params->f1;

    for (int i = 0; i < params->numSamples; i++) {
        double t = i * dt;
        double t_rev = params->duration - t;  /* Обращение во времени */

        /* Исходный сигнал в обращённом времени: s(T - t) */
        double phase_rev = two_pi_f1_over_beta * (exp(params->beta * t_rev) - 1.0);
        double s_rev = sin(phase_rev);

        /* Амплитудная модуляция: f(T-t) / f1 = (f2/f1) * exp(-beta*t)
         *
         * Лог-sweep имеет спектр |S(f)| ∝ 1/√f (мощность ∝ 1/f).
         * Обратный фильтр во времени s(T-t) имеет тот же спектр |S*(f)| ∝ 1/√f.
         * Чтобы получить плоский результат |S(f)|·|K(f)| = const,
         * нужна амплитудная модуляция AMP(f) ∝ f.
         *
         * В момент t обратный фильтр генерирует частоту f(T-t) = f1·exp(beta·(T-t)).
         * AMP(f(T-t)) ∝ f(T-t) → amp_mod(t) = f(T-t)/f1 = (f2/f1)·exp(-beta·t).
         *
         * При t=0: amp = f2/f1 (ВЧ усиливаются), частота = f2
         * При t=T: amp = 1.0 (НЧ не трогаются), частота = f1
         *
         * ВАЖНО: знак экспоненты ОТРИЦАТЕЛЬНЫЙ (-beta·t).
         * Положительный знак давал |K(f)| ∝ 1/f^(3/2) → результат ∝ 1/f²,
         * что создавало гигантский спад (+48 dB на 100 Гц, -108 dB на 20 кГц). */
        double amp_mod = exp(-params->beta * t) * f2_over_f1;

        buffer[i] = (float)(s_rev * amp_mod);
    }
}
