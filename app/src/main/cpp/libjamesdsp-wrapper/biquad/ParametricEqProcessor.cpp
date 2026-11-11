/*
 * ParametricEqProcessor implementation.
 * See ParametricEqProcessor.h for design overview.
 */

#include "ParametricEqProcessor.h"
#include <cmath>
#include <algorithm>
#include <cstring>

// Hartley FHT from the JamesDSP core (linked into jamesdsp-wrapper via the
// jamesdsp library; the same routines the GEQ uses in ArbFIRGen.c).
extern "C" {
void LLbitReversalTbl(unsigned *dst, unsigned int n);
void fhtsinHalfTblFloat(float *dst, unsigned int n);
void LLdiscreteHartleyFloat(float *A, const int nPoints, const float *sinTab);
}

ParametricEqProcessor::ParametricEqProcessor()
    : enabled(false)
    , linearPhase(false)
    , sampleRate(48000.0)
{
}

ParametricEqProcessor::~ParametricEqProcessor()
{
}

void ParametricEqProcessor::setLinearPhase(bool en)
{
    linearPhase.store(en, std::memory_order_release);
}

// Magnitude of one normalized biquad at digital frequency w
static double peqBiquadMagAt(const BiQuad& bq, double w)
{
    double coeffs[4];
    double b0;
    bq.getCoefficients(coeffs, b0);
    const double b1 = coeffs[0], b2 = coeffs[1];
    const double a1 = coeffs[2], a2 = coeffs[3];

    const double c1 = std::cos(w), s1 = std::sin(w);
    const double c2 = std::cos(2.0 * w), s2 = std::sin(2.0 * w);
    const double nre = b0 + b1 * c1 + b2 * c2;
    const double nim = -(b1 * s1 + b2 * s2);
    const double dre = 1.0 + a1 * c1 + a2 * c2;
    const double dim = -(a1 * s1 + a2 * s2);
    const double num = nre * nre + nim * nim;
    const double den = dre * dre + dim * dim;
    return (den > 1e-30) ? std::sqrt(num / den) : 0.0;
}

// Samples the cascade magnitude on the 16384-point grid (bins 0..8192),
// inverse-transforms it into a zero-phase impulse and mirrors it around
// tap 8191 — the same recipe as the GEQ ArbitraryEqLinearPhase, with the
// biquad cascade evaluated analytically. Per-band channel routing is
// preserved by building a separate kernel per ear.
void ParametricEqProcessor::peqFirBuild(ChainConfig& chain)
{
    chain.irL.assign(PEQ_FIR_TAPS, 0.0f);
    chain.irR.assign(PEQ_FIR_TAPS, 0.0f);

    std::vector<unsigned int> bitRev(PEQ_FFT_SIZE);
    std::vector<float> sineTab(PEQ_FFT_SIZE);
    std::vector<float> specL(PEQ_FFT_SIZE, 0.0f), specR(PEQ_FFT_SIZE, 0.0f);
    LLbitReversalTbl(bitRev.data(), PEQ_FFT_SIZE);
    fhtsinHalfTblFloat(sineTab.data(), PEQ_FFT_SIZE);

    const int K = PEQ_FFT_SIZE / 2;
    for (int k = 0; k <= K; k++)
    {
        const double w = 2.0 * M_PI * (double)k / (double)PEQ_FFT_SIZE;
        double magL = chain.preampLinear;
        double magR = chain.preampLinear;

        for (const auto& band : chain.bands)
        {
            if (!band.enabled)
                continue;
            const double g = band.isPreamp ? band.preampLinear
                                           : peqBiquadMagAt(band.bqL, w);
            if (band.channelMode != PEQ_CHAN_RIGHT)
                magL *= g;
            if (band.channelMode != PEQ_CHAN_LEFT)
                magR *= g;
        }

        // LLdiscreteHartleyFloat consumes bit-reversed input
        specL[bitRev[k]] = (float)magL;
        specR[bitRev[k]] = (float)magR;
        if (k > 0 && k < K)
        {
            const unsigned int mi = bitRev[PEQ_FFT_SIZE - k];
            specL[mi] = (float)magL;
            specR[mi] = (float)magR;
        }
    }

    LLdiscreteHartleyFloat(specL.data(), PEQ_FFT_SIZE, sineTab.data());
    LLdiscreteHartleyFloat(specR.data(), PEQ_FFT_SIZE, sineTab.data());

    for (int i = 0; i < K; i++)
    {
        const float l = specL[i] * (1.0f / (float)PEQ_FFT_SIZE);
        const float r = specR[i] * (1.0f / (float)PEQ_FFT_SIZE);
        chain.irL[PEQ_FIR_CENTER - i] = chain.irL[PEQ_FIR_CENTER + i] = l;
        chain.irR[PEQ_FIR_CENTER - i] = chain.irR[PEQ_FIR_CENTER + i] = r;
    }

    chain.linearPhase = true;
    FFTConvolver2x2Init(&chain.conv);
    // 1024-sample partitions: the wrapper sees variable block sizes, and the
    // convolver accumulates internally, so any partition works. 16383 taps /
    // 1024 = 16 segments per channel — cheap and low-latency.
    FFTConvolver2x2LoadImpulseResponse(&chain.conv, 1024,
        chain.irL.data(), chain.irR.data(), PEQ_FIR_TAPS);
}

void ParametricEqProcessor::configure(double sr, const ParametricEqBandConfig* bands, size_t count, double preampDb)
{
    sampleRate = sr;

    // Build the new chain out-of-line, then publish it atomically. The audio
    // thread keeps a shared reference to the previous chain until its current
    // block finishes, so reconfiguring during playback is safe.
    auto next = std::make_shared<ChainConfig>();
    next->preampLinear = std::pow(10.0, preampDb / 20.0);
    next->linearPhase = linearPhase.load(std::memory_order_acquire);
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

    // Linear phase: sample the finished biquad cascade's magnitude and swap
    // the whole chain over to the FFT convolver. The biquad list stays in the
    // chain (it defines the response and is cheap to keep).
    if (next->linearPhase)
        peqFirBuild(*next);

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

    // processDeinterleaved() takes the single shared-chain snapshot used by
    // this block; avoid a second atomic load and shared_ptr refcount update.

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

    // Linear-phase FIR path: partitioned FFT convolution with the symmetric
    // kernels built at configure time. Adds PEQ_FIR_CENTER samples of
    // constant latency by design (same as GEQ linear phase).
    if (chain->linearPhase)
    {
        FFTConvolver2x2Process(&chain->conv, left, right, left, right,
                               (unsigned int)numFrames);
        return;
    }

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

    // Denormal cleanup is block-level work. Doing it inside the sample loop
    // multiplied eight absolute-value checks by frames x bands and dominated
    // PEQ cost on power-saving cores.
    for (auto& band : bandStates)
    {
        if (!band.enabled || band.isPreamp)
            continue;
        band.bqL.removeDenormals();
        band.bqR.removeDenormals();
    }
}
