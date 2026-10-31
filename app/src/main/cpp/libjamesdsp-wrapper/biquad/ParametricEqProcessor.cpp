/*
 * ParametricEqProcessor implementation.
 * See ParametricEqProcessor.h for design overview.
 */

#include "ParametricEqProcessor.h"
#include <cmath>
#include <algorithm>
#include <cstring>

ParametricEqProcessor::ParametricEqProcessor()
    : enabled(false)
    , sampleRate(48000.0)
{
}

ParametricEqProcessor::~ParametricEqProcessor()
{
}

void ParametricEqProcessor::configure(double sr, const ParametricEqBandConfig* bands, size_t count, double preampDb)
{
    sampleRate = sr;

    // Build the new chain out-of-line, then publish it atomically. The audio
    // thread keeps a shared reference to the previous chain until its current
    // block finishes, so reconfiguring during playback is safe.
    auto next = std::make_shared<ChainConfig>();
    next->preampLinear = std::pow(10.0, preampDb / 20.0);
    next->bands.reserve(count);

    for (size_t i = 0; i < count; ++i)
    {
        const auto& cfg = bands[i];
        if (!cfg.enabled)
            continue;

        BandState state;
        state.channelMode = cfg.channelMode;
        state.enabled = true;
        state.isPreamp = false;
        state.preampLinear = 1.0;

        // Preamp bands are flat frequency-independent gain stages (ported
        // from EqualizerAPO's PreampFilter). They apply 10^(gain/20) to the
        // sample directly, without any biquad coloring.
        if (cfg.filterType == PEQ_PREAMP)
        {
            state.isPreamp = true;
            state.preampLinear = std::pow(10.0, cfg.gain / 20.0);
            next->bands.push_back(std::move(state));
            continue;
        }

        // Clamp frequency to valid range for the sample rate
        double freq = cfg.frequency;
        double nyquist = sampleRate * 0.5;
        if (freq < 1.0) freq = 1.0;
        if (freq > nyquist * 0.98) freq = nyquist * 0.98;

        // Clamp Q
        double q = cfg.q;
        if (q < 0.1) q = 0.1;
        if (q > 24.0) q = 24.0;

        BiQuad::Type bqt = toBiQuadType(cfg.filterType);
        const bool isCutoff = cfg.filterType == PEQ_LOW_PASS || cfg.filterType == PEQ_HIGH_PASS;

        // Cutoffs expand into a Butterworth cascade: one biquad per 12 dB/octave
        // of slope, because a single biquad rolls off at 12 dB/octave, which
        // across the audio band reads as a tilt rather than a corner. The
        // section Qs are the Butterworth values for the resulting order; the
        // band's own Q rides on the last section only, so it still adds
        // resonance at the corner (for a single section the formula returns
        // the user's Q unchanged). The gain field carries the slope for
        // cutoffs; bands saved before it existed carry 0 there and come back
        // at the default. Mirrors BiquadUtils.cascadeFor exactly - if one
        // changes the other must, or the drawn curve stops being the applied
        // one. (Port of RootlessViPER4Android 5ea9281, GPL-3.0.)
        int sections = 1;
        if (isCutoff)
        {
            double slope = cfg.gain < 6.0 ? 48.0 : cfg.gain;
            int stages = (int)(slope / 12.0 + 0.5);
            if (stages < 1) stages = 1;
            if (stages > 8) stages = 8;
            sections = stages;
        }
        constexpr double kPi = 3.14159265358979323846;
        for (int k = 0; k < sections; k++)
        {
            double sectionQ = q;
            if (isCutoff)
            {
                sectionQ = 1.0 / (2.0 * std::cos((2.0 * k + 1.0) * kPi / (4.0 * sections)));
                if (k == sections - 1)
                    sectionQ *= q / 0.70710678;
            }

            BandState state;
            state.channelMode = cfg.channelMode;
            state.enabled = true;
            state.isPreamp = false;
            state.preampLinear = 1.0;

            // Create biquads with Q mode (isBandwidthOrS = false); cutoffs run
            // at 0 dB because their slope lives in the number of sections.
            state.bqL = BiQuad(bqt, isCutoff ? 0.0 : cfg.gain, freq, sampleRate, sectionQ, false);
            state.bqR = BiQuad(bqt, isCutoff ? 0.0 : cfg.gain, freq, sampleRate, sectionQ, false);

            next->bands.push_back(std::move(state));
        }
    }

    std::atomic_store(&publishedChain, std::shared_ptr<ChainConfig>(std::move(next)));
}

