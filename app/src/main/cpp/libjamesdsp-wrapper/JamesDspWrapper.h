#ifndef DSPHOST_H
#define DSPHOST_H

#include <jni.h>

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
} JamesDspWrapper;

/* C interop function */
static void receiveLiveprogStdOut(const char* buffer, void* userData);

#endif // DSPHOST_H
