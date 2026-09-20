/*
 * ParametricEqProcessor implementation.
 * See ParametricEqProcessor.h for design overview.
 */

#include "ParametricEqProcessor.h"
#include <cmath>
#include <algorithm>
#include <cstring>

ParametricEqProcessor::ParametricEqProcessor()
    : sampleRate(48000.0)
    , preampLinear(1.0)
    , enabled(false)
{
}

ParametricEqProcessor::~ParametricEqProcessor()
{
}

void ParametricEqProcessor::configure(double sr, const ParametricEqBandConfig* bands, size_t count, double preampDb)
{
    sampleRate = sr;
    preampLinear = std::pow(10.0, preampDb / 20.0);

    bandStates.clear();
    bandStates.reserve(count);

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
            bandStates.push_back(std::move(state));
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

        // Create biquads with Q mode (isBandwidthOrS = false)
        state.bqL = BiQuad(bqt, cfg.gain, freq, sampleRate, q, false);
        state.bqR = BiQuad(bqt, cfg.gain, freq, sampleRate, q, false);

        bandStates.push_back(std::move(state));
    }
}

void ParametricEqProcessor::setEnabled(bool en)
{
    enabled = en;
}

void ParametricEqProcessor::processInterleaved(float* data, size_t numFrames)
{
    if (!enabled || bandStates.empty())
        return;

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
    if (!enabled || bandStates.empty())
        return;

    // Apply preamp
    if (preampLinear != 1.0)
    {
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
