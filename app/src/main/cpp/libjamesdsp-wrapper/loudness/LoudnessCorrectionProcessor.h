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
#include <mutex>
#include <vector>

/* Loudness compensation mode */
#define LOUDNESS_MODE_CLASSIC   0  /* Fletcher-Munson: two-shelf heuristic */
#define LOUDNESS_MODE_ISO226    1  /* ISO 226:2023: 29-band contour-based */

/* Number of ISO 226 frequency bands */
#define ISO226_NUM_BANDS 29

/* First-order RC high-pass (6 dB/oct) used as the trailing stage of an
 * odd-order subsonic cascade: y[n] = alpha * (y[n-1] + x[n] - x[n-1])
 * with alpha = rc / (rc + dt), rc = 1 / (2*pi*fc), dt = 1/sampleRate.
 * The first-order stage runs FIRST in the cascade (closest to the input).
 * Shared spec between C (peq_loudness.c), C++ and Kotlin
 * (LoudnessCurveCalculator). */
struct FirstOrderHighPass
{
    double alpha;
    double x1, y1;
    bool active;

    FirstOrderHighPass() : alpha(0.0), x1(0.0), y1(0.0), active(false) {}

    void configure(double cutoffHz, double sampleRate, bool enable)
    {
        active = enable;
        x1 = 0.0;
        y1 = 0.0;
        alpha = 0.0;
        if (!enable)
            return;
        double rc = 1.0 / (2.0 * M_PI * cutoffHz);
        double dt = 1.0 / sampleRate;
        alpha = rc / (rc + dt);
    }

    inline double process(double sample)
    {
        double result = alpha * (y1 + sample - x1);
        x1 = sample;
        y1 = result;
        return result;
    }

    inline void removeDenormals()
    {
        const double denorm_limit = 2.2250738585072014e-308; // DBL_MIN
        if (std::abs(x1) < denorm_limit) x1 = 0.0;
        if (std::abs(y1) < denorm_limit) y1 = 0.0;
    }
};

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
    //   freq    — cutoff frequency (Hz, 5–200)
    //   order   — filter order 1–8, 6 dB/oct per order. Implemented as
    //             floor(order/2) 2nd-order biquad stages plus one first-order
    //             stage for odd orders (shared spec with C and Kotlin UI).
    //   qFactor — Q of each 2nd-order stage (0.1–2.0, Butterworth = 0.707)
    void setSubsonicFilter(bool enable, double freq, int order, double qFactor);

    // Process interleaved stereo float audio in-place.
    // Each frame is [L, R]. numFrames = number of frames (not samples).
    // Called from the audio thread, AFTER the main JamesDSP chain.
    void processInterleaved(float* data, size_t numFrames);

    // Process deinterleaved stereo float audio in-place.
    void processDeinterleaved(float* left, float* right, size_t numFrames);

private:
    // ---- Config snapshot (BUG-06) ----
    // Written by the config thread under configMutex; the audio thread copies
    // it under the same short lock before recomputing coefficients. Setters
    // never touch biquads directly, and the audio thread never reads
    // half-written parameters, so no torn coefficients are possible.
    struct ConfigParams
    {
        double sampleRate;
        double referenceLevel;
        double referenceOffset;
        double attenuation;
        int mode;
        double lsFreq, lsSlope, lsRatio;
        double hsFreq, hsSlope, hsRatio;
        double isoBasePhon, isoQ;
        bool subsonicEnabled;
        double subsonicFreq;
        int subsonicOrder;
        double subsonicQ;
    };
    ConfigParams config;
    std::mutex configMutex;

    // Current volume — written from config thread, read on audio thread
    std::atomic<double> volumeDb;
    // Flag: parameters changed and coefficients need recomputation
    std::atomic<bool> coeffsDirty;

    std::atomic<bool> enabled;

    // ---- Coefficient block: written only by recomputeCoefficients() on the
    // audio thread, read only by process*() on the audio thread ----
    double attFactor;
    bool neutral;
    int activeMode;
    int activeSubsonicStages;
    bool activeSubsonicFirstOrder;

    // Per-channel biquads (stereo)
    BiQuad lowShelfL;
    BiQuad lowShelfR;
    BiQuad highShelfL;
    BiQuad highShelfR;

    // ---- Subsonic (infrasonic) high-pass filter ----
    static const int MAX_SUBSONIC_STAGES = 4; // 4 biquads = 8th order
    BiQuad subsonicL[MAX_SUBSONIC_STAGES];
    BiQuad subsonicR[MAX_SUBSONIC_STAGES];
    FirstOrderHighPass subsonicFirstL; // odd-order trailing stage
    FirstOrderHighPass subsonicFirstR;

    // ---- ISO 226 mode: 29-band peaking EQ cascade ----
    BiQuad isoBandsL[ISO226_NUM_BANDS];
    BiQuad isoBandsR[ISO226_NUM_BANDS];
    double isoPreampLinear;
    int isoActiveBandCount;

    // Temp buffers for deinterleaved processing
    std::vector<float> tmpL;
    std::vector<float> tmpR;

    // Compute shelf gains from the volume difference.
    // Mirrors getLShelfParamter / getHShelfParamter from the original.
    void getLowShelfParams(const ConfigParams& cfg, double volume,
                           double& freq, double& s, double& gain, double& preAmp);
    void getHighShelfParams(const ConfigParams& cfg, double volume,
                            double& freq, double& s, double& gain);

    // Recompute biquad coefficients from the current volume + parameters.
    // Audio-thread only; [cfg] is the config snapshot copied under
    // configMutex. Filter state policy: biquads are rebuilt with cleared
    // state (same as the pre-BUG-06 behaviour), which is applied between
    // audio blocks — a click-free boundary.
    void recomputeCoefficients(double volume, const ConfigParams& cfg);

    // Compute ISO 226:2023 loudness correction gains for all 29 bands.
    // Returns the max positive gain (for pre-amp computation).
    double computeIso226Gains(const ConfigParams& cfg, double volume,
                              double gains[ISO226_NUM_BANDS]);

    // ISO 226:2023 Table 1 data (29 one-third-octave bands)
    static const double iso226Freqs[ISO226_NUM_BANDS];
    static const double iso226Af[ISO226_NUM_BANDS];
    static const double iso226Lu[ISO226_NUM_BANDS];
    static const double iso226Tf[ISO226_NUM_BANDS];
};
