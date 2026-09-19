/*
 * LoudnessCorrectionProcessor implementation.
 * See LoudnessCorrectionProcessor.h for design overview and provenance.
 */

#include "LoudnessCorrectionProcessor.h"
#include <cmath>
#include <algorithm>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif
#ifndef M_LN2
#define M_LN2 0.69314718055994530942
#endif

LoudnessCorrectionProcessor::LoudnessCorrectionProcessor()
    : sampleRate(48000.0)
    , referenceLevel(0.0)
    , referenceOffset(0.0)
    , attenuation(1.0)
    , volumeDb(0.0)
    , lastComputedVolume(0.0)
    , coeffsDirty(true)
    , enabled(false)
    , attFactor(1.0)
    , neutral(true)
{
}

LoudnessCorrectionProcessor::~LoudnessCorrectionProcessor()
{
}

void LoudnessCorrectionProcessor::configure(double sr, double refLevel,
                                            double refOffset, double att)
{
    sampleRate = sr;
    referenceLevel = refLevel;
    referenceOffset = refOffset;
    attenuation = (att < 0.0) ? 0.0 : (att > 2.0 ? 2.0 : att);

    // Reset biquad state (clears history)
    lowShelfL = BiQuad();
    lowShelfR = BiQuad();
    highShelfL = BiQuad();
    highShelfR = BiQuad();

    attFactor = 1.0;
    neutral = true;

    // Force coefficient recomputation on next process() call
    lastComputedVolume = 1e30; // impossible value → always "changed"
    coeffsDirty.store(true, std::memory_order_release);
}

void LoudnessCorrectionProcessor::setVolume(double vol)
{
    volumeDb.store(vol, std::memory_order_release);
    coeffsDirty.store(true, std::memory_order_release);
}

void LoudnessCorrectionProcessor::setEnabled(bool en)
{
    enabled = en;
}

void LoudnessCorrectionProcessor::getLowShelfParams(double volume, double& freq,
                                                     double& s, double& gain,
                                                     double& preAmp)
{
    freq = 75.0;
    s = 0.52;
    double volDiff = referenceLevel - referenceOffset - volume;
    if (volDiff > 0.0)
    {
        // Below reference: boost bass.
        // Original: gain = volDiff * 0.55 / (1 - 0.55) * attenuation
        gain = volDiff * 0.55 / (1.0 - 0.55) * attenuation;
        preAmp = -gain;
    }
    else if (volDiff < 0.0)
    {
        // Above reference: gentle bass cut.
        preAmp = 0.0;
        gain = volDiff * 0.55 * std::exp(volDiff / 90.0) * attenuation;
    }
    else
    {
        gain = 0.0;
        preAmp = 0.0;
    }
}

void LoudnessCorrectionProcessor::getHighShelfParams(double volume, double& freq,
                                                      double& s, double& gain)
{
    freq = 10000.0;
    s = 0.9;
    double volDiff = referenceLevel - referenceOffset - volume;
    if (volDiff > 0.0)
    {
        // Below reference: boost treble (less aggressive than bass).
        gain = volDiff * 0.225 * std::exp(-volDiff / 100.0) * attenuation;
    }
    else if (volDiff < 0.0)
    {
        // Above reference: gentle treble cut.
        gain = volDiff * 0.175 * std::exp(volDiff / 80.0) * attenuation;
    }
    else
    {
        gain = 0.0;
    }
}

void LoudnessCorrectionProcessor::recomputeCoefficients(double volume)
{
    double freqLS, sLS, gainLS, preAmp;
    double freqHS, sHS, gainHS;

    getLowShelfParams(volume, freqLS, sLS, gainLS, preAmp);
    attFactor = std::exp(preAmp / 6.0 * std::log(2.0));

    getHighShelfParams(volume + preAmp, freqHS, sHS, gainHS);

    neutral = std::max(std::abs(gainLS), std::abs(gainHS)) < 0.2;

    // Clamp frequencies to valid range for the sample rate
    double nyquist = sampleRate * 0.5;
    if (freqLS < 1.0) freqLS = 1.0;
    if (freqLS > nyquist * 0.98) freqLS = nyquist * 0.98;
    if (freqHS < 1.0) freqHS = 1.0;
    if (freqHS > nyquist * 0.98) freqHS = nyquist * 0.98;

    // Construct fresh biquads with S-slope shelf mode (isBandwidthOrS = true).
    // This produces coefficient math identical to the original's
    // upDateBiquadCoefficients() runtime path.
    lowShelfL = BiQuad(BiQuad::LOW_SHELF, gainLS, freqLS, sampleRate, sLS, true);
    lowShelfR = BiQuad(BiQuad::LOW_SHELF, gainLS, freqLS, sampleRate, sLS, true);
    highShelfL = BiQuad(BiQuad::HIGH_SHELF, gainHS, freqHS, sampleRate, sHS, true);
    highShelfR = BiQuad(BiQuad::HIGH_SHELF, gainHS, freqHS, sampleRate, sHS, true);
}

void LoudnessCorrectionProcessor::processInterleaved(float* data, size_t numFrames)
{
    if (!enabled)
        return;

    // Lazily recompute coefficients if volume or parameters changed
    if (coeffsDirty.load(std::memory_order_acquire))
    {
        double vol = volumeDb.load(std::memory_order_acquire);
        recomputeCoefficients(vol);
        lastComputedVolume = vol;
        coeffsDirty.store(false, std::memory_order_release);
    }

    if (neutral)
        return; // near-zero correction: bypass for best quality

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

void LoudnessCorrectionProcessor::processDeinterleaved(float* left, float* right,
                                                        size_t numFrames)
{
    if (!enabled)
        return;

    // The interleaved path already recomputes coefficients; if called
    // directly, check dirty flag here too.
    if (coeffsDirty.load(std::memory_order_acquire))
    {
        double vol = volumeDb.load(std::memory_order_acquire);
        recomputeCoefficients(vol);
        lastComputedVolume = vol;
        coeffsDirty.store(false, std::memory_order_release);
    }

    if (neutral)
        return;

    for (size_t i = 0; i < numFrames; ++i)
    {
        double sampleL = left[i];
        double sampleR = right[i];

        // Low shelf → pre-amp attenuation → high shelf
        sampleL = lowShelfL.process(sampleL);
        sampleR = lowShelfR.process(sampleR);

        sampleL *= attFactor;
        sampleR *= attFactor;

        sampleL = highShelfL.process(sampleL);
        sampleR = highShelfR.process(sampleR);

        left[i]  = (float)sampleL;
        right[i] = (float)sampleR;
    }

    // Remove denormals periodically to prevent CPU spikes
    lowShelfL.removeDenormals();
    lowShelfR.removeDenormals();
    highShelfL.removeDenormals();
    highShelfR.removeDenormals();
}
