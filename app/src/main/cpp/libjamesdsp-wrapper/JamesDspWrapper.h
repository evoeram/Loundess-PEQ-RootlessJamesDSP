#ifndef DSPHOST_H
#define DSPHOST_H

#include <jni.h>
#include <vector>

#include <Effects/dynamicEq.h>
#include <Effects/lufs_normalizer.h>
#include <Effects/fet_compressor.h>
#include <Effects/viper_clarity.h>
#include <Effects/field_surround.h>
#include <Effects/viper_agc.h>
#include <Effects/cure.h>
#include <Effects/tape.h>

// Forward declaration (opaque pointer for C interop)
class ParametricEqProcessor;
class LoudnessCorrectionProcessor;

typedef struct
{
    void* dsp;
    JavaVM* vm;
    jobject callbackInterface;
    jmethodID callbackOnLiveprogOutput;
    jmethodID callbackOnLiveprogExec;
    jmethodID callbackOnLiveprogResult;
    jmethodID callbackOnVdcParseError;
    ParametricEqProcessor* parametricEq;
    LoudnessCorrectionProcessor* loudnessCorrection;
    dynamicEq_t* dynamicEq;
    lufs_normalizer_t* lufsNormalizer;
    fet_compressor_t* fetCompressor;
    viper_clarity_t* viperClarity;
    field_surround_t* fieldSurround;
    viper_agc_t* viperAgc;
    cure_t* cure;
    tape_t* tape;
    // Audio-thread-only conversion scratch. Retained across blocks to avoid
    // allocator work and zero-initialisation in the PCM16 real-time path.
    std::vector<float> pcm16Scratch;
} JamesDspWrapper;

/* C interop function */
static void receiveLiveprogStdOut(const char* buffer, void* userData);

#endif // DSPHOST_H
