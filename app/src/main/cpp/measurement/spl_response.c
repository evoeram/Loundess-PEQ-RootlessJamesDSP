/*
 * spl_response.c — Вычисление SPL из импульсной характеристики.
 *
 * Pipeline:
 *  1. FFT windowed IR → комплексный спектр
 *  2. Magnitude → dB: SPL(f) = 20*log10(|H(f)|)
 *  3. Phase: arg(H(f)) в градусах
 *  4. Опционально: variable fractional-octave сглаживание
 */

#include "spl_response.h"
#include "kiss_fft.h"
#include "kiss_fftr.h"
#include <stdlib.h>
#include <string.h>
#include <math.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

static int next_power_of_two(int n)
{
    int p = 1;
    while (p < n) p <<= 1;
    return p;
}

int spl_compute_with_fft_size(const float *ir, int irLen, int sampleRate,
                               int fftSize, spl_result_t *result)
{
    if (!ir || irLen <= 0 || !result) return -1;
    if (fftSize <= 0) fftSize = next_power_of_two(irLen);
    if (fftSize & 1) fftSize <<= 1; /* kiss_fftr требует чётный размер */

    int numBins = fftSize / 2 + 1;

    /* Выделение буферов.
     * timeBuffer передаётся в kiss_fftr, поэтому должен быть kiss_fft_scalar
     * (по умолчанию double). */
    kiss_fft_scalar *timeBuffer = (kiss_fft_scalar *)calloc(fftSize, sizeof(kiss_fft_scalar));
    kiss_fft_cpx *freqData = (kiss_fft_cpx *)malloc(numBins * sizeof(kiss_fft_cpx));
    float *freqs = (float *)malloc(numBins * sizeof(float));
    float *spl = (float *)malloc(numBins * sizeof(float));
    float *phase = (float *)malloc(numBins * sizeof(float));

    if (!timeBuffer || !freqData || !freqs || !spl || !phase) {
        free(timeBuffer); free(freqData); free(freqs); free(spl); free(phase);
        return -2;
    }

    /* Копирование IR с zero-padding.
     * ir — float, timeBuffer — kiss_fft_scalar (double), конвертируем поэлементно. */
    int copyLen = (irLen < fftSize) ? irLen : fftSize;
    for (int i = 0; i < copyLen; i++) {
        timeBuffer[i] = (kiss_fft_scalar)ir[i];
    }

    /* FFT */
    kiss_fftr_cfg cfg = kiss_fftr_alloc(fftSize, 0, NULL, NULL);
    if (!cfg) {
        free(timeBuffer); free(freqData); free(freqs); free(spl); free(phase);
        return -3;
    }
    kiss_fftr(cfg, timeBuffer, freqData);

    /* Вычисление magnitude и phase */
    for (int i = 0; i < numBins; i++) {
        double re = (double)freqData[i].r;
        double im = (double)freqData[i].i;
        double mag = sqrt(re * re + im * im);

        /* SPL в дБ. Добавляем малый epsilon чтобы избежать log(0). */
        spl[i] = (float)(20.0 * log10(mag + 1e-20));

        /* Phase в градусах */
        double ph = atan2(im, re);
        phase[i] = (float)(ph * 180.0 / M_PI);

        /* Частота бина */
        freqs[i] = (float)((double)i * sampleRate / fftSize);
    }

    /* Заполнение результата */
    result->frequencies = freqs;
    result->spl = spl;
    result->phase = phase;
    result->numBins = numBins;
    result->fftSize = fftSize;
    result->sampleRate = sampleRate;

    /* Очистка */
    free(timeBuffer);
    free(freqData);
    kiss_fftr_free(cfg);

    return 0;
}

int spl_compute(const float *ir, int irLen, int sampleRate,
                spl_result_t *result)
{
    return spl_compute_with_fft_size(ir, irLen, sampleRate, 0, result);
}

/*
 * Fractional-octave сглаживание.
 *
 * Для каждого частотного бина f_i вычисляется среднее значение SPL
 * в полосе [f_i / r, f_i * r], где r = 2^(fraction/2).
 *
 * На НЧ (< lowFreq) используется lowFraction (например 1/48),
 * на ВЧ (>= lowFreq) — highFraction (например 1/3).
 */
