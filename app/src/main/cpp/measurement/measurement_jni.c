/*
 * measurement_jni.c — JNI мост между Kotlin и C/NDK measurement engine.
 *
 * Экспортирует функции для:
 *  1. Генерации sweep + inverse filter
 *  2. Деконволюции (запись → IR)
 *  3. Вычисления SPL из IR
 *  4. Сглаживания
 *
 * Kotlin вызывает эти функции через System.loadLibrary("measurement").
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <android/log.h>

#include "sweep_generator.h"
#include "farina_deconv.h"
#include "ir_windowing.h"
#include "spl_response.h"
#include "kiss_fft.h"
#include "kiss_fftr.h"

#define TAG "MeasurementJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ===== Sweep generation ===== */

/*
 * Kotlin: external fun generateSweep(f1: Double, f2: Double, duration: Double,
 *                                     sampleRate: Int): FloatArray
 */
JNIEXPORT jfloatArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_measurement_MeasurementNativeEngine_generateSweep(
        JNIEnv *env, jobject thiz,
        jdouble f1, jdouble f2, jdouble duration, jint sampleRate)
{
    sweep_params_t params;
    sweep_init(&params, f1, f2, duration, sampleRate);

    jfloatArray result = (*env)->NewFloatArray(env, params.numSamples);
    if (!result) {
        LOGE("generateSweep: failed to allocate float array");
        return NULL;
    }

    float *buffer = (float *)malloc(params.numSamples * sizeof(float));
    if (!buffer) {
        LOGE("generateSweep: failed to allocate buffer");
        return NULL;
    }

    sweep_generate(&params, buffer);
    (*env)->SetFloatArrayRegion(env, result, 0, params.numSamples, buffer);
    free(buffer);
    return result;
}

/*
 * Kotlin: external fun generateInverseFilter(f1: Double, f2: Double, duration: Double,
 *                                             sampleRate: Int): FloatArray
 */
JNIEXPORT jfloatArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_measurement_MeasurementNativeEngine_generateInverseFilter(
        JNIEnv *env, jobject thiz,
        jdouble f1, jdouble f2, jdouble duration, jint sampleRate)
{
    sweep_params_t params;
    sweep_init(&params, f1, f2, duration, sampleRate);

    jfloatArray result = (*env)->NewFloatArray(env, params.numSamples);
    if (!result) {
        LOGE("generateInverseFilter: failed to allocate float array");
        return NULL;
    }

    float *buffer = (float *)malloc(params.numSamples * sizeof(float));
    if (!buffer) {
        LOGE("generateInverseFilter: failed to allocate buffer");
        return NULL;
    }

    sweep_generate_inverse_filter(&params, buffer);
    (*env)->SetFloatArrayRegion(env, result, 0, params.numSamples, buffer);
    free(buffer);
    return result;
}

/* ===== Deconvolution ===== */

/* Структура для хранения нативного контекста между JNI вызовами */
typedef struct {
    deconv_result_t deconv;
    spl_result_t spl;
    int hasSpl;
    /* THD data */
    float *thd;            /* THD в процентах (0-100) */
    float *thdFrequencies; /* Частоты для THD */
    int thdNumBins;
    int hasThd;
    /* RT60 data */
    float *rt60;           /* RT60 в секундах per octave band */
    float *rt60Frequencies;/* Центральные частоты octave bands */
    int rt60NumBands;
    int hasRt60;
    /* Group delay */
    float *groupDelay;     /* Group delay в секундах */
    int gdNumBins;
    int hasGroupDelay;
} measurement_context_t;

/*
 * Kotlin: external fun deconvolveEx(handle: Long, recorded: FloatArray,
 *                                    inverseFilter: FloatArray, sampleRate: Int,
 *                                    normalizeIr: Boolean, windowMode: Int,
 *                                    maxIrLenMs: Int, arrivalThreshold: Double,
 *                                    leftWindowMs: Int, rightWindowPercent: Int): Int
 */