void ParametricEqProcessor::setEnabled(bool en)
{
    enabled.store(en, std::memory_order_release);
}

void ParametricEqProcessor::processInterleaved(float* data, size_t numFrames)
{
    if (!enabled.load(std::memory_order_acquire))
        return;

    {
        std::shared_ptr<ChainConfig> chain = std::atomic_load(&publishedChain);
        if (!chain || chain->bands.empty())
            return;
    }

    // Ensure temp buffers are large enough
    if (tmpL.size() < numFrames)
    {
        tmpL.resize(numFrames);
        tmpR.resize(numFrames);
    }

    // Deinterleave
    for (size_t i = 0; i < numFrames; ++i)
    {
        tmpL[i] = data[i * 2];
        tmpR[i] = data[i * 2 + 1];
    }

    processDeinterleaved(tmpL.data(), tmpR.data(), numFrames);

    // Re-interleave
    for (size_t i = 0; i < numFrames; ++i)
    {
        data[i * 2]     = tmpL[i];
        data[i * 2 + 1] = tmpR[i];
    }
}

void ParametricEqProcessor::processDeinterleaved(float* left, float* right, size_t numFrames)
{
    if (!enabled.load(std::memory_order_acquire))
        return;

    // Shared reference to the published chain: a concurrent configure() may
    // swap publishedChain, but the chain held here stays alive (with its
    // biquad state) until this block ends
    std::shared_ptr<ChainConfig> chain = std::atomic_load(&publishedChain);
    if (!chain || chain->bands.empty())
        return;

    std::vector<BandState>& bandStates = chain->bands;

    // Apply preamp
    if (chain->preampLinear != 1.0)
    {
        const double preampLinear = chain->preampLinear;
        for (size_t i = 0; i < numFrames; ++i)
        {
            left[i]  = (float)(left[i]  * preampLinear);
            right[i] = (float)(right[i] * preampLinear);
        }
    }

    // Process through biquad cascade
    for (size_t i = 0; i < numFrames; ++i)
    {
        double sampleL = left[i];
        double sampleR = right[i];

        for (auto& band : bandStates)
        {
            if (!band.enabled)
                continue;

            // Preamp band: flat gain stage, no biquad processing.
            if (band.isPreamp)
            {
                const double g = band.preampLinear;
                switch (band.channelMode)
                {
                case PEQ_CHAN_BOTH:
                    sampleL *= g;
                    sampleR *= g;
                    break;
                case PEQ_CHAN_LEFT:
                    sampleL *= g;
                    break;
                case PEQ_CHAN_RIGHT:
                    sampleR *= g;
                    break;
                }
                continue;
            }

            // Remove denormals periodically to prevent CPU spikes
            band.bqL.removeDenormals();
            band.bqR.removeDenormals();

            switch (band.channelMode)
            {
            case PEQ_CHAN_BOTH:
                sampleL = band.bqL.process(sampleL);
                sampleR = band.bqR.process(sampleR);
                break;
            case PEQ_CHAN_LEFT:
                sampleL = band.bqL.process(sampleL);
                // Right channel passes through unfiltered
                break;
            case PEQ_CHAN_RIGHT:
                sampleR = band.bqR.process(sampleR);
                // Left channel passes through unfiltered
                break;
            }
        }

        left[i]  = (float)sampleL;
        right[i] = (float)sampleR;
    }
}
