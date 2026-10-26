/*
 * ParametricEqProcessor — time-domain biquad cascade for RootlessJamesDSP.
 *
 * Uses the ported BiQuad logic from EqualizerAPO to process audio directly
 * in the time domain (not via FFT magnitude approximation like the existing
 * GraphicEQ-based PEQ path).
 *
 * Features:
 *  - All 8 RBJ biquad filter types (Peaking, Low/High-pass, Band-pass,
 *    Low/High-shelf, Notch, All-pass)
 *  - Per-band channel routing: L+R (both), L only, R only
 *  - Unlimited number of bands (dynamically allocated)
 *  - Per-band enable/disable
 *  - Preamp gain
 */

#pragma once

#include "BiQuad.h"
#include <atomic>
#include <vector>
#include <cstdint>
#include <cstddef>
#include <memory>
#include <string>

// Filter type codes (match Kotlin ParametricEqFilterType enum codes)
enum ParametricEqFilterType : int
{
    PEQ_PEAKING     = 0,
    PEQ_LOW_SHELF   = 1,
    PEQ_HIGH_SHELF  = 2,
    PEQ_LOW_PASS    = 3,
    PEQ_HIGH_PASS   = 4,
    PEQ_BAND_PASS   = 5,
    PEQ_NOTCH       = 6,
    PEQ_ALL_PASS    = 7,
    PEQ_PREAMP      = 8, // Flat frequency-independent gain stage (ported PreampFilter)
};

// Channel routing modes
enum ParametricEqChannelMode : int
{
    PEQ_CHAN_BOTH = 0, // L+R
    PEQ_CHAN_LEFT  = 1, // L only
    PEQ_CHAN_RIGHT = 2, // R only
};

// Configuration for a single band
struct ParametricEqBandConfig
{
    int filterType;       // ParametricEqFilterType
    double frequency;     // Hz
    double gain;          // dB
    double q;             // Q factor (or bandwidth/S for shelves)
    int channelMode;      // ParametricEqChannelMode
    bool enabled;
};

// Map our filter type codes to BiQuad::Type
inline BiQuad::Type toBiQuadType(int ft)
{
    switch (ft)
    {
    case PEQ_PEAKING:    return BiQuad::PEAKING;
    case PEQ_LOW_SHELF:  return BiQuad::LOW_SHELF;
    case PEQ_HIGH_SHELF: return BiQuad::HIGH_SHELF;
    case PEQ_LOW_PASS:   return BiQuad::LOW_PASS;
    case PEQ_HIGH_PASS:  return BiQuad::HIGH_PASS;
    case PEQ_BAND_PASS:  return BiQuad::BAND_PASS;
    case PEQ_NOTCH:      return BiQuad::NOTCH;
    case PEQ_ALL_PASS:   return BiQuad::ALL_PASS;
    default:             return BiQuad::PEAKING;
    }
}

class ParametricEqProcessor
{
public:
    ParametricEqProcessor();
    ~ParametricEqProcessor();

    // Reconfigure the entire band chain. Call from config thread only.
    // sampleRate: current DSP sample rate
    // bands: array of band configs
    // count: number of bands
    // preampDb: global preamp gain in dB
    void configure(double sampleRate, const ParametricEqBandConfig* bands, size_t count, double preampDb);

    // Enable/disable the entire PEQ
    void setEnabled(bool enabled);

    bool isEnabled() const { return enabled.load(std::memory_order_acquire); }

    // Process interleaved stereo float audio in-place.
    // Each frame is [L, R]. n = number of frames (not samples).
    // This is called AFTER the main JamesDSP chain, on the interleaved output.
    void processInterleaved(float* data, size_t numFrames);

    // Process deinterleaved stereo float audio in-place.
    // left/right arrays of length numFrames.
    void processDeinterleaved(float* left, float* right, size_t numFrames);

private:
    // Internal per-band state: separate BiQuad instances for L and R
    struct BandState
    {
        BiQuad bqL;
        BiQuad bqR;
        int channelMode;  // PEQ_CHAN_BOTH / LEFT / RIGHT
        bool enabled;
        bool isPreamp;        // true for PEQ_PREAMP bands (flat gain, no biquad)
        double preampLinear;  // linear gain = 10^(gain/20) for preamp bands
    };

    // Immutable-after-publish band chain. configure() builds a fresh chain and
    // publishes it via std::atomic_store; the audio thread takes a shared
    // reference per block (std::atomic_load), so a reconfigure during playback
    // can never free or tear the chain being processed. Biquad state inside a
    // published chain is touched only by the (single) audio thread.
    struct ChainConfig
    {
        std::vector<BandState> bands;
        double preampLinear;
    };
    std::shared_ptr<ChainConfig> publishedChain;

    std::atomic<bool> enabled;
    double sampleRate;  // config thread only

    // Temp buffers for deinterleaved processing (audio thread only)
    std::vector<float> tmpL;
    std::vector<float> tmpR;

    void processChannel(double& x1, double& x2, double& y1, double& y2,
                        const BandState& band, double sample);
};