JNIEXPORT jint JNICALL
Java_me_timschneeberger_rootlessjamesdsp_measurement_MeasurementNativeEngine_deconvolveEx(
        JNIEnv *env, jobject thiz, jlong handle,
        jfloatArray recordedArr, jfloatArray inverseFilterArr, jint sampleRate,
        jboolean normalizeIr, jint windowMode, jint maxIrLenMs,
        jdouble arrivalThreshold, jint leftWindowMs, jint rightWindowPercent)
{
    measurement_context_t *ctx = (measurement_context_t *)(intptr_t)handle;
    if (!ctx) {
        LOGE("deconvolveEx: invalid context handle");
        return -1;
    }

    deconv_free(&ctx->deconv);
    if (ctx->hasSpl) { spl_free(&ctx->spl); ctx->hasSpl = 0; }
    if (ctx->hasThd) { free(ctx->thd); free(ctx->thdFrequencies); ctx->hasThd = 0; }
    if (ctx->hasRt60) { free(ctx->rt60); free(ctx->rt60Frequencies); ctx->hasRt60 = 0; }
    if (ctx->hasGroupDelay) { free(ctx->groupDelay); ctx->hasGroupDelay = 0; }

    int numRecorded = (*env)->GetArrayLength(env, recordedArr);
    int numFilter = (*env)->GetArrayLength(env, inverseFilterArr);

    float *recorded = (float *)(*env)->GetFloatArrayElements(env, recordedArr, NULL);
    float *inverseFilter = (float *)(*env)->GetFloatArrayElements(env, inverseFilterArr, NULL);

    if (!recorded || !inverseFilter) {
        LOGE("deconvolveEx: failed to get array elements");
        if (recorded) (*env)->ReleaseFloatArrayElements(env, recordedArr, recorded, JNI_ABORT);
        if (inverseFilter) (*env)->ReleaseFloatArrayElements(env, inverseFilterArr, inverseFilter, JNI_ABORT);
        return -2;
    }

    deconv_config_t config;
    config.normalizeIr = normalizeIr ? 1 : 0;
    config.windowMode = (ir_window_mode_t)windowMode;
    config.maxIrLenMs = maxIrLenMs;
    config.arrivalThreshold = (double)arrivalThreshold;
    config.leftWindowMs = leftWindowMs;
    config.rightWindowPercent = rightWindowPercent;

    int ret = farina_deconvolve_ex(recorded, inverseFilter, numRecorded, numFilter,
                                     sampleRate, &config, &ctx->deconv);

    (*env)->ReleaseFloatArrayElements(env, recordedArr, recorded, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, inverseFilterArr, inverseFilter, JNI_ABORT);

    if (ret != 0) {
        LOGE("deconvolveEx: failed with code %d", ret);
    } else {
        LOGI("deconvolveEx: success, IR length=%d, t0=%d, norm=%d, win=%d, maxMs=%d",
             ctx->deconv.irValidLen, ctx->deconv.t0Sample,
             config.normalizeIr, config.windowMode, config.maxIrLenMs);
    }

    return ret;
}

/*
 * Kotlin: external fun deconvolve(recorded: FloatArray, inverseFilter: FloatArray,
 *                                  sampleRate: Int): DeconvResult
 *
 * Возвращает DeconvResult (data class) через long handle для нативной памяти.
 * Kotlin вызывает getIr(handle) для доступа к данным.
 */

/*
 * Kotlin: external fun createMeasurementContext(): Long
 */
JNIEXPORT jlong JNICALL
Java_me_timschneeberger_rootlessjamesdsp_measurement_MeasurementNativeEngine_createContext(
        JNIEnv *env, jobject thiz)
{
    measurement_context_t *ctx = (measurement_context_t *)calloc(1, sizeof(measurement_context_t));
    if (!ctx) {
        LOGE("createContext: failed to allocate context");
        return 0;
    }
    ctx->hasSpl = 0;
    ctx->hasThd = 0;
    ctx->hasRt60 = 0;
    ctx->hasGroupDelay = 0;
    return (jlong)(intptr_t)ctx;
}

/*
 * Kotlin: external fun destroyMeasurementContext(handle: Long)
 */
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_measurement_MeasurementNativeEngine_destroyContext(
        JNIEnv *env, jobject thiz, jlong handle)
{
    measurement_context_t *ctx = (measurement_context_t *)(intptr_t)handle;
    if (!ctx) return;
    deconv_free(&ctx->deconv);
    if (ctx->hasSpl) spl_free(&ctx->spl);
    if (ctx->hasThd) { free(ctx->thd); free(ctx->thdFrequencies); ctx->hasThd = 0; }
    if (ctx->hasRt60) { free(ctx->rt60); free(ctx->rt60Frequencies); ctx->hasRt60 = 0; }
    if (ctx->hasGroupDelay) { free(ctx->groupDelay); ctx->hasGroupDelay = 0; }
    free(ctx);
}

/*
 * Kotlin: external fun deconvolve(handle: Long, recorded: FloatArray,
 *                                 inverseFilter: FloatArray, sampleRate: Int): Int
 */
