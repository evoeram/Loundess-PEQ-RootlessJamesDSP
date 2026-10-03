/*
 * farina_deconv.c — Реализация деконволюции методом Фарины.
 *
 * Pipeline:
 *  1. FFT записанного сигнала и обратного фильтра (kissfft, real FFT)
 *  2. Умножение спектров: H(f) = R(f) · S_inv(f)
 *  3. IFFT → h(t) — сырая импульсная характеристика
 *  4. Поиск первого значимого пика (прямой звук) → t0
 *  5. Сдвиг так, чтобы t0 был в начале
 *  6. Левое окно Tukey (обрезка THD, попавшего в t < 0 после сдвига)
 */

#include "farina_deconv.h"
#include "sweep_generator.h"
#include "ir_windowing.h"

/* kissfft — уже в репозитории */
#include "kiss_fft.h"
#include "kiss_fftr.h"

#include <stdlib.h>
#include <string.h>
#include <math.h>

/* Поиск следующей степени двойки >= n */
static int next_power_of_two(int n)
{
    int p = 1;
    while (p < n) p <<= 1;
    return p;
}

/*
 * Поиск первого прихода прямой волны — улучшенный алгоритм.
 *
 * 1. Находим глобальный максимум амплитуды (это прямой звук или близкое отражение).
 * 2. Если arrivalThreshold > 0: идём от начала, ищем первый сэмпл >= threshold * maxAbs.
 * 3. Если arrivalThreshold == 0 (авто): от глобального максимума идём назад,
 *    ищем начало импульса (где амплитуда падает ниже 10% от пика).
 *    Это более точно, чем простой порог — не ловит шум перед прямым звуком.
 */
static int find_first_arrival(const float *ir, int len, double thresholdRatio)
{
    float maxAbs = 0.0f;
    int maxIdx = 0;
    for (int i = 0; i < len; i++) {
        float a = fabsf(ir[i]);
        if (a > maxAbs) {
            maxAbs = a;
            maxIdx = i;
        }
    }

    if (maxAbs < 1e-12f) return 0;

    if (thresholdRatio > 0.0) {
        float threshold = (float)(maxAbs * thresholdRatio);
        for (int i = 0; i < len; i++) {
            if (fabsf(ir[i]) >= threshold) return i;
        }
        return 0;
    } else {
        /* Авто-режим: от глобального максимума идём назад,
         * ищем начало импульса (где амплитуда падает ниже 10% от пика). */
        float threshold = maxAbs * 0.1f;
        int start = maxIdx;
        for (int i = maxIdx; i >= 0; i--) {
            if (fabsf(ir[i]) < threshold) {
                start = i + 1;
                break;
            }
            start = i;
        }
        return start;
    }
}

