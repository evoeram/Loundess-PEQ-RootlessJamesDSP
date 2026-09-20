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
#include <android/log.h>

#include "sweep_generator.h"
#include "farina_deconv.h"
#include "ir_windowing.h"
#include "spl_response.h"

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

/*
 * Kotlin: external fun deconvolve(recorded: FloatArray, inverseFilter: FloatArray,
 *                                  sampleRate: Int): DeconvResult
 *
 * Возвращает DeconvResult (data class) через long handle для нативной памяти.
 * Kotlin вызывает getIr(handle) для доступа к данным.
 */

/* Структура для хранения нативного контекста между JNI вызовами */
typedef struct {
    deconv_result_t deconv;
    spl_result_t spl;
    int hasSpl;
} measurement_context_t;

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