JNIEXPORT jint JNICALL
Java_me_timschneeberger_rootlessjamesdsp_measurement_MeasurementNativeEngine_deconvolve(
        JNIEnv *env, jobject thiz, jlong handle,
        jfloatArray recordedArr, jfloatArray inverseFilterArr, jint sampleRate)
{
    measurement_context_t *ctx = (measurement_context_t *)(intptr_t)handle;
    if (!ctx) {
        LOGE("deconvolve: invalid context handle");
        return -1;
    }

    /* Очистка предыдущих результатов */
    deconv_free(&ctx->deconv);
    if (ctx->hasSpl) { spl_free(&ctx->spl); ctx->hasSpl = 0; }

    int numRecorded = (*env)->GetArrayLength(env, recordedArr);
    int numFilter = (*env)->GetArrayLength(env, inverseFilterArr);

    float *recorded = (float *)(*env)->GetFloatArrayElements(env, recordedArr, NULL);
    float *inverseFilter = (float *)(*env)->GetFloatArrayElements(env, inverseFilterArr, NULL);

    if (!recorded || !inverseFilter) {
        LOGE("deconvolve: failed to get array elements");
        if (recorded) (*env)->ReleaseFloatArrayElements(env, recordedArr, recorded, JNI_ABORT);
        if (inverseFilter) (*env)->ReleaseFloatArrayElements(env, inverseFilterArr, inverseFilter, JNI_ABORT);
        return -2;
    }

    int ret = farina_deconvolve(recorded, inverseFilter, numRecorded, numFilter,
                                 sampleRate, &ctx->deconv);

    (*env)->ReleaseFloatArrayElements(env, recordedArr, recorded, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, inverseFilterArr, inverseFilter, JNI_ABORT);

    if (ret != 0) {
        LOGE("deconvolve: farina_deconvolve failed with code %d", ret);
    } else {
        LOGI("deconvolve: success, IR length=%d, t0=%d", ctx->deconv.irValidLen, ctx->deconv.t0Sample);
    }

    return ret;
}

/*
 * Kotlin: external fun getIr(handle: Long): FloatArray
 */
JNIEXPORT jfloatArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_measurement_MeasurementNativeEngine_getIr(
        JNIEnv *env, jobject thiz, jlong handle)
{
    measurement_context_t *ctx = (measurement_context_t *)(intptr_t)handle;
    if (!ctx || !ctx->deconv.ir) {
        LOGE("getIr: no IR available");
        return NULL;
    }

    int len = ctx->deconv.irValidLen;
    jfloatArray result = (*env)->NewFloatArray(env, len);
    if (!result) return NULL;

    (*env)->SetFloatArrayRegion(env, result, 0, len, ctx->deconv.ir);
    return result;
}

/*
 * Kotlin: external fun computeSpl(handle: Long, fftSize: Int): Int
 * Если fftSize <= 0, выбирается автоматически.
 */
JNIEXPORT jint JNICALL
Java_me_timschneeberger_rootlessjamesdsp_measurement_MeasurementNativeEngine_computeSpl(
        JNIEnv *env, jobject thiz, jlong handle, jint fftSize)
{
    measurement_context_t *ctx = (measurement_context_t *)(intptr_t)handle;
    if (!ctx || !ctx->deconv.ir) {
        LOGE("computeSpl: no IR available");
        return -1;
    }

    if (ctx->hasSpl) { spl_free(&ctx->spl); ctx->hasSpl = 0; }

    int ret = spl_compute_with_fft_size(ctx->deconv.ir, ctx->deconv.irValidLen,
                                         ctx->deconv.sampleRate, fftSize, &ctx->spl);
    if (ret != 0) {
        LOGE("computeSpl: failed with code %d", ret);
    } else {
        ctx->hasSpl = 1;
        LOGI("computeSpl: success, bins=%d", ctx->spl.numBins);
    }
    return ret;
}

/*
 * Kotlin: external fun getSplFrequencies(handle: Long): FloatArray
 */
JNIEXPORT jfloatArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_measurement_MeasurementNativeEngine_getSplFrequencies(
        JNIEnv *env, jobject thiz, jlong handle)
{
    measurement_context_t *ctx = (measurement_context_t *)(intptr_t)handle;
    if (!ctx || !ctx->hasSpl) return NULL;

    jfloatArray result = (*env)->NewFloatArray(env, ctx->spl.numBins);
    if (!result) return NULL;
    (*env)->SetFloatArrayRegion(env, result, 0, ctx->spl.numBins, ctx->spl.frequencies);
    return result;
}

/*
 * Kotlin: external fun getSpl(handle: Long): FloatArray
 */
