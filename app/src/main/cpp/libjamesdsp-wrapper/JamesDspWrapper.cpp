#include <android/log.h>

#define TAG "JamesDspWrapper_JNI"
#include <Log.h>

#include <string>
#include <jni.h>
#include <vector>

#include "JamesDspWrapper.h"
#include "JArrayList.h"
#include "EelVmVariable.h"
#include "biquad/ParametricEqProcessor.h"
#include "loudness/LoudnessCorrectionProcessor.h"

extern "C" {
#include "../EELStdOutExtension.h"
#include <jdsp_header.h>
}

// C interop
inline JamesDSPLib* cast(void* raw){
    if(raw == nullptr)
    {
        LOGE("JamesDspWrapper::cast: JamesDSPLib pointer is NULL")
    }
    return static_cast<JamesDSPLib*>(raw);
}

inline JamesDspWrapper* castWrapper(jlong raw){
    if(raw == 0)
    {
        LOGE("JamesDspWrapper::castWrapper: JamesDspWrapper pointer is NULL")
    }
    return reinterpret_cast<JamesDspWrapper*>(raw);
}

#define RETURN_IF_NULL(name, retval) \
    if(name == nullptr)      \
        return retval;

#define DECLARE_WRAPPER(retval) \
     if(self == 0L) \
        return retval; \
     auto* wrapper = castWrapper(self); \
     RETURN_IF_NULL(wrapper, retval)

#define DECLARE_DSP(retval) \
    DECLARE_WRAPPER(retval) \
    auto* dsp = cast(wrapper->dsp); \
    RETURN_IF_NULL(dsp, retval)

#define DECLARE_WRAPPER_V DECLARE_WRAPPER()
#define DECLARE_DSP_V DECLARE_DSP()
#define DECLARE_WRAPPER_B DECLARE_WRAPPER(false)
#define DECLARE_DSP_B DECLARE_DSP(false)

inline int32_t arySearch(int32_t *array, int32_t N, int32_t x)
{
    for (int32_t i = 0; i < N; i++)
    {
        if (array[i] == x)
            return i;
    }
    return -1;
}

#define FLOIDX 20000
/*inline void* GetStringForIndex(eel_string_context_state *st, float val, int32_t write)
{
    auto castedValue = (int32_t)(val + 0.5f);
    if (castedValue < FLOIDX)
        return nullptr;
    int32_t idx = arySearch(st->map, st->slot, castedValue);
    if (idx < 0)
        return nullptr;
    if (!write)
    {
        s_str *tmp = &st->m_literal_strings[idx];
        const char *s = s_str_c_str(tmp);
        return (void*)s;
    }
    else
        return (void*)&st->m_literal_strings[idx];
}*/