void spl_smoothing_variable(float *spl, const float *frequencies,
                             int numBins, float lowFreq,
                             float lowFraction, float highFraction)
{
    if (!spl || !frequencies || numBins <= 0) return;

    float *smoothed = (float *)malloc(numBins * sizeof(float));
    if (!smoothed) return;

    for (int i = 0; i < numBins; i++) {
        float f = frequencies[i];
        if (f < 1.0f) f = 1.0f;

        /* Выбор дроби октавы в зависимости от частоты */
        float fraction = (f < lowFreq) ? lowFraction : highFraction;

        /* Полоса сглаживания: [f/r, f*r], r = 2^(fraction/2) */
        double r = pow(2.0, fraction / 2.0);
        float fLow = (float)(f / r);
        float fHigh = (float)(f * r);

        /* Поиск индексов бинов в полосе */
        int iLow = (int)(fLow * numBins * 2.0 / frequencies[numBins - 1]);
        int iHigh = (int)(fHigh * numBins * 2.0 / frequencies[numBins - 1]);
        if (iLow < 0) iLow = 0;
        if (iHigh >= numBins) iHigh = numBins - 1;
        if (iHigh <= iLow) {
            smoothed[i] = spl[i];
            continue;
        }

        /* Среднее по полосе (в дБ — это energy average) */
        double sum = 0.0;
        int count = 0;
        for (int j = iLow; j <= iHigh; j++) {
            if (frequencies[j] >= fLow && frequencies[j] <= fHigh) {
                sum += spl[j];
                count++;
            }
        }
        smoothed[i] = (count > 0) ? (float)(sum / count) : spl[i];
    }

    memcpy(spl, smoothed, numBins * sizeof(float));
    free(smoothed);
}

/*
 * Fixed fractional-octave сглаживание.
 * fraction = 1.0/N (например 1/3 → fraction = 1.0/3.0).
 * Полоса: [f / 2^(1/(2N)), f * 2^(1/(2N))].
 */
static void spl_smoothing_fixed(float *spl, const float *frequencies,
                                int numBins, double fraction)
{
    if (!spl || !frequencies || numBins <= 0) return;

    float *smoothed = (float *)malloc(numBins * sizeof(float));
    if (!smoothed) return;

    double r = pow(2.0, fraction / 2.0);

    for (int i = 0; i < numBins; i++) {
        float f = frequencies[i];
        if (f < 1.0f) f = 1.0f;

        float fLow = (float)(f / r);
        float fHigh = (float)(f * r);

        /* Двоичный поиск нижней и верхней границы */
        int iLow = 0, iHigh = numBins - 1;
        /* Линейный поиск (бины упорядочены по частоте) */
        for (int j = i; j >= 0 && frequencies[j] >= fLow; j--) iLow = j;
        for (int j = i; j < numBins && frequencies[j] <= fHigh; j++) iHigh = j;

        if (iHigh <= iLow) {
            smoothed[i] = spl[i];
            continue;
        }

        double sum = 0.0;
        int count = 0;
        for (int j = iLow; j <= iHigh; j++) {
            sum += spl[j];
            count++;
        }
        smoothed[i] = (count > 0) ? (float)(sum / count) : spl[i];
    }

    memcpy(spl, smoothed, numBins * sizeof(float));
    free(smoothed);
}

/*
 * Psychoacoustic сглаживание (критические полосы слуха).
 *
 * Использует формулу bark = 13*atan(0.00076*f) + 3.5*atan((f/7500)^2).
 * Сглаживание в пределах одной критической полосы (ширина ~1 барк).
 * Каждая барк-полоса усредняется, результат интерполируется обратно.
 */
static void spl_smoothing_psychoacoustic(float *spl, const float *frequencies,
                                          int numBins)
{
    if (!spl || !frequencies || numBins <= 0) return;

    float *smoothed = (float *)malloc(numBins * sizeof(float));
    if (!smoothed) return;

    /* Функция перевода Гц → барк */
    #define HZ_TO_BARK(f) (13.0 * atan(0.00076 * (f)) + 3.5 * atan(pow((f) / 7500.0, 2.0)))

    for (int i = 0; i < numBins; i++) {
        double f = frequencies[i];
        if (f < 1.0) f = 1.0;

        double barkCenter = HZ_TO_BARK(f);
        /* Ширина полосы сглаживания: 1 барк (±0.5 барк от центра) */
        double barkLow = barkCenter - 0.5;
        double barkHigh = barkCenter + 0.5;

        /* Поиск бинов в пределах полосы */
        double sum = 0.0;
        int count = 0;

        for (int j = 0; j < numBins; j++) {
            double fj = frequencies[j];
            if (fj < 1.0) fj = 1.0;
            double barkj = HZ_TO_BARK(fj);
            if (barkj >= barkLow && barkj <= barkHigh) {
                sum += spl[j];
                count++;
            }
        }

        smoothed[i] = (count > 0) ? (float)(sum / count) : spl[i];
    }

    #undef HZ_TO_BARK
    memcpy(spl, smoothed, numBins * sizeof(float));
    free(smoothed);
}