JNIEXPORT jfloatArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_measurement_MeasurementNativeEngine_getSpl(
        JNIEnv *env, jobject thiz, jlong handle)
{
    measurement_context_t *ctx = (measurement_context_t *)(intptr_t)handle;
    if (!ctx || !ctx->hasSpl) return NULL;

    jfloatArray result = (*env)->NewFloatArray(env, ctx->spl.numBins);
    if (!result) return NULL;
    (*env)->SetFloatArrayRegion(env, result, 0, ctx->spl.numBins, ctx->spl.spl);
    return result;
}

/*
 * Kotlin: external fun getSplPhase(handle: Long): FloatArray
 */
JNIEXPORT jfloatArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_measurement_MeasurementNativeEngine_getSplPhase(
        JNIEnv *env, jobject thiz, jlong handle)
{
    measurement_context_t *ctx = (measurement_context_t *)(intptr_t)handle;
    if (!ctx || !ctx->hasSpl) return NULL;

    jfloatArray result = (*env)->NewFloatArray(env, ctx->spl.numBins);
    if (!result) return NULL;
    (*env)->SetFloatArrayRegion(env, result, 0, ctx->spl.numBins, ctx->spl.phase);
    return result;
}

/*
 * Kotlin: external fun applySmoothing(handle: Long, lowFreq: Float,
 *                                     lowFraction: Float, highFraction: Float)
 */
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_measurement_MeasurementNativeEngine_applySmoothing(
        JNIEnv *env, jobject thiz, jlong handle,
        jfloat lowFreq, jfloat lowFraction, jfloat highFraction)
{
    measurement_context_t *ctx = (measurement_context_t *)(intptr_t)handle;
    if (!ctx || !ctx->hasSpl) return;

    spl_smoothing_variable(ctx->spl.spl, ctx->spl.frequencies,
                            ctx->spl.numBins, lowFreq, lowFraction, highFraction);
}

/*
 * Kotlin: external fun applySmoothingByType(handle: Long, type: Int)
 */
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_measurement_MeasurementNativeEngine_applySmoothingByType(
        JNIEnv *env, jobject thiz, jlong handle, jint type)
{
    measurement_context_t *ctx = (measurement_context_t *)(intptr_t)handle;
    if (!ctx || !ctx->hasSpl) return;

    spl_smoothing_by_type(ctx->spl.spl, ctx->spl.frequencies,
                           ctx->spl.numBins, type);
}

/* ===== THD (Total Harmonic Distortion) ===== */

/*
 * Kotlin: external fun computeThd(handle: Long): Int
 *
 * Вычисляет THD из IR методом Фарины.
 * В IR после основного импульса содержатся "harmonic IRs" —
 * отклики на гармонических искажениях. THD(f) вычисляется
 * как отношение энергии гармоник к энергии основного сигнала.
 *
 * Алгоритм:
 * 1. FFT(IR) → комплексный спектр H(f)
 * 2. Для каждой частоты f: основная энергия = |H(f)|²
 * 3. Энергия гармоник: sum(|H(k*f)|²) for k=2,3,...
 * 4. THD(f) = sqrt(sum_harmonics / fundamental) * 100%
 */