extern "C" JNIEXPORT jlong JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_alloc(JNIEnv *env, jobject obj, jobject callback)
{
    auto* self = new JamesDspWrapper();
    self->callbackInterface = env->NewGlobalRef(callback);
    self->env = env;
    self->parametricEq = new ParametricEqProcessor();
    self->loudnessCorrection = new LoudnessCorrectionProcessor();
    self->dynamicEq = new dynamicEq_t();
    dynamicEq_init(self->dynamicEq);
    self->lufsNormalizer = new lufs_normalizer_t();
    lufs_normalizer_init(self->lufsNormalizer);

    jclass callbackClass = env->GetObjectClass(callback);
    if (callbackClass == nullptr)
    {
        LOGE("JamesDspWrapper::ctor: Cannot find callback class");
        delete self;
        return 0;
    }
    else
    {
        self->callbackOnLiveprogOutput = env->GetMethodID(callbackClass, "onLiveprogOutput",
                                                      "(Ljava/lang/String;)V");
        self->callbackOnLiveprogExec = env->GetMethodID(callbackClass, "onLiveprogExec",
                                                    "(Ljava/lang/String;)V");
        self->callbackOnLiveprogResult = env->GetMethodID(callbackClass, "onLiveprogResult",
                                                          "(ILjava/lang/String;Ljava/lang/String;)V");
        self->callbackOnVdcParseError = env->GetMethodID(callbackClass, "onVdcParseError",
                                                          "()V");
        if (self->callbackOnLiveprogOutput == nullptr || self->callbackOnLiveprogExec == nullptr ||
            self->callbackOnLiveprogResult == nullptr || self->callbackOnVdcParseError == nullptr)
        {
            LOGE("JamesDspWrapper::ctor: Cannot find callback method");
            delete self;
            return 0;
        }
    }


    auto* _dsp = (JamesDSPLib*)malloc(sizeof(JamesDSPLib));
    memset(_dsp, 0, sizeof(JamesDSPLib));

    if(!_dsp)
    {
        LOGE("JamesDspWrapper::ctor: Failed to allocate memory for libjamesdsp class object");
        delete self;
        return 1;
    }

    JamesDSPGlobalMemoryAllocation();
    JamesDSPInit(_dsp, 128, 48000);

    if(!JamesDSPGetMutexStatus(_dsp))
    {
        LOGE("JamesDspWrapper::ctor: JamesDSPGetMutexStatus returned false. "
                    "Cannot run safely in multi-threaded environment.");
        JamesDSPFree(_dsp);
        JamesDSPGlobalMemoryDeallocation();
        delete self;
        return 2;
    }

    self->dsp = _dsp;

    LOGD("JamesDspWrapper::ctor: memory allocated at %lx", (long)self);
    return (long)self;
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_free(JNIEnv *env, jobject obj, jlong self)
{
    DECLARE_DSP_V

    LOGD("JamesDspWrapper::dtor: freeing memory allocated at %lx", (long)self);

    setStdOutHandler(nullptr, nullptr);

    JamesDSPFree(dsp);
    free(dsp);
    wrapper->dsp = nullptr;

    delete wrapper->parametricEq;
    wrapper->parametricEq = nullptr;

    delete wrapper->loudnessCorrection;
    wrapper->loudnessCorrection = nullptr;

    dynamicEq_destroy(wrapper->dynamicEq);
    delete wrapper->dynamicEq;
    wrapper->dynamicEq = nullptr;

    delete wrapper->lufsNormalizer;
    wrapper->lufsNormalizer = nullptr;

    JamesDSPGlobalMemoryDeallocation();

    env->DeleteGlobalRef(wrapper->callbackInterface);
    delete wrapper;

    LOGD("JamesDspWrapper::dtor: memory freed");
}

extern "C" JNIEXPORT jint JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_getBenchmarkSize(JNIEnv *env, jobject obj) {
    return MAX_BENCHMARK;
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_runBenchmark(JNIEnv *env, jobject obj, jdoubleArray jc0, jdoubleArray jc1)
{
    LOGD("JamesDspWrapper::runBenchmark: started");

    auto c0 = env->GetDoubleArrayElements(jc0, nullptr);
    auto c1 = env->GetDoubleArrayElements(jc1, nullptr);

    JamesDSP_Start_benchmark();
    JamesDSP_Save_benchmark(c0, c1);

    env->ReleaseDoubleArrayElements(jc0, c0, 0);
    env->ReleaseDoubleArrayElements(jc1, c1, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_loadBenchmark(JNIEnv *env, jobject obj, jdoubleArray jc0, jdoubleArray jc1)
{
    LOGD("JamesDspWrapper::loadBenchmark: loading data");

    auto c0 = env->GetDoubleArrayElements(jc0, nullptr);
    auto c1 = env->GetDoubleArrayElements(jc1, nullptr);

    JamesDSP_Load_benchmark(c0, c1);

    env->ReleaseDoubleArrayElements(jc0, c0, JNI_ABORT);
    env->ReleaseDoubleArrayElements(jc1, c1, JNI_ABORT);
}

extern "C"
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setSamplingRate(JNIEnv *env,
                                                                                 jobject obj,
                                                                                 jlong self,
                                                                                 jfloat sample_rate,
                                                                                 jboolean force_refresh)
{
    DECLARE_DSP_V
    JamesDSPSetSampleRate(dsp, sample_rate, force_refresh);
}


extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_isHandleValid(JNIEnv *env, jobject obj, jlong self)
{
    DECLARE_DSP_B // This macro returns false if the DSP object can't be accessed
    return true;
}

extern "C"
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processInt16(JNIEnv *env, jobject obj, jlong self, jshortArray inputObj, jshortArray outputObj, jint offset, jint size)
{
    DECLARE_DSP_V

    if(inputObj == nullptr || outputObj == nullptr)
        return;
    if(offset < 0)
        offset = 0;
    const jsize inputArrayLength = env->GetArrayLength(inputObj);
    const jsize outputArrayLength = env->GetArrayLength(outputObj);
    const jsize inputLength = size < 0 ? inputArrayLength - offset : size;
    if(offset > inputArrayLength || inputLength < 2 || (inputLength & 1) != 0 ||
       inputLength > inputArrayLength - offset || outputArrayLength < inputLength)
        return;

    if (env->IsSameObject(inputObj, outputObj))
    {
        auto input = env->GetShortArrayElements(inputObj, nullptr);
        if(input == nullptr)
            return;
        dsp->processInt16Multiplexd(dsp, input + offset, input + offset, inputLength / 2);

        // In-place path: the DSP output lives at input[offset..], so the PEQ,
        // Dynamic EQ and loudness chain must process that same region, not the
        // buffer start.
        const bool peqEnabled = wrapper->parametricEq && wrapper->parametricEq->isEnabled();
        /* The module is called even when disabled: it needs the block to
         * finish its click-free fade-out. It fast-paths when fully off. */
        const bool deqActive = wrapper->dynamicEq && dynamicEq_is_active(wrapper->dynamicEq);
        const bool loudEnabled = wrapper->loudnessCorrection && wrapper->loudnessCorrection->isEnabled();
        const bool lufsEnabled = wrapper->lufsNormalizer && wrapper->lufsNormalizer->enabled;
        if (peqEnabled || deqActive || loudEnabled || lufsEnabled)
        {
            size_t frames = inputLength / 2;
            std::vector<float> tmp(frames * 2);
            for (size_t i = 0; i < frames * 2; ++i)
                tmp[i] = (float)(input[offset + i]) / 32768.0f;
            if (peqEnabled)
                wrapper->parametricEq->processInterleaved(tmp.data(), frames);
            if (deqActive)
                dynamicEq_process_interleaved(wrapper->dynamicEq, tmp.data(), frames);
            if (loudEnabled)
                wrapper->loudnessCorrection->processInterleaved(tmp.data(), frames);
            if (lufsEnabled)
                lufs_normalizer_process_interleaved(wrapper->lufsNormalizer, tmp.data(), frames);
            for (size_t i = 0; i < frames * 2; ++i)
            {
                float v = tmp[i] * 32768.0f;
                if (v > 32767.0f) v = 32767.0f;
                if (v < -32768.0f) v = -32768.0f;
                input[offset + i] = (short)v;
            }
        }

        env->ReleaseShortArrayElements(inputObj, input, 0);
        return;
    }

    auto input = env->GetShortArrayElements(inputObj, nullptr);
    auto output = env->GetShortArrayElements(outputObj, nullptr);
    if(input == nullptr || output == nullptr)
    {
        if(input != nullptr) env->ReleaseShortArrayElements(inputObj, input, JNI_ABORT);
        if(output != nullptr) env->ReleaseShortArrayElements(outputObj, output, JNI_ABORT);
        return;
    }
    // DSP пишет в output[0..], поэтому PEQ и loudness тоже должны
    // читать/писать с начала буфера. Раньше использовался offsetIdx * 2,
    // что при offset > 0 приводило к обработке мусора и пропуску начала.
    dsp->processInt16Multiplexd(dsp, input + offset, output, inputLength / 2);

    // Apply the time-domain chain (PEQ, Dynamic EQ, loudness) on the int16
    // path via a float conversion.
    const bool peqEnabled = wrapper->parametricEq && wrapper->parametricEq->isEnabled();
    /* Called while disabled too, so the fade-out can finish. */
    const bool deqActive = wrapper->dynamicEq && dynamicEq_is_active(wrapper->dynamicEq);
    const bool loudEnabled = wrapper->loudnessCorrection && wrapper->loudnessCorrection->isEnabled();
    const bool lufsEnabled = wrapper->lufsNormalizer && wrapper->lufsNormalizer->enabled;
    if (peqEnabled || deqActive || loudEnabled || lufsEnabled)
    {
        size_t frames = inputLength / 2;
        std::vector<float> tmp(frames * 2);
        for (size_t i = 0; i < frames * 2; ++i)
            tmp[i] = (float)(output[i]) / 32768.0f;
        if (peqEnabled)
            wrapper->parametricEq->processInterleaved(tmp.data(), frames);
        if (deqActive)
            dynamicEq_process_interleaved(wrapper->dynamicEq, tmp.data(), frames);
        if (loudEnabled)
            wrapper->loudnessCorrection->processInterleaved(tmp.data(), frames);
        if (lufsEnabled)
            lufs_normalizer_process_interleaved(wrapper->lufsNormalizer, tmp.data(), frames);
        for (size_t i = 0; i < frames * 2; ++i)
        {
            float v = tmp[i] * 32768.0f;
            if (v > 32767.0f) v = 32767.0f;
            if (v < -32768.0f) v = -32768.0f;
            output[i] = (short)v;
        }
    }

    env->ReleaseShortArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseShortArrayElements(outputObj, output, 0);
}

extern "C"
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processInt32(JNIEnv *env, jobject obj, jlong self, jintArray inputObj, jintArray outputObj, jint offset, jint size)
{
    DECLARE_DSP_V

    if(inputObj == nullptr || outputObj == nullptr)
        return;
    if(offset < 0)
        offset = 0;
    const jsize inputArrayLength = env->GetArrayLength(inputObj);
    const jsize outputArrayLength = env->GetArrayLength(outputObj);
    const jsize inputLength = size < 0 ? inputArrayLength - offset : size;
    if(offset > inputArrayLength || inputLength < 2 || (inputLength & 1) != 0 ||
       inputLength > inputArrayLength - offset || outputArrayLength < inputLength)
        return;

    if (env->IsSameObject(inputObj, outputObj))
    {
        auto input = env->GetIntArrayElements(inputObj, nullptr);
        if(input == nullptr)
            return;
        dsp->processInt32Multiplexd(dsp, input + offset, input + offset, inputLength / 2);
        env->ReleaseIntArrayElements(inputObj, input, 0);
        return;
    }

    auto input = env->GetIntArrayElements(inputObj, nullptr);
    auto output = env->GetIntArrayElements(outputObj, nullptr);
    if(input == nullptr || output == nullptr)
    {
        if(input != nullptr) env->ReleaseIntArrayElements(inputObj, input, JNI_ABORT);
        if(output != nullptr) env->ReleaseIntArrayElements(outputObj, output, JNI_ABORT);
        return;
    }
    dsp->processInt32Multiplexd(dsp, input + offset, output, inputLength / 2);
    env->ReleaseIntArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseIntArrayElements(outputObj, output, 0);
}

extern "C"
JNIEXPORT jbooleanArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processInt24Packed(JNIEnv *env, jobject obj, jlong self, jbooleanArray inputObj)
{
    /* We need to use jbooleanArray (= unsigned 8-bit) instead of jbyteArray (= signed 8-bit) here! */

    // Return inputObj if DECLARE failed
    DECLARE_DSP(inputObj)

    auto inputLength = env->GetArrayLength(inputObj);
    auto outputObj = env->NewBooleanArray(inputLength);

    auto input = env->GetBooleanArrayElements(inputObj, nullptr);
    auto output = env->GetBooleanArrayElements(outputObj, nullptr);
    dsp->processInt24PackedMultiplexd(dsp, input, output, inputLength / 2);
    env->ReleaseBooleanArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseBooleanArrayElements(outputObj, output, 0);
    return outputObj;
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processInt8U24(JNIEnv *env, jobject obj, jlong self, jintArray inputObj)
{
    // Return inputObj if DECLARE failed
    DECLARE_DSP(inputObj)

    auto inputLength = env->GetArrayLength(inputObj);
    auto outputObj = env->NewIntArray(inputLength);

    auto input = env->GetIntArrayElements(inputObj, nullptr);
    auto output = env->GetIntArrayElements(outputObj, nullptr);
    dsp->processInt8_24Multiplexd(dsp, input, output, inputLength / 2);
    env->ReleaseIntArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseIntArrayElements(outputObj, output, 0);
    return outputObj;
}

extern "C"
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processFloat(JNIEnv *env, jobject obj, jlong self, jfloatArray inputObj, jfloatArray outputObj, jint offset, jint size)
{
    DECLARE_DSP_V

    if(inputObj == nullptr || outputObj == nullptr)
        return;
    if(offset < 0)
        offset = 0;
    const jsize inputArrayLength = env->GetArrayLength(inputObj);
    const jsize outputArrayLength = env->GetArrayLength(outputObj);
    const jsize inputLength = size < 0 ? inputArrayLength - offset : size;
    if(offset > inputArrayLength || inputLength < 2 || (inputLength & 1) != 0 ||
       inputLength > inputArrayLength - offset || outputArrayLength < inputLength)
        return;

    if (env->IsSameObject(inputObj, outputObj))
    {
        auto input = env->GetFloatArrayElements(inputObj, nullptr);
        if(input == nullptr)
            return;
        dsp->processFloatMultiplexd(dsp, input + offset, input + offset, inputLength / 2);

        // In-place path: the DSP output lives at input[offset..], so the PEQ,
        // Dynamic EQ and loudness chain must process that same region.
        if (wrapper->parametricEq && wrapper->parametricEq->isEnabled())
            wrapper->parametricEq->processInterleaved(input + offset, inputLength / 2);

        if (wrapper->dynamicEq)
            dynamicEq_process_interleaved(wrapper->dynamicEq, input + offset, inputLength / 2);

        if (wrapper->loudnessCorrection && wrapper->loudnessCorrection->isEnabled())
            wrapper->loudnessCorrection->processInterleaved(input + offset, inputLength / 2);

        if (wrapper->lufsNormalizer && wrapper->lufsNormalizer->enabled)
            lufs_normalizer_process_interleaved(wrapper->lufsNormalizer, input + offset, inputLength / 2);

        env->ReleaseFloatArrayElements(inputObj, input, 0);
        return;
    }

    auto input = env->GetFloatArrayElements(inputObj, nullptr);
    auto output = env->GetFloatArrayElements(outputObj, nullptr);
    if(input == nullptr || output == nullptr)
    {
        if(input != nullptr) env->ReleaseFloatArrayElements(inputObj, input, JNI_ABORT);
        if(output != nullptr) env->ReleaseFloatArrayElements(outputObj, output, JNI_ABORT);
        return;
    }

    // DSP пишет в output[0..], поэтому PEQ и loudness тоже должны
    // читать/писать с начала буфера. Раньше использовался offset * 2,
    // что при offset > 0 приводило к обработке мусора и пропуску начала.
    dsp->processFloatMultiplexd(dsp, input + offset, output, inputLength / 2);

    // Apply the time-domain chain after the main DSP chain: PEQ first, then
    // Dynamic EQ, then loudness (which must see the corrected signal), then
    // LUFS targeting on the final program.
    if (wrapper->parametricEq && wrapper->parametricEq->isEnabled())
    {
        wrapper->parametricEq->processInterleaved(output, inputLength / 2);
    }

    if (wrapper->dynamicEq)
    {
        dynamicEq_process_interleaved(wrapper->dynamicEq, output, inputLength / 2);
    }

    if (wrapper->loudnessCorrection && wrapper->loudnessCorrection->isEnabled())
    {
        wrapper->loudnessCorrection->processInterleaved(output, inputLength / 2);
    }

    if (wrapper->lufsNormalizer && wrapper->lufsNormalizer->enabled)
    {
        lufs_normalizer_process_interleaved(wrapper->lufsNormalizer, output, inputLength / 2);
    }

    env->ReleaseFloatArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseFloatArrayElements(outputObj, output, 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setLimiter(JNIEnv *env, jobject obj, jlong self, jboolean enable, jfloat threshold, jfloat release)
{
    DECLARE_DSP_B
    JamesDSPSetOutputControlEnabled(dsp, enable ? 1 : 0);
    JLimiterSetCoefficients(dsp, threshold, release);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setSmoothVolumeGain(JNIEnv *env, jobject obj, jlong self, jdouble gainDb)
{
    DECLARE_DSP_B
    JamesDSPSetSmoothVolumeGain(dsp, gainDb);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setPostGain(JNIEnv *env, jobject obj, jlong self, jfloat gain)
{
    DECLARE_DSP_B
    JamesDSPSetPostGain(dsp, gain);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setMultiEqualizer(JNIEnv *env, jobject obj, jlong self,
                                                                                   jboolean enable, jint filterType, jint interpolationMode,
                                                                                   jdoubleArray bands)
{
    DECLARE_DSP_B

    if(env->GetArrayLength(bands) != 30)
    {
        LOGE("JamesDspWrapper::setMultiEqualizer: Invalid EQ data. 30 semicolon-separated fields expected, "
                      "found %d fields instead.", env->GetArrayLength(bands));
        return false;
    }

    if(bands == nullptr)
    {
        LOGW("JamesDspWrapper::setMultiEqualizer: EQ band pointer is NULL. Disabling EQ");
        MultimodalEqualizerDisable(dsp);
        return true;
    }

    if(enable)
    {
        auto* nativeBands = (env->GetDoubleArrayElements(bands, nullptr));
        MultimodalEqualizerAxisInterpolation(dsp, interpolationMode, filterType, nativeBands, nativeBands + 15);
        env->ReleaseDoubleArrayElements(bands, nativeBands, JNI_ABORT);
        MultimodalEqualizerEnable(dsp, 1);
    }
    else
    {
        MultimodalEqualizerDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setVdc(JNIEnv *env, jobject obj, jlong self,
                                                                       jboolean enable, jstring vdcContents)
{
    DECLARE_DSP_B
    if(enable)
    {
        const char *nativeString = env->GetStringUTFChars(vdcContents, nullptr);
        DDCStringParser(dsp, (char*)nativeString);
        env->ReleaseStringUTFChars(vdcContents, nativeString);

        int ret = DDCEnable(dsp, 1);
        if (ret <= 0)
        {
            LOGE("JamesDspWrapper::setVdc: Call to DDCEnable(wrapper->dsp) failed. Invalid DDC parameter?");
            LOGE("JamesDspWrapper::setVdc: Disabling DDC engine");
            env->CallVoidMethod(wrapper->callbackInterface, wrapper->callbackOnVdcParseError);

            DDCDisable(dsp);
            return false;
        }
    }
    else
    {
        DDCDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setCompander(JNIEnv *env, jobject obj, jlong self,
                                                                              jboolean enable, jfloat timeConstant, jint granularity, jint tfresolution, jdoubleArray bands)
{
    DECLARE_DSP_B

    if(bands == nullptr)
    {
        LOGW("JamesDspWrapper::setCompander: Compander band pointer is NULL. Disabling compander");
        CompressorDisable(dsp);
        return true;
    }

    if(env->GetArrayLength(bands) != 14)
    {
        LOGE("JamesDspWrapper::setCompander: Invalid compander data. 14 semicolon-separated fields expected, "
             "found %d fields instead.", env->GetArrayLength(bands));
        return false;
    }

    if(enable)
    {
        // Disable before re-configuring so the realtime thread never touches a
        // half-updated state (fixes stuck/garbled compander state on preset switch)
        CompressorDisable(dsp);
        CompressorSetParam(dsp, timeConstant, granularity, tfresolution, 0);
        auto* nativeBands = (env->GetDoubleArrayElements(bands, nullptr));
        CompressorSetGain(dsp, nativeBands, nativeBands + 7, 1);
        env->ReleaseDoubleArrayElements(bands, nativeBands, JNI_ABORT);
        CompressorEnable(dsp, 1);
    }
    else
    {
        CompressorDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setReverb(JNIEnv *env, jobject obj, jlong self,
                                                                          jboolean enable, jint preset)
{
    DECLARE_DSP_B
    if(enable)
    {
        // Disable before re-configuring: Reverb_SetParam rewrites the reverb
        // state that the realtime thread is reading (stuck/garbled reverb)
        ReverbDisable(dsp);
        Reverb_SetParam(dsp, preset);
        ReverbEnable(dsp);
    }
    else
    {
        ReverbDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setConvolver(JNIEnv *env, jobject obj, jlong self,
                                                                             jboolean enable, jfloatArray impulseResponse,
                                                                             jint irChannels, jint irFrames)
{
    DECLARE_DSP_B

    int success = 1;
    if(env->GetArrayLength(impulseResponse) <= 0)
    {
        LOGW("JamesDspWrapper::setConvolver: Impulse response array is empty. Disabling convolver");
        enable = false;
    }

    if(enable)
    {
        if(irFrames <= 0)
        {
            LOGW("JamesDspWrapper::setConvolver: Impulse response has zero frames");
        }

        LOGD("JamesDspWrapper::setConvolver: Impulse response loaded: channels=%d, frames=%d", irChannels, irFrames);

        Convolver1DDisable(dsp);

        auto* nativeImpulse = (env->GetFloatArrayElements(impulseResponse, nullptr));
        success = Convolver1DLoadImpulseResponse(dsp, nativeImpulse, irChannels, irFrames, 1);
        env->ReleaseFloatArrayElements(impulseResponse, nativeImpulse, JNI_ABORT);
    }

    if(enable)
        Convolver1DEnable(dsp);
    else
        Convolver1DDisable(dsp);

    if(success <= 0)
    {
        LOGD("JamesDspWrapper::setConvolver: Failed to update convolver. Convolver1DLoadImpulseResponse returned an error.");
        return false;
    }

    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setGraphicEq(JNIEnv *env, jobject obj, jlong self,
                                                                             jboolean enable, jstring graphicEq)
{
    DECLARE_DSP_B
    if(graphicEq == nullptr || env->GetStringUTFLength(graphicEq) <= 0)
    {
        LOGE("JamesDspWrapper::setGraphicEq: graphicEq is empty or NULL. Disabling graphic eq.");
        enable = false;
    }

    if(enable)
    {
        const char *nativeString = env->GetStringUTFChars(graphicEq, nullptr);
        ArbitraryResponseEqualizerStringParser(dsp, (char*)nativeString);
        env->ReleaseStringUTFChars(graphicEq, nativeString);

        ArbitraryResponseEqualizerEnable(dsp, 1);
    }
    else
        ArbitraryResponseEqualizerDisable(dsp);

    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setCrossfeed(JNIEnv *env, jobject obj, jlong self,
                                                                             jboolean enable, jint mode, jint customFcut, jint customFeed)
{
    DECLARE_DSP_B
    // Disable before re-configuring: CrossfeedChangeMode/BS2BInit rewrite state
    // that the realtime thread is reading (stuck crossfeed on mode switch)
    CrossfeedDisable(dsp);
    if(mode == 99)
    {
        memset(&dsp->advXF.bs2b, 0, sizeof(dsp->advXF.bs2b));
        BS2BInit(&dsp->advXF.bs2b[1], (unsigned int)dsp->fs, ((unsigned int)customFcut | ((unsigned int)customFeed << 16)));
        dsp->advXF.mode = 1;
    }
    else
    {
       CrossfeedChangeMode(dsp, mode);
    }

    if(enable)
        CrossfeedEnable(dsp, 1);
    else
        CrossfeedDisable(dsp);

    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setBassBoost(JNIEnv *env, jobject obj, jlong self,
                                                                             jboolean enable, jfloat maxGain)
{
    DECLARE_DSP_B
    if(enable)
    {
        BassBoostSetParam(dsp, maxGain);
        BassBoostEnable(dsp);
    }
    else
    {
        BassBoostDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setStereoEnhancement(JNIEnv *env, jobject obj, jlong self,
                                                                                     jboolean enable, jfloat level)
{
    DECLARE_DSP_B
    StereoEnhancementDisable(dsp);
    StereoEnhancementSetParam(dsp, level / 100.0f);
    if(enable)
    {
        StereoEnhancementEnable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setVacuumTube(JNIEnv *env, jobject obj, jlong self,
                                                                              jboolean enable, jfloat level)
{
    DECLARE_DSP_B
    if(enable)
    {
        VacuumTubeSetGain(dsp, level / 100.0f);
        VacuumTubeEnable(dsp);
    }
    else
    {
        VacuumTubeDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setHarmonicExpander(JNIEnv *env, jobject obj, jlong self,
    jboolean enable, jfloatArray harmonicGains, jfloat crossoverFreq, jfloat mix)
{
    DECLARE_DSP_B
    jfloat gains[9] = {0};
    if (harmonicGains != nullptr)
    {
        jsize len = env->GetArrayLength(harmonicGains);
        if (len > 9) len = 9;
        env->GetFloatArrayRegion(harmonicGains, 0, len, gains);
    }
    if (enable)
    {
        HarmonicExpanderSetParam(dsp, gains, crossoverFreq, mix / 100.0f);
        HarmonicExpanderEnable(dsp);
    }
    else
    {
        HarmonicExpanderDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setSubHarmonicExpander(JNIEnv *env, jobject obj, jlong self,
    jboolean enable, jfloatArray subHarmonicGains, jfloat crossoverFreq, jfloat mix)
{
    DECLARE_DSP_B
    jfloat gains[9] = {0};
    if (subHarmonicGains != nullptr)
    {
        jsize len = env->GetArrayLength(subHarmonicGains);
        if (len > 9) len = 9;
        env->GetFloatArrayRegion(subHarmonicGains, 0, len, gains);
    }
    if (enable)
    {
        SubHarmonicExpanderSetParam(dsp, gains, crossoverFreq, mix / 100.0f);
        SubHarmonicExpanderEnable(dsp);
    }
    else
    {
        SubHarmonicExpanderDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setNosR2R(JNIEnv *env, jobject obj, jlong self,
                                                                            jboolean enable, jdouble targetRate, jint bitDepth,
                                                                            jdouble resistorTolerance, jint deviationGrowth,
                                                                            jint harmony, jlong serialNumber,
                                                                            jdouble jitterAmount, jdouble harmonicsAmount, jboolean invertPhase)
{
    DECLARE_DSP_B
    if(enable)
    {
        NosR2RSetParam(dsp, targetRate, bitDepth, resistorTolerance, deviationGrowth,
                       harmony, (long)serialNumber, jitterAmount, harmonicsAmount, invertPhase ? 1 : 0);
        NosR2REnable(dsp);
    }
    else
    {
        NosR2RDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setLiveprog(JNIEnv *env, jobject obj, jlong self,
                                                                            jboolean enable, jstring id, jstring liveprogContent)
{
    DECLARE_DSP_B

    // Attach log listener
    setStdOutHandler(receiveLiveprogStdOut, wrapper);

    LiveProgDisable(dsp);

    const char *nativeString = env->GetStringUTFChars(liveprogContent, nullptr);
    if(nativeString == nullptr)
        return false;
    if(strlen(nativeString) < 1) {
        LOGD("JamesDspWrapper::setLiveprog: empty file")
        env->ReleaseStringUTFChars(liveprogContent, nativeString);
        return true;
    }

    env->CallVoidMethod(wrapper->callbackInterface, wrapper->callbackOnLiveprogExec, id);

    int ret = LiveProgStringParser(dsp, (char*)nativeString); // Ignore constness, libjamesdsp does not modify it
    env->ReleaseStringUTFChars(liveprogContent, nativeString);

    // NSEEL_code_getcodeerror may return NULL; route it through std::string so
    // NewStringUTF never receives a dangling or null pointer
    const char *compileError = NSEEL_code_getcodeerror(dsp->eel.vm);
    const std::string errorString = compileError != nullptr ? compileError : "";
    if(!errorString.empty())
    {
        LOGW("JamesDspWrapper::setLiveprog: NSEEL_code_getcodeerror: Syntax error in script file, cannot load. Reason: %s", errorString.c_str());
    }
    if(ret <= 0)
    {
        LOGW("JamesDspWrapper::setLiveprog: %s", checkErrorCode(ret));
    }

    jstring errorStringJni = env->NewStringUTF(errorString.c_str());
    env->CallVoidMethod(wrapper->callbackInterface, wrapper->callbackOnLiveprogResult, ret, id, errorStringJni);
    env->DeleteLocalRef(errorStringJni);

    // Only enable the engine when the script actually compiled; a broken
    // script must never enter the audio path
    if(enable && ret > 0)
        LiveProgEnable(dsp);
    else
        LiveProgDisable(dsp);
    return ret > 0;
}


extern "C" JNIEXPORT jobject JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_enumerateEelVariables(JNIEnv *env, jobject obj, jlong self)
{
    auto array = JArrayList(env);

    // Return empty array if DECLARE failed
    DECLARE_DSP(array.getJavaReference())

    // The VM table is rebuilt by LiveProgStringParser (under jdsp_lock) when a
    // script is (re)loaded; enumerate under the same lock and bail out when no
    // VM exists, otherwise this races with the parser and can read freed memory
    jdsp_lock(dsp);
    if(dsp->eel.vm == nullptr)
    {
        jdsp_unlock(dsp);
        return array.getJavaReference();
    }

    auto *ctx = (compileContext*)dsp->eel.vm;
    for (int i = 0; i < ctx->varTable_numBlocks; i++)
    {
        for (int j = 0; j < NSEEL_VARS_PER_BLOCK; j++)
        {
            // TODO fix string handling (broke after last libjamesdsp update)
            const char *valid = nullptr;//(char*)GetStringForIndex(ctx->region_context, ctx->varTable_Values[i][j], 1);
            bool isString = valid;

            if (ctx->varTable_Names[i][j])
            {
                const char* name = ctx->varTable_Names[i][j];
                // Keep the string in a named local: std::to_string(...).c_str()
                // returns a pointer into a temporary that dies at the end of
                // the full expression (dangling pointer / use-after-free)
                const std::string numericValue = isString
                    ? std::string()
                    : std::to_string(ctx->varTable_Values[i][j]);
                const char* value = isString ? valid : numericValue.c_str();

                auto var = EelVmVariable(env, name, value, isString);
                array.add(var.getJavaReference());
            }
        }
    }

    jdsp_unlock(dsp);
    return array.getJavaReference();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_manipulateEelVariable(JNIEnv *env, jobject obj, jlong self,
                                                                                      jstring name, jfloat value)
{
    DECLARE_DSP_B
    const char *nativeName = env->GetStringUTFChars(name, nullptr);
    if(nativeName == nullptr)
        return false;

    // Writing varTable_Values races with script execution on the audio thread;
    // do it under jdsp_lock, and bail out when no VM is loaded
    jdsp_lock(dsp);
    if(dsp->eel.vm == nullptr)
    {
        jdsp_unlock(dsp);
        env->ReleaseStringUTFChars(name, nativeName);
        return false;
    }

    auto* ctx = (compileContext*)dsp->eel.vm;
    for (int i = 0; i < ctx->varTable_numBlocks; i++)
    {
        for (int j = 0; j < NSEEL_VARS_PER_BLOCK; j++)
        {
            if(!ctx->varTable_Names[i][j] || std::strcmp(ctx->varTable_Names[i][j], nativeName) != 0)
                continue;

            const char *valid = nullptr;//(char*)GetStringForIndex(ctx->region_context, ctx->varTable_Values[i][j], 1);
            if(valid)
            {
                LOGE("JamesDspWrapper::manipulateEelVariable: variable '%s' is a string; currently only numerical variables can be manipulated", nativeName);
                jdsp_unlock(dsp);
                env->ReleaseStringUTFChars(name, nativeName);
                return false;
            }

            ctx->varTable_Values[i][j] = value;

            jdsp_unlock(dsp);
            env->ReleaseStringUTFChars(name, nativeName);
            return true;
        }
    }

    LOGE("JamesDspWrapper::manipulateEelVariable: variable '%s' not found", nativeName);
    jdsp_unlock(dsp);
    env->ReleaseStringUTFChars(name, nativeName);
    return false;
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_freezeLiveprogExecution(JNIEnv *env, jobject obj, jlong self,
                                                                                        jboolean freeze)
{
    DECLARE_DSP_V
    jdsp_lock(dsp);
    if(dsp->eel.vm != nullptr)
        dsp->eel.active = !freeze;
    jdsp_unlock(dsp);
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_eelErrorCodeToString(JNIEnv *env,
                                                                                     jobject obj,
                                                                                     jint error_code)
{
    return env->NewStringUTF(checkErrorCode(error_code));
}

void receiveLiveprogStdOut(const char *buffer, void* userData)
{
    // Логирование в logcat (аналог fwrite(stdout) в Linux-версии)
    __android_log_print(ANDROID_LOG_INFO, "LiveProg", "%s", buffer);

    auto* self = static_cast<JamesDspWrapper*>(userData);
    if(self == nullptr)
    {
        LOGE("JamesDspWrapper::receiveLiveprogStdOut: Self reference is NULL");
        return;
    }

    self->env->CallVoidMethod(self->callbackInterface, self->callbackOnLiveprogOutput, self->env->NewStringUTF(buffer));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setParametricEq(JNIEnv *env, jobject obj,
    jlong self, jboolean enable, jdouble sampleRate, jdouble preampDb,
    jdoubleArray freqArr, jdoubleArray gainArr, jdoubleArray qArr,
    jintArray typeArr, jintArray chanArr)
{
    DECLARE_WRAPPER_B
    if (wrapper->parametricEq == nullptr)
        return false;

    if (!enable)
    {
        wrapper->parametricEq->setEnabled(false);
        return true;
    }

    jsize count = env->GetArrayLength(freqArr);
    if (count <= 0 || count != env->GetArrayLength(gainArr) ||
        count != env->GetArrayLength(qArr) ||
        count != env->GetArrayLength(typeArr) ||
        count != env->GetArrayLength(chanArr))
    {
        LOGE("JamesDspWrapper::setParametricEq: array length mismatch or empty. count=%d", count);
        wrapper->parametricEq->setEnabled(false);
        return false;
    }

    auto* freqs  = env->GetDoubleArrayElements(freqArr, nullptr);
    auto* gains  = env->GetDoubleArrayElements(gainArr, nullptr);
    auto* qs     = env->GetDoubleArrayElements(qArr, nullptr);
    auto* types  = env->GetIntArrayElements(typeArr, nullptr);
    auto* chans  = env->GetIntArrayElements(chanArr, nullptr);

    std::vector<ParametricEqBandConfig> bands(count);
    for (jsize i = 0; i < count; ++i)
    {
        bands[i].filterType   = types[i];
        bands[i].frequency    = freqs[i];
        bands[i].gain         = gains[i];
        bands[i].q            = qs[i];
        bands[i].channelMode  = chans[i];
        bands[i].enabled      = true;
    }

    env->ReleaseDoubleArrayElements(freqArr, freqs, JNI_ABORT);
    env->ReleaseDoubleArrayElements(gainArr, gains, JNI_ABORT);
    env->ReleaseDoubleArrayElements(qArr, qs, JNI_ABORT);
    env->ReleaseIntArrayElements(typeArr, types, JNI_ABORT);
    env->ReleaseIntArrayElements(chanArr, chans, JNI_ABORT);

    wrapper->parametricEq->configure(sampleRate, bands.data(), bands.size(), preampDb);
    wrapper->parametricEq->setEnabled(true);

    LOGD("JamesDspWrapper::setParametricEq: configured %d bands, preamp=%.1f dB, sr=%.0f",
         count, preampDb, sampleRate);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setDynamicEq(JNIEnv *env, jobject obj,
    jlong self, jboolean enable, jdouble sampleRate, jfloatArray configArr,
    jfloat mix, jint msMode)
{
    DECLARE_WRAPPER_B
    if (wrapper->dynamicEq == nullptr)
        return false;

    jsize length = configArr != nullptr ? env->GetArrayLength(configArr) : 0;
    if (length % DYNEQ_VALUES_PER_BAND != 0)
    {
        LOGE("JamesDspWrapper::setDynamicEq: config length %d is not a multiple of %d",
             length, DYNEQ_VALUES_PER_BAND);
        return false;
    }

    jsize count = length / DYNEQ_VALUES_PER_BAND;
    if (count > DYNEQ_MAX_BANDS)
        count = DYNEQ_MAX_BANDS;

    if (!enable || count == 0 || sampleRate <= 0.0)
    {
        dynamicEq_set_enabled(wrapper->dynamicEq, false);
        return true;
    }

    auto* config = env->GetFloatArrayElements(configArr, nullptr);
    if (config == nullptr)
        return false;

    dynamicEq_configure(wrapper->dynamicEq, sampleRate, config, (int)count);
    dynamicEq_set_mix_ms(wrapper->dynamicEq, (double)mix, (int)msMode);
    dynamicEq_set_enabled(wrapper->dynamicEq, true);

    env->ReleaseFloatArrayElements(configArr, config, JNI_ABORT);

    LOGD("JamesDspWrapper::setDynamicEq: configured %d bands, mix=%.0f%%, ms=%d, sr=%.0f",
         count, mix, msMode, sampleRate);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setLoudnessCorrection(JNIEnv *env, jobject obj,
    jlong self, jboolean enable, jdouble sampleRate, jdouble referenceLevel,
    jdouble referenceOffset, jdouble attenuation, jdouble currentVolumeDb,
    jint mode)
{
    DECLARE_WRAPPER_B
    if (wrapper->loudnessCorrection == nullptr)
        return false;

    if (!enable)
    {
        wrapper->loudnessCorrection->setEnabled(false);
        return true;
    }

    // Biquad coefficients depend on sample rate; applying with sr=0 produces
    // invalid coefficients (division by zero). Skip until valid.
    if (sampleRate <= 0.0)
    {
        LOGW("JamesDspWrapper::setLoudnessCorrection: skipping, sample rate not yet known (%.0f)", sampleRate);
        return false;
    }

    wrapper->loudnessCorrection->configure(sampleRate, referenceLevel,
                                           referenceOffset, attenuation,
                                           (int)mode);
    wrapper->loudnessCorrection->setVolume(currentVolumeDb);
    wrapper->loudnessCorrection->setEnabled(true);

    LOGD("JamesDspWrapper::setLoudnessCorrection: refLevel=%.1f dB, refOffset=%.1f dB, att=%.2f, vol=%.1f dB, sr=%.0f, mode=%d",
         referenceLevel, referenceOffset, attenuation, currentVolumeDb, sampleRate, (int)mode);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setLoudnessTuning(
    JNIEnv *env, jobject obj,
    jlong self,
    jdouble lsFreq, jdouble lsSlope, jdouble lsRatio,
    jdouble hsFreq, jdouble hsSlope, jdouble hsRatio,
    jdouble isoBasePhon, jdouble isoQ)
{
    DECLARE_WRAPPER_B
    if (wrapper->loudnessCorrection == nullptr)
        return false;

    wrapper->loudnessCorrection->setTuningParams(
        lsFreq, lsSlope, lsRatio,
        hsFreq, hsSlope, hsRatio,
        isoBasePhon, isoQ);

    LOGD("JamesDspWrapper::setLoudnessTuning: lsFreq=%.0f, lsSlope=%.2f, lsRatio=%.2f, "
         "hsFreq=%.0f, hsSlope=%.2f, hsRatio=%.3f, isoBasePhon=%.0f, isoQ=%.2f",
         lsFreq, lsSlope, lsRatio, hsFreq, hsSlope, hsRatio, isoBasePhon, isoQ);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setLoudnessSubsonic(
    JNIEnv *env, jobject obj,
    jlong self, jboolean enable, jdouble freq, jint order, jdouble qFactor)
{
    DECLARE_WRAPPER_B
    if (wrapper->loudnessCorrection == nullptr)
        return false;

    wrapper->loudnessCorrection->setSubsonicFilter(
        enable, freq, (int)order, qFactor);

    LOGD("JamesDspWrapper::setLoudnessSubsonic: enable=%d, freq=%.1f Hz, order=%d, Q=%.3f",
         enable, freq, (int)order, qFactor);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setLoudnessCorrectionVolume(JNIEnv *env, jobject obj,
    jlong self, jdouble currentVolumeDb)
{
    DECLARE_WRAPPER_B
    if (wrapper->loudnessCorrection == nullptr)
        return false;

    wrapper->loudnessCorrection->setVolume(currentVolumeDb);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setLufsNormalizer(JNIEnv *env, jobject obj,
    jlong self, jboolean enable, jdouble sampleRate, jdouble targetLufs,
    jdouble maxGainDb, jint mode)
{
    DECLARE_WRAPPER_B
    if (wrapper->lufsNormalizer == nullptr)
        return false;

    if (!enable)
    {
        lufs_normalizer_set_enabled(wrapper->lufsNormalizer, false);
        return true;
    }

    // K-weighting coefficients and window sizes depend on the sample rate;
    // applying with sr=0 would produce invalid filters. Re-applied when the
    // rate becomes known.
    if (sampleRate <= 0.0)
    {
        LOGW("JamesDspWrapper::setLufsNormalizer: skipping, sample rate not yet known (%.0f)", sampleRate);
        return false;
    }

    lufs_normalizer_configure(wrapper->lufsNormalizer, sampleRate,
                              targetLufs, maxGainDb, (int)mode);
    lufs_normalizer_set_enabled(wrapper->lufsNormalizer, true);

    LOGD("JamesDspWrapper::setLufsNormalizer: target=%.1f LUFS, maxGain=%.1f dB, mode=%d, sr=%.0f",
         targetLufs, maxGainDb, (int)mode, sampleRate);
    return true;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *)
{
#ifndef NO_CRASHLYTICS
    firebase::crashlytics::Initialize();
#endif
    LOGD("JNI_OnLoad called")
    return JNI_VERSION_1_6;
}
