#ifndef DSPHOST_H
#define DSPHOST_H

#include <jni.h>

#include <Effects/dynamicEq.h>
#include <Effects/lufs_normalizer.h>

// Forward declaration (opaque pointer for C interop)
class ParametricEqProcessor;
class LoudnessCorrectionProcessor;

typedef struct
{
    void* dsp;
    JNIEnv* env;
    jobject callbackInterface;
    jmethodID callbackOnLiveprogOutput;
    jmethodID callbackOnLiveprogExec;
    jmethodID callbackOnLiveprogResult;
    jmethodID callbackOnVdcParseError;
    ParametricEqProcessor* parametricEq;
    LoudnessCorrectionProcessor* loudnessCorrection;
    dynamicEq_t* dynamicEq;
    lufs_normalizer_t* lufsNormalizer;
} JamesDspWrapper;

/* C interop function */
static void receiveLiveprogStdOut(const char* buffer, void* userData);

#endif // DSPHOST_H