JNIEXPORT jint JNICALL
Java_me_timschneeberger_rootlessjamesdsp_measurement_MeasurementNativeEngine_computeThd(
        JNIEnv *env, jobject thiz, jlong handle)
{
    measurement_context_t *ctx = (measurement_context_t *)(intptr_t)handle;
    if (!ctx || !ctx->deconv.ir) {
        LOGE("computeThd: no IR available");
        return -1;
    }

    /* Free previous THD data */
    if (ctx->hasThd) { free(ctx->thd); free(ctx->thdFrequencies); ctx->hasThd = 0; }

    int irLen = ctx->deconv.irValidLen;
    int sampleRate = ctx->deconv.sampleRate;

    /* Use same FFT size as SPL computation */
    int fftSize = ctx->spl.fftSize;
    if (fftSize <= 0) {
        int p = 1;
        while (p < irLen) p <<= 1;
        fftSize = p;
    }
    if (fftSize & 1) fftSize <<= 1;

    int numBins = fftSize / 2 + 1;

    /* Compute FFT of IR */
    kiss_fft_scalar *timeBuffer = (kiss_fft_scalar *)calloc(fftSize, sizeof(kiss_fft_scalar));
    kiss_fft_cpx *freqData = (kiss_fft_cpx *)malloc(numBins * sizeof(kiss_fft_cpx));
    if (!timeBuffer || !freqData) {
        free(timeBuffer); free(freqData);
        return -2;
    }

    int copyLen = (irLen < fftSize) ? irLen : fftSize;
    for (int i = 0; i < copyLen; i++) {
        timeBuffer[i] = (kiss_fft_scalar)ctx->deconv.ir[i];
    }

    kiss_fftr_cfg cfg = kiss_fftr_alloc(fftSize, 0, NULL, NULL);
    if (!cfg) {
        free(timeBuffer); free(freqData);
        return -3;
    }
    kiss_fftr(cfg, timeBuffer, freqData);

    /* Compute magnitude spectrum */
    double *mag = (double *)malloc(numBins * sizeof(double));
    if (!mag) {
        free(timeBuffer); free(freqData); kiss_fftr_free(cfg);
        return -4;
    }
    for (int i = 0; i < numBins; i++) {
        double re = (double)freqData[i].r;
        double im = (double)freqData[i].i;
        mag[i] = sqrt(re * re + im * im);
    }

    /* Compute THD per frequency bin.
     * For each fundamental frequency f (bin index i):
     * THD = sqrt(sum(|H(k*i)|^2, k=2..K) / |H(i)|^2) * 100%
     * Only compute for bins where harmonics fit within numBins.
     * Limit to fundamental frequencies up to sampleRate/4 (Nyquist/2)
     * to ensure at least 2nd harmonic is available. */
    int maxFundamentalBin = numBins / 2; /* at least 2nd harmonic fits */
    int thdBins = maxFundamentalBin;

    float *thd = (float *)calloc(thdBins, sizeof(float));
    float *thdFreqs = (float *)calloc(thdBins, sizeof(float));
    if (!thd || !thdFreqs) {
        free(thd); free(thdFreqs); free(mag);
        free(timeBuffer); free(freqData); kiss_fftr_free(cfg);
        return -5;
    }

    double freqResolution = (double)sampleRate / fftSize;

    for (int i = 1; i < thdBins; i++) {  /* skip DC */
        thdFreqs[i] = (float)(i * freqResolution);

        double fundamentalPower = mag[i] * mag[i];
        if (fundamentalPower < 1e-20) {
            thd[i] = 0.0f;
            continue;
        }

        /* Sum power of harmonics 2nd through 10th */
        double harmonicPower = 0.0;
        int maxHarmonic = 10;
        for (int k = 2; k <= maxHarmonic; k++) {
            int harmBin = i * k;
            if (harmBin >= numBins) break;
            harmonicPower += mag[harmBin] * mag[harmBin];
        }

        /* THD in percent */
        double thdVal = sqrt(harmonicPower / fundamentalPower) * 100.0;
        /* Clamp to reasonable range */
        if (thdVal > 100.0) thdVal = 100.0;
        if (thdVal < 0.0) thdVal = 0.0;
        thd[i] = (float)thdVal;
    }

    ctx->thd = thd;
    ctx->thdFrequencies = thdFreqs;
    ctx->thdNumBins = thdBins;
    ctx->hasThd = 1;

    free(mag);
    free(timeBuffer); free(freqData); kiss_fftr_free(cfg);

    LOGI("computeThd: success, bins=%d", thdBins);
    return 0;
}

