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

/* Поиск индекса первого значимого пика в IR.
 * Идём от начала, ищем первый сэмпл, превышающий порог
 * относительно глобального максимума.
 * Возвращает индекс этого сэмпла. */
static int find_first_arrival(const float *ir, int len, double thresholdRatio)
{
    /* Находим глобальный максимум амплитуды */
    float maxAbs = 0.0f;
    for (int i = 0; i < len; i++) {
        float a = fabsf(ir[i]);
        if (a > maxAbs) maxAbs = a;
    }

    if (maxAbs < 1e-12f) return 0;

    float threshold = (float)(maxAbs * thresholdRatio);

    /* Ищем первый сэмпл выше порога — это приход прямой волны.
     * Используем порог 0.2 (т.е. -14 dB от пика) — достаточно
     * консервативно, чтобы не поймать шум, но поймать первый折射. */
    for (int i = 0; i < len; i++) {
        if (fabsf(ir[i]) >= threshold) return i;
    }

    return 0;
}

int farina_deconvolve(const float *recorded,
                      const float *inverseFilter,
                      int numRecorded,
                      int numFilter,
                      int sampleRate,
                      deconv_result_t *result)
{
    if (!recorded || !inverseFilter || !result) return -1;
    if (numRecorded <= 0 || numFilter <= 0) return -2;

    /* Размер FFT: следующая степень двойки >= numRecorded + numFilter - 1
     * (длина линейной свёртки). Для overlap-save можно меньше, но
     * для MVP делаем один блок. */
    int convLen = numRecorded + numFilter - 1;
    int fftSize = next_power_of_two(convLen);
    /* kiss_fftr требует чётный размер */
    if (fftSize & 1) fftSize <<= 1;

    /* Выделение буферов.
     * ВНИМАНИЕ: kiss_fft_scalar по умолчанию = double.
     * timeBuffer и irRaw передаются в kiss_fftr/kiss_fftri,
     * поэтому их тип должен быть kiss_fft_scalar, а не float. */
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

    /* Конфигурация kissfft (real FFT) */
    kiss_fftr_cfg cfgForward = kiss_fftr_alloc(fftSize, 0, NULL, NULL);
    kiss_fftr_cfg cfgInverse = kiss_fftr_alloc(fftSize, 1, NULL, NULL);

    if (!cfgForward || !cfgInverse) {
        free(timeBuffer); free(freqRecorded); free(freqInverse);
        free(freqProduct); free(irRaw);
        if (cfgForward) kiss_fftr_free(cfgForward);
        if (cfgInverse) kiss_fftr_free(cfgInverse);
        return -4;
    }

    /* 1. FFT записанного сигнала (zero-padded).
     * recorded — float, timeBuffer — kiss_fft_scalar (double), конвертируем. */
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
    /* Нормализация: kiss_fftri не делит на N */
    double norm = 1.0 / fftSize;
    for (int i = 0; i < fftSize; i++) {
        irRaw[i] *= norm;
    }

    /* 5. Поиск первого прихода прямой волны.
     * irRaw — kiss_fft_scalar (double), find_first_arrival ожидает float*.
     * Конвертируем во временный float-массив. */
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
    int t0 = find_first_arrival(irFloat, fftSize, 0.2);

    /* 6. Сдвиг IR так, чтобы t0 был в начале + обрезка THD левым окном.
     * THD при log sweep попадает в t < 0 (т.е. перед прямым звуком).
     * После сдвига t0 → 0, THD оказывается в отрицательных индексах
     * и отбрасывается.
     * Ограничиваем длину IR до maxIrLen сэмплов (~340 мс при 48 кГц)
     * — достаточно для акустических измерений в помещении, и сильно
     * ускоряет последующий SPL FFT. */
    int maxIrLen = sampleRate / 3; /* ~333 мс */
    int irValidLen = fftSize - t0;
    if (irValidLen > maxIrLen) irValidLen = maxIrLen;
    if (irValidLen <= 0) irValidLen = fftSize;

    float *irShifted = (float *)malloc(irValidLen * sizeof(float));
    if (!irShifted) {
        free(timeBuffer); free(freqRecorded); free(freqInverse);
        free(freqProduct); free(irRaw);
        kiss_fftr_free(cfgForward); kiss_fftr_free(cfgInverse);
        return -5;
    }

    /* Сдвиг: копируем начиная с t0.
     * irRaw — kiss_fft_scalar (double), irShifted — float, конвертируем. */
    memset(irShifted, 0, irValidLen * sizeof(float));
    int copyStart = t0;
    int copyEnd = (t0 + irValidLen < fftSize) ? (t0 + irValidLen) : fftSize;
    for (int i = copyStart; i < copyEnd; i++) {
        irShifted[i - copyStart] = (float)irRaw[i];
    }

    /* 7. Применение окон: левое Tukey (обрезка остатков THD) + правое Hann.
     * Длительность левого окна: ~5 мс (типичная для обрезки THD).
     * Длительность правого окна: последние 10% IR. */
    int leftWindowSamples = (int)(0.005 * sampleRate); /* 5 мс */
    if (leftWindowSamples > irValidLen / 4) leftWindowSamples = irValidLen / 4;
    int rightWindowStart = irValidLen - irValidLen / 10; /* последние 10% */

    ir_apply_tukey_left_hann_right(irShifted, irValidLen,
                                    leftWindowSamples, rightWindowStart);

    /* 8. Нормализация IR к пиковому значению = 1.0 (0 dBFS).
     * Без этого SPL имеет произвольный сдвиг по уровню. */
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

    /* Заполнение результата */
    result->ir = irShifted;
    result->fftSize = fftSize;
    result->t0Sample = t0;
    result->irValidLen = irValidLen;
    result->sampleRate = sampleRate;

    /* Очистка */
    free(timeBuffer); free(freqRecorded); free(freqInverse);
    free(freqProduct); free(irRaw); free(irFloat);
    kiss_fftr_free(cfgForward); kiss_fftr_free(cfgInverse);

    return 0;
}

void deconv_free(deconv_result_t *result)
{
    if (result && result->ir) {
        free(result->ir);
        result->ir = NULL;
    }
}