/*
 * ERB (Equivalent Rectangular Bandwidth) сглаживание.
 *
 * Использует формулу ERB(f) = 24.7 * (4.37 * f / 1000 + 1).
 * Сглаживание в пределах одной ERB-полосы.
 */
static void spl_smoothing_erb(float *spl, const float *frequencies,
                               int numBins)
{
    if (!spl || !frequencies || numBins <= 0) return;

    float *smoothed = (float *)malloc(numBins * sizeof(float));
    if (!smoothed) return;

    for (int i = 0; i < numBins; i++) {
        double f = frequencies[i];
        if (f < 1.0) f = 1.0;

        /* Ширина ERB-полосы на частоте f */
        double erb = 24.7 * (4.37 * f / 1000.0 + 1.0);
        /* Полоса сглаживания: f ± ERB/2 */
        double fLow = f - erb / 2.0;
        double fHigh = f + erb / 2.0;
        if (fLow < 1.0) fLow = 1.0;

        double sum = 0.0;
        int count = 0;

        for (int j = 0; j < numBins; j++) {
            double fj = frequencies[j];
            if (fj >= fLow && fj <= fHigh) {
                sum += spl[j];
                count++;
            }
        }

        smoothed[i] = (count > 0) ? (float)(sum / count) : spl[i];
    }

    memcpy(spl, smoothed, numBins * sizeof(float));
    free(smoothed);
}

/*
 * Применить сглаживание заданного типа.
 *
 * type: 0=none, 1=1/1, 2=1/2, 3=1/3, 4=1/6, 5=1/12, 6=1/24, 7=1/48,
 *       8=variable, 9=psychoacoustic, 10=ERB
 */
void spl_smoothing_by_type(float *spl, const float *frequencies,
                           int numBins, int type)
{
    if (!spl || !frequencies || numBins <= 0) return;

    switch (type) {
        case 0: /* none */
            break;
        case 1: /* 1/1 octave */
            spl_smoothing_fixed(spl, frequencies, numBins, 1.0);
            break;
        case 2: /* 1/2 octave */
            spl_smoothing_fixed(spl, frequencies, numBins, 1.0 / 2.0);
            break;
        case 3: /* 1/3 octave */
            spl_smoothing_fixed(spl, frequencies, numBins, 1.0 / 3.0);
            break;
        case 4: /* 1/6 octave */
            spl_smoothing_fixed(spl, frequencies, numBins, 1.0 / 6.0);
            break;
        case 5: /* 1/12 octave */
            spl_smoothing_fixed(spl, frequencies, numBins, 1.0 / 12.0);
            break;
        case 6: /* 1/24 octave */
            spl_smoothing_fixed(spl, frequencies, numBins, 1.0 / 24.0);
            break;
        case 7: /* 1/48 octave */
            spl_smoothing_fixed(spl, frequencies, numBins, 1.0 / 48.0);
            break;
        case 8: /* variable (1/48 на НЧ, 1/3 на ВЧ) */
            spl_smoothing_variable(spl, frequencies, numBins, 200.0f,
                                    1.0f / 48.0f, 1.0f / 3.0f);
            break;
        case 9: /* psychoacoustic */
            spl_smoothing_psychoacoustic(spl, frequencies, numBins);
            break;
        case 10: /* ERB */
            spl_smoothing_erb(spl, frequencies, numBins);
            break;
        default:
            break;
    }
}

void spl_free(spl_result_t *result)
{
    if (!result) return;
    if (result->frequencies) { free(result->frequencies); result->frequencies = NULL; }
    if (result->spl) { free(result->spl); result->spl = NULL; }
    if (result->phase) { free(result->phase); result->phase = NULL; }
}