JNIEXPORT jfloatArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_measurement_MeasurementNativeEngine_getThd(
        JNIEnv *env, jobject thiz, jlong handle)
{
    measurement_context_t *ctx = (measurement_context_t *)(intptr_t)handle;
    if (!ctx || !ctx->hasThd) return NULL;

    jfloatArray result = (*env)->NewFloatArray(env, ctx->thdNumBins);
    if (!result) return NULL;
    (*env)->SetFloatArrayRegion(env, result, 0, ctx->thdNumBins, ctx->thd);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_measurement_MeasurementNativeEngine_getThdFrequencies(
        JNIEnv *env, jobject thiz, jlong handle)
{
    measurement_context_t *ctx = (measurement_context_t *)(intptr_t)handle;
    if (!ctx || !ctx->hasThd) return NULL;

    jfloatArray result = (*env)->NewFloatArray(env, ctx->thdNumBins);
    if (!result) return NULL;
    (*env)->SetFloatArrayRegion(env, result, 0, ctx->thdNumBins, ctx->thdFrequencies);
    return result;
}

/* ===== RT60 (Reverberation Time) ===== */

/*
 * Kotlin: external fun computeRt60(handle: Long): Int
 *
 * Вычисляет RT60 из IR методом Schroeder backward integration.
 *
 * Алгоритм:
 * 1. Для каждой octave band:
 *    a. Отфильтровать IR полосовым фильтром (октава)
 *    b. Вычислить squared envelope: p(t) = ir_band(t)^2
 *    c. Schroeder backward integration: B(t) = integral from t to end of p(tau) dtau
 *    d. В дБ: 10*log10(B(t))
 *    e. Линейная регрессия от пика до -30 dB (или -60 dB)
 *    f. RT60 = время для падения на 60 dB (экстраполяция)
 *
 * Здесь используем упрощённую версию: FFT-based octave band energy decay.
 */
JNIEXPORT jint JNICALL
Java_me_timschneeberger_rootlessjamesdsp_measurement_MeasurementNativeEngine_computeRt60(
        JNIEnv *env, jobject thiz, jlong handle)
{
    measurement_context_t *ctx = (measurement_context_t *)(intptr_t)handle;
    if (!ctx || !ctx->deconv.ir) {
        LOGE("computeRt60: no IR available");
        return -1;
    }

    /* Free previous RT60 data */
    if (ctx->hasRt60) { free(ctx->rt60); free(ctx->rt60Frequencies); ctx->hasRt60 = 0; }

    int irLen = ctx->deconv.irValidLen;
    int sampleRate = ctx->deconv.sampleRate;

    /* Octave band center frequencies: 31.5, 63, 125, 250, 500, 1k, 2k, 4k, 8k, 16k */
    const float bandCenters[] = {31.5f, 63.0f, 125.0f, 250.0f, 500.0f,
                                  1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f};
    int numBands = sizeof(bandCenters) / sizeof(bandCenters[0]);

    float *rt60 = (float *)calloc(numBands, sizeof(float));
    float *rt60Freqs = (float *)calloc(numBands, sizeof(float));
    if (!rt60 || !rt60Freqs) {
        free(rt60); free(rt60Freqs);
        return -2;
    }

    /* For each octave band, compute energy decay via Schroeder integration */
    for (int b = 0; b < numBands; b++) {
        float fc = bandCenters[b];
        rt60Freqs[b] = fc;

        /* Band edges: fLow = fc / sqrt(2), fHigh = fc * sqrt(2) */
        float fLow = fc / 1.41421356f;
        float fHigh = fc * 1.41421356f;

        /* Skip if band exceeds Nyquist */
        if (fHigh > sampleRate / 2.0f) {
            rt60[b] = 0.0f;
            continue;
        }

        /* Compute band-limited IR via FFT:
         * 1. FFT(IR) → spectrum
         * 2. Zero out bins outside [fLow, fHigh]
         * 3. IFFT → band-limited IR
         * 4. Compute energy decay */

        int fftSize = 1;
        while (fftSize < irLen) fftSize <<= 1;
        if (fftSize & 1) fftSize <<= 1;
        int nBins = fftSize / 2 + 1;
        double freqRes = (double)sampleRate / fftSize;

        kiss_fft_scalar *tb = (kiss_fft_scalar *)calloc(fftSize, sizeof(kiss_fft_scalar));
        kiss_fft_cpx *fd = (kiss_fft_cpx *)malloc(nBins * sizeof(kiss_fft_cpx));
        kiss_fft_cpx *fdFiltered = (kiss_fft_cpx *)calloc(nBins, sizeof(kiss_fft_cpx));
        kiss_fft_scalar *irBand = (kiss_fft_scalar *)malloc(fftSize * sizeof(kiss_fft_scalar));

        if (!tb || !fd || !fdFiltered || !irBand) {
            free(tb); free(fd); free(fdFiltered); free(irBand);
            rt60[b] = 0.0f;
            continue;
        }

        for (int i = 0; i < irLen && i < fftSize; i++) {
            tb[i] = (kiss_fft_scalar)ctx->deconv.ir[i];
        }

        kiss_fftr_cfg cfgF = kiss_fftr_alloc(fftSize, 0, NULL, NULL);
        kiss_fftr_cfg cfgI = kiss_fftr_alloc(fftSize, 1, NULL, NULL);
        if (!cfgF || !cfgI) {
            if (cfgF) kiss_fftr_free(cfgF);
            if (cfgI) kiss_fftr_free(cfgI);
            free(tb); free(fd); free(fdFiltered); free(irBand);
            rt60[b] = 0.0f;
            continue;
        }

        kiss_fftr(cfgF, tb, fd);

        /* Bandpass filter in frequency domain */
        for (int i = 0; i < nBins; i++) {
            double f = i * freqRes;
            if (f >= fLow && f <= fHigh) {
                fdFiltered[i].r = fd[i].r;
                fdFiltered[i].i = fd[i].i;
            }
            /* else zero (already zeroed by calloc) */
        }

        kiss_fftri(cfgI, fdFiltered, irBand);
        double norm = 1.0 / fftSize;
        for (int i = 0; i < fftSize; i++) {
            irBand[i] *= norm;
        }

        /* Schroeder backward integration:
         * B(t) = sum_{k=t}^{N-1} ir_band(k)^2
         * Then: 10*log10(B(t)/B(0)) → decay curve in dB
         * Linear regression from 0 dB to -30 dB point → RT60 = slope * 2 */
        double *backwardInt = (double *)malloc(irLen * sizeof(double));
        if (!backwardInt) {
            kiss_fftr_free(cfgF); kiss_fftr_free(cfgI);
            free(tb); free(fd); free(fdFiltered); free(irBand);
            rt60[b] = 0.0f;
            continue;
        }

        /* Compute backward integration */
        backwardInt[irLen - 1] = (double)irBand[irLen - 1] * irBand[irLen - 1];
        for (int i = irLen - 2; i >= 0; i--) {
            double s = (double)irBand[i];
            backwardInt[i] = backwardInt[i + 1] + s * s;
        }

        /* Normalize to 0 dB at t=0 */
        double b0 = backwardInt[0];
        if (b0 < 1e-20) {
            free(backwardInt);
            kiss_fftr_free(cfgF); kiss_fftr_free(cfgI);
            free(tb); free(fd); free(fdFiltered); free(irBand);
            rt60[b] = 0.0f;
            continue;
        }

        /* Find -30 dB point (relative to B(0)) */
        double threshold30 = b0 * pow(10.0, -30.0 / 10.0); /* -30 dB power */
        int t30Idx = irLen - 1;
        for (int i = 0; i < irLen; i++) {
            if (backwardInt[i] < threshold30) {
                t30Idx = i;
                break;
            }
        }

        /* Linear regression in dB domain from 0 to t30Idx */
        /* Convert to dB: 10*log10(B(t)/B(0)) */
        int regressLen = t30Idx;
        if (regressLen < 2) {
            free(backwardInt);
            kiss_fftr_free(cfgF); kiss_fftr_free(cfgI);
            free(tb); free(fd); free(fdFiltered); free(irBand);
            rt60[b] = 0.0f;
            continue;
        }

        /* Linear regression: y = a*x + b
         * x = time in seconds, y = 10*log10(B(t)/B(0)) in dB */
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < regressLen; i++) {
            double t = (double)i / sampleRate;
            double y = 10.0 * log10(backwardInt[i] / b0);
            sumX += t;
            sumY += y;
            sumXY += t * y;
            sumX2 += t * t;
        }
        int n = regressLen;
        double denom = (double)n * sumX2 - sumX * sumX;
        if (fabs(denom) < 1e-20) {
            free(backwardInt);
            kiss_fftr_free(cfgF); kiss_fftr_free(cfgI);
            free(tb); free(fd); free(fdFiltered); free(irBand);
            rt60[b] = 0.0f;
            continue;
        }
        double slope = ((double)n * sumXY - sumX * sumY) / denom; /* dB/s */

        /* RT60 = time for -60 dB decay = -60 / slope */
        if (slope < -0.001) {
            rt60[b] = (float)(-60.0 / slope);
        } else {
            rt60[b] = 0.0f; /* no decay detected */
        }

        free(backwardInt);
        kiss_fftr_free(cfgF); kiss_fftr_free(cfgI);
        free(tb); free(fd); free(fdFiltered); free(irBand);
    }

    ctx->rt60 = rt60;
    ctx->rt60Frequencies = rt60Freqs;
    ctx->rt60NumBands = numBands;
    ctx->hasRt60 = 1;

    LOGI("computeRt60: success, bands=%d", numBands);
    return 0;
}