int farina_deconvolve_ex(const float *recorded,
                          const float *inverseFilter,
                          int numRecorded,
                          int numFilter,
                          int sampleRate,
                          const deconv_config_t *config,
                          deconv_result_t *result)
{
    if (!recorded || !inverseFilter || !result || !config) return -1;
    if (numRecorded <= 0 || numFilter <= 0) return -2;

    int convLen = numRecorded + numFilter - 1;
    int fftSize = next_power_of_two(convLen);
    if (fftSize & 1) fftSize <<= 1;

    kiss_fft_scalar *timeBuffer = (kiss_fft_scalar *)malloc(fftSize * sizeof(kiss_fft_scalar));
    kiss_fft_cpx *freqRecorded = (kiss_fft_cpx *)malloc((fftSize / 2 + 1) * sizeof(kiss_fft_cpx));
    kiss_fft_cpx *freqInverse = (kiss_fft_cpx *)malloc((fftSize / 2 + 1) * sizeof(kiss_fft_cpx));
    kiss_fft_cpx *freqProduct = (kiss_fft_cpx *)malloc((fftSize / 2 + 1) * sizeof(kiss_fft_cpx));
    kiss_fft_scalar *irRaw = (kiss_fft_scalar *)malloc(fftSize * sizeof(kiss_fft_scalar));

    if (!timeBuffer || !freqRecorded || !freqInverse || !freqProduct || !irRaw) {
        free(timeBuffer); free(freqRecorded); free(freqInverse);
        free(freqProduct); free(irRaw);
        return -3;
    }

    kiss_fftr_cfg cfgForward = kiss_fftr_alloc(fftSize, 0, NULL, NULL);
    kiss_fftr_cfg cfgInverse = kiss_fftr_alloc(fftSize, 1, NULL, NULL);

    if (!cfgForward || !cfgInverse) {
        free(timeBuffer); free(freqRecorded); free(freqInverse);
        free(freqProduct); free(irRaw);
        if (cfgForward) kiss_fftr_free(cfgForward);
        if (cfgInverse) kiss_fftr_free(cfgInverse);
        return -4;
    }

    /* 1. FFT записанного сигнала (zero-padded) */
    memset(timeBuffer, 0, fftSize * sizeof(kiss_fft_scalar));
    int copyLen = (numRecorded < fftSize) ? numRecorded : fftSize;
    for (int i = 0; i < copyLen; i++) {
        timeBuffer[i] = (kiss_fft_scalar)recorded[i];
    }
    kiss_fftr(cfgForward, timeBuffer, freqRecorded);

    /* 2. FFT обратного фильтра (zero-padded) */
    memset(timeBuffer, 0, fftSize * sizeof(kiss_fft_scalar));
    copyLen = (numFilter < fftSize) ? numFilter : fftSize;
    for (int i = 0; i < copyLen; i++) {
        timeBuffer[i] = (kiss_fft_scalar)inverseFilter[i];
    }
    kiss_fftr(cfgForward, timeBuffer, freqInverse);

    /* 3. Умножение спектров: H(f) = R(f) · S_inv(f) */
    int nBins = fftSize / 2 + 1;
    for (int i = 0; i < nBins; i++) {
        double re = (double)freqRecorded[i].r * freqInverse[i].r
                  - (double)freqRecorded[i].i * freqInverse[i].i;
        double im = (double)freqRecorded[i].r * freqInverse[i].i
                  + (double)freqRecorded[i].i * freqInverse[i].r;
        freqProduct[i].r = (kiss_fft_scalar)re;
        freqProduct[i].i = (kiss_fft_scalar)im;
    }

    /* 4. IFFT → сырая импульсная характеристика */
    kiss_fftri(cfgInverse, freqProduct, irRaw);
    double norm = 1.0 / fftSize;
    for (int i = 0; i < fftSize; i++) {
        irRaw[i] *= norm;
    }

    /* 5. Поиск первого прихода прямой волны */
    float *irFloat = (float *)malloc(fftSize * sizeof(float));
    if (!irFloat) {
        free(timeBuffer); free(freqRecorded); free(freqInverse);
        free(freqProduct); free(irRaw);
        kiss_fftr_free(cfgForward); kiss_fftr_free(cfgInverse);
        return -5;
    }
    for (int i = 0; i < fftSize; i++) {
        irFloat[i] = (float)irRaw[i];
    }
    int t0 = find_first_arrival(irFloat, fftSize, config->arrivalThreshold);
    free(irFloat);

    /* 6. Вычисление длины IR */
    int maxIrLen;
    if (config->maxIrLenMs > 0) {
        maxIrLen = config->maxIrLenMs * sampleRate / 1000;
    } else {
        maxIrLen = fftSize;
    }
    int irValidLen = fftSize - t0;
    if (irValidLen > maxIrLen) irValidLen = maxIrLen;
    if (irValidLen <= 0) irValidLen = fftSize;

    /* Сдвиг: копируем начиная с t0 */
    float *irShifted = (float *)malloc(irValidLen * sizeof(float));
    if (!irShifted) {
        free(timeBuffer); free(freqRecorded); free(freqInverse);
        free(freqProduct); free(irRaw);
        kiss_fftr_free(cfgForward); kiss_fftr_free(cfgInverse);
        return -5;
    }

    memset(irShifted, 0, irValidLen * sizeof(float));
    int copyStart = t0;
    int copyEnd = (t0 + irValidLen < fftSize) ? (t0 + irValidLen) : fftSize;
    for (int i = copyStart; i < copyEnd; i++) {
        irShifted[i - copyStart] = (float)irRaw[i];
    }

    /* 7. Оконное взвешивание (опционально) */
    switch (config->windowMode) {
        case IR_WINDOW_LEFT_RIGHT: {
            int leftWindowSamples = config->leftWindowMs * sampleRate / 1000;
            if (leftWindowSamples <= 0) leftWindowSamples = (int)(0.005 * sampleRate);
            if (leftWindowSamples > irValidLen / 4) leftWindowSamples = irValidLen / 4;
            int rightWindowStart = irValidLen - irValidLen * config->rightWindowPercent / 100;
            if (config->rightWindowPercent <= 0) rightWindowStart = irValidLen - irValidLen / 10;
            if (rightWindowStart < leftWindowSamples) rightWindowStart = leftWindowSamples;
            ir_apply_tukey_left_hann_right(irShifted, irValidLen,
                                            leftWindowSamples, rightWindowStart);
            break;
        }
        case IR_WINDOW_HANN:
            ir_apply_hann(irShifted, irValidLen);
            break;
        case IR_WINDOW_TUKEY:
            ir_apply_tukey(irShifted, irValidLen, 0.25);
            break;
        case IR_WINDOW_NONE:
        default:
            break;
    }

    /* 8. Опциональная нормализация IR к пиковому значению = 1.0 */
    if (config->normalizeIr) {
        float peakAbs = 0.0f;
        for (int i = 0; i < irValidLen; i++) {
            float a = fabsf(irShifted[i]);
            if (a > peakAbs) peakAbs = a;
        }
        if (peakAbs > 1e-12f) {
            float invPeak = 1.0f / peakAbs;
            for (int i = 0; i < irValidLen; i++) {
                irShifted[i] *= invPeak;
            }
        }
    }

    /* Заполнение результата */
    result->ir = irShifted;
    result->fftSize = fftSize;
    result->t0Sample = t0;
    result->irValidLen = irValidLen;
    result->sampleRate = sampleRate;

    free(timeBuffer); free(freqRecorded); free(freqInverse);
    free(freqProduct); free(irRaw);
    kiss_fftr_free(cfgForward); kiss_fftr_free(cfgInverse);

    return 0;
}

/* Обратная совместимость: настройки по умолчанию */
int farina_deconvolve(const float *recorded,
                      const float *inverseFilter,
                      int numRecorded,
                      int numFilter,
                      int sampleRate,
                      deconv_result_t *result)
{
    deconv_config_t config;
    config.normalizeIr = 1;
    config.windowMode = IR_WINDOW_LEFT_RIGHT;
    config.maxIrLenMs = 333;
    config.arrivalThreshold = 0.0;
    config.leftWindowMs = 5;
    config.rightWindowPercent = 10;
    return farina_deconvolve_ex(recorded, inverseFilter, numRecorded, numFilter,
                                 sampleRate, &config, result);
}

void deconv_free(deconv_result_t *result)
{
    if (result && result->ir) {
        free(result->ir);
        result->ir = NULL;
    }
}
