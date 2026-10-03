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
 * Two modes are supported:
 *  - Mode 0 (Classic): Fletcher-Munson heuristic with two shelves (original).
 *  - Mode 1 (ISO 226:2023): Uses the ISO 226:2023 equal-loudness-level contour
 *    formula to compute the exact frequency-dependent gain curve that
 *    compensates for the perceptual difference between the reference and
 *    current listening level. Applied as a 29-band 1/3-octave peaking-EQ
 *    cascade (20 Hz – 12.5 kHz).
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

/* Loudness compensation mode */
#define LOUDNESS_MODE_CLASSIC   0  /* Fletcher-Munson: two-shelf heuristic */
#define LOUDNESS_MODE_ISO226    1  /* ISO 226:2023: 29-band contour-based */

/* Number of ISO 226 frequency bands */
#define ISO226_NUM_BANDS 29

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
    //   mode            — 0 = classic (Fletcher-Munson), 1 = ISO 226:2023
    void configure(double sampleRate, double referenceLevel,
                   double referenceOffset, double attenuation,
                   int mode = LOUDNESS_MODE_CLASSIC);

    // Push the current playback volume (dB). Safe to call from any thread.
    // Coefficients are recomputed lazily on the next process() call.
    void setVolume(double volumeDb);

    // Enable / disable the entire loudness correction.
    void setEnabled(bool enabled);
    bool isEnabled() const { return enabled; }

    // Set user-tunable shelf/contour parameters.
    // Defaults match the original EqualizerAPO hardcoded values.
    //   lsFreq     — low shelf center frequency (Hz, default 75)
    //   lsSlope    — low shelf S slope (default 0.52)
    //   lsRatio    — low shelf gain ratio (default 0.55)
    //   hsFreq     — high shelf center frequency (Hz, default 10000)
    //   hsSlope    — high shelf S slope (default 0.90)
    //   hsRatio    — high shelf gain ratio (default 0.225)
    //   isoBasePhon — ISO 226 base phon level (default 80)
    //   isoQ       — ISO 226 peaking Q (default 4.318)
    void setTuningParams(double lsFreq, double lsSlope, double lsRatio,
                         double hsFreq, double hsSlope, double hsRatio,
                         double isoBasePhon, double isoQ);

    // Set subsonic (infrasonic) high-pass filter parameters.
    //   enable  — whether the subsonic filter is active
    //   freq    — cutoff frequency (Hz, 10–100)
    //   order   — filter order 1–8 (each step = 12 dB/oct, 2nd-order biquad stages)
    //   qFactor — Q of each 2nd-order stage (0.1–2.0, Butterworth = 0.707)
    void setSubsonicFilter(bool enable, double freq, int order, double qFactor);

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
    int mode;

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

    // ---- User-tunable parameters (defaults match original hardcoded values) ----
    double lsFreq;     // low shelf frequency (Hz)
    double lsSlope;    // low shelf S slope
    double lsRatio;    // low shelf gain ratio
    double hsFreq;     // high shelf frequency (Hz)
    double hsSlope;    // high shelf S slope
    double hsRatio;    // high shelf gain ratio
    double isoBasePhon; // ISO 226 base phon level
    double isoQ;       // ISO 226 peaking Q

    // ---- Subsonic (infrasonic) high-pass filter ----
    bool subsonicEnabled;
    double subsonicFreq;    // cutoff Hz
    int subsonicOrder;      // 1–8 (biquad stages = ceil(order/2))
    double subsonicQ;       // Q per 2nd-order stage
    static const int MAX_SUBSONIC_STAGES = 4; // 4 biquads = 8th order
    BiQuad subsonicL[MAX_SUBSONIC_STAGES];
    BiQuad subsonicR[MAX_SUBSONIC_STAGES];

    // ---- ISO 226 mode: 29-band peaking EQ cascade ----
    BiQuad isoBandsL[ISO226_NUM_BANDS];
    BiQuad isoBandsR[ISO226_NUM_BANDS];
    double isoGains[ISO226_NUM_BANDS];
    double isoPreampLinear;
    int isoActiveBandCount;

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

    // Compute ISO 226:2023 loudness correction gains for all 29 bands.
    // Returns the max positive gain (for pre-amp computation).
    double computeIso226Gains(double volume, double gains[ISO226_NUM_BANDS]);

    // ISO 226:2023 Table 1 data (29 one-third-octave bands)
    static const double iso226Freqs[ISO226_NUM_BANDS];
    static const double iso226Af[ISO226_NUM_BANDS];
    static const double iso226Lu[ISO226_NUM_BANDS];
    static const double iso226Tf[ISO226_NUM_BANDS];
};
