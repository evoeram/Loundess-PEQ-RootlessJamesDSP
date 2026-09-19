/*
 * LoudnessCorrectionProcessor — time-domain loudness compensation for
 * RootlessJamesDSP.
 *
 * Ported from EqualizerAPO's filters/loudnessCorrection/LoudnessCorrectionFilter
 * (Copyright (C) 2017 Alexander Walch, GPLv2).
 *
 * Principle (Fletcher–Munson loudness compensation):
 *  At low listening volumes the human ear is less sensitive to bass and
 *  (to a lesser degree) treble. This filter measures the difference between
 *  the current playback volume and a user-defined reference level, then
 *  applies a low-shelf boost at 75 Hz and a high-shelf boost at 10 kHz that
 *  scales with that difference. A pre-amp attenuation keeps the overall
 *  loudness roughly constant.
 *
 * Differences from the EqualizerAPO original:
 *  - No Windows VolumeController / background polling thread. The current
 *    playback volume is pushed in from the Kotlin layer (which can read the
 *    Android media stream volume) via setVolume().
 *  - Coefficient recomputation happens lazily on the audio thread when the
 *    volume parameter has changed (lock-free via std::atomic<double>).
 *  - Uses the existing ported BiQuad class with S-slope shelf mode, which
 *    produces coefficient math identical to the original's
 *    upDateBiquadCoefficients() runtime update path.
 *  - Processes interleaved stereo float audio in-place, mirroring
 *    ParametricEqProcessor's integration pattern.
 */

#pragma once

#include "../biquad/BiQuad.h"
#include <atomic>
#include <cstdint>
#include <vector>

class LoudnessCorrectionProcessor
{
public:
    LoudnessCorrectionProcessor();
    ~LoudnessCorrectionProcessor();

    // Set base parameters and allocate filter state.
    // Called from the config thread only.
    //   sampleRate      — current DSP sample rate (Hz)
    //   referenceLevel  — volume level at which no correction is needed (dB)
    //   referenceOffset — offset subtracted from the reference level (dB)
    //   attenuation     — correction strength scaler, clamped to [0, 2]
    void configure(double sampleRate, double referenceLevel,
                   double referenceOffset, double attenuation);

    // Push the current playback volume (dB). Safe to call from any thread.
    // Coefficients are recomputed lazily on the next process() call.
    void setVolume(double volumeDb);

    // Enable / disable the entire loudness correction.
    void setEnabled(bool enabled);
    bool isEnabled() const { return enabled; }

    // Process interleaved stereo float audio in-place.
    // Each frame is [L, R]. numFrames = number of frames (not samples).
    // Called from the audio thread, AFTER the main JamesDSP chain.
    void processInterleaved(float* data, size_t numFrames);

    // Process deinterleaved stereo float audio in-place.
    void processDeinterleaved(float* left, float* right, size_t numFrames);

private:
    // Base parameters (set from config thread, read-only on audio thread
    // after configure() completes)
    double sampleRate;
    double referenceLevel;
    double referenceOffset;
    double attenuation;

    // Current volume — written from config thread, read on audio thread
    std::atomic<double> volumeDb;
    // Last volume for which coefficients were computed (audio thread only)
    double lastComputedVolume;
    // Flag: parameters changed and coefficients need recomputation
    std::atomic<bool> coeffsDirty;

    bool enabled;

    // Pre-amp linear gain factor applied between low and high shelf
    double attFactor;
    // Whether the correction is near-zero (bypass for best quality)
    bool neutral;

    // Per-channel biquads (stereo)
    BiQuad lowShelfL;
    BiQuad lowShelfR;
    BiQuad highShelfL;
    BiQuad highShelfR;

    // Temp buffers for deinterleaved processing
    std::vector<float> tmpL;
    std::vector<float> tmpR;

    // Compute shelf gains from the volume difference.
    // Mirrors getLShelfParamter / getHShelfParamter from the original.
    void getLowShelfParams(double volume, double& freq, double& s,
                           double& gain, double& preAmp);
    void getHighShelfParams(double volume, double& freq, double& s,
                            double& gain);

    // Recompute biquad coefficients from the current volume + parameters.
    // Audio-thread only.
    void recomputeCoefficients(double volume);
};