JNIEXPORT jfloatArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_measurement_MeasurementNativeEngine_getRt60(
        JNIEnv *env, jobject thiz, jlong handle)
{
    measurement_context_t *ctx = (measurement_context_t *)(intptr_t)handle;
    if (!ctx || !ctx->hasRt60) return NULL;

    jfloatArray result = (*env)->NewFloatArray(env, ctx->rt60NumBands);
    if (!result) return NULL;
    (*env)->SetFloatArrayRegion(env, result, 0, ctx->rt60NumBands, ctx->rt60);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_measurement_MeasurementNativeEngine_getRt60Frequencies(
        JNIEnv *env, jobject thiz, jlong handle)
{
    measurement_context_t *ctx = (measurement_context_t *)(intptr_t)handle;
    if (!ctx || !ctx->hasRt60) return NULL;

    jfloatArray result = (*env)->NewFloatArray(env, ctx->rt60NumBands);
    if (!result) return NULL;
    (*env)->SetFloatArrayRegion(env, result, 0, ctx->rt60NumBands, ctx->rt60Frequencies);
    return result;
}

/* ===== Group Delay ===== */

/*
 * Kotlin: external fun computeGroupDelay(handle: Long): Int
 *
 * Group delay = -d(phase)/d(frequency), в секундах.
 * Вычисляется из фазового отклика SPL через численное дифференцирование.
 *
 * phase в радианах → group_delay = -d(phase_rad) / (2*pi*df)
 */
JNIEXPORT jint JNICALL
Java_me_timschneeberger_rootlessjamesdsp_measurement_MeasurementNativeEngine_computeGroupDelay(
        JNIEnv *env, jobject thiz, jlong handle)
{
    measurement_context_t *ctx = (measurement_context_t *)(intptr_t)handle;
    if (!ctx || !ctx->hasSpl) {
        LOGE("computeGroupDelay: no SPL available");
        return -1;
    }

    /* Free previous group delay data */
    if (ctx->hasGroupDelay) { free(ctx->groupDelay); ctx->hasGroupDelay = 0; }

    int numBins = ctx->spl.numBins;
    float *phase = ctx->spl.phase; /* in degrees */
    int sampleRate = ctx->spl.sampleRate;
    int fftSize = ctx->spl.fftSize;

    float *groupDelay = (float *)calloc(numBins, sizeof(float));
    if (!groupDelay) return -2;

    /* Convert phase from degrees to radians, then compute group delay.
     * group_delay(f) = -d(phase_rad) / d(omega)
     *                 = -d(phase_rad) / (2*pi*df)
     *
     * We need to unwrap phase first to avoid 360-degree jumps. */

    /* Phase unwrapping (in degrees) */
    double *unwrapPhase = (double *)malloc(numBins * sizeof(double));
    if (!unwrapPhase) { free(groupDelay); return -3; }

    unwrapPhase[0] = (double)phase[0];
    for (int i = 1; i < numBins; i++) {
        double diff = (double)phase[i] - (double)phase[i - 1];
        /* Wrap diff to [-180, 180] */
        while (diff > 180.0) diff -= 360.0;
        while (diff < -180.0) diff += 360.0;
        unwrapPhase[i] = unwrapPhase[i - 1] + diff;
    }

    /* Compute group delay via numerical differentiation.
     * df = frequency spacing = sampleRate / fftSize
     * phase_rad = phase_deg * pi / 180
     * group_delay = -d(phase_rad) / (2 * pi * df)
     *             = -d(phase_deg * pi / 180) / (2 * pi * df)
     *             = -d(phase_deg) / (360 * df) */
    double df = (double)sampleRate / fftSize;

    /* Central difference for interior points */
    for (int i = 1; i < numBins - 1; i++) {
        double dPhase = unwrapPhase[i + 1] - unwrapPhase[i - 1];
        groupDelay[i] = (float)(-dPhase / (360.0 * df));
    }

    /* Forward/backward difference for endpoints */
    if (numBins > 1) {
        groupDelay[0] = (float)(-(unwrapPhase[1] - unwrapPhase[0]) / (360.0 * df));
        groupDelay[numBins - 1] = (float)(-(unwrapPhase[numBins - 1] - unwrapPhase[numBins - 2]) / (360.0 * df));
    }

    /* Smooth group delay with a small moving average to reduce noise */
    int smoothWindow = numBins / 50;
    if (smoothWindow > 1) {
        float *smoothed = (float *)malloc(numBins * sizeof(float));
        if (smoothed) {
            for (int i = 0; i < numBins; i++) {
                double sum = 0.0;
                int count = 0;
                int start = i - smoothWindow / 2;
                int end = i + smoothWindow / 2;
                if (start < 0) start = 0;
                if (end >= numBins) end = numBins - 1;
                for (int j = start; j <= end; j++) {
                    sum += groupDelay[j];
                    count++;
                }
                smoothed[i] = (float)(sum / count);
            }
            memcpy(groupDelay, smoothed, numBins * sizeof(float));
            free(smoothed);
        }
    }

    free(unwrapPhase);

    ctx->groupDelay = groupDelay;
    ctx->gdNumBins = numBins;
    ctx->hasGroupDelay = 1;

    LOGI("computeGroupDelay: success, bins=%d", numBins);
    return 0;
}

JNIEXPORT jfloatArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_measurement_MeasurementNativeEngine_getGroupDelay(
        JNIEnv *env, jobject thiz, jlong handle)
{
    measurement_context_t *ctx = (measurement_context_t *)(intptr_t)handle;
    if (!ctx || !ctx->hasGroupDelay) return NULL;

    jfloatArray result = (*env)->NewFloatArray(env, ctx->gdNumBins);
    if (!result) return NULL;
    (*env)->SetFloatArrayRegion(env, result, 0, ctx->gdNumBins, ctx->groupDelay);
    return result;
}
