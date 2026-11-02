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
    : volumeDb(0.0)
    , coeffsDirty(true)
    , enabled(false)
    , attFactor(1.0)
    , neutral(true)
    , activeMode(LOUDNESS_MODE_CLASSIC)
    , activeSubsonicStages(0)
    , activeSubsonicFirstOrder(false)
    , isoPreampLinear(1.0)
    , isoActiveBandCount(0)
{
    config.sampleRate = 48000.0;
    config.referenceLevel = 0.0;
    config.referenceOffset = 0.0;
    config.attenuation = 1.0;
    config.mode = LOUDNESS_MODE_CLASSIC;
    config.lsFreq = 75.0;
    config.lsSlope = 0.52;
    config.lsRatio = 0.55;
    config.hsFreq = 10000.0;
    config.hsSlope = 0.90;
    config.hsRatio = 0.225;
    config.isoBasePhon = 80.0;
    config.isoQ = 4.318;
    config.subsonicEnabled = false;
    config.subsonicFreq = 20.0;
    config.subsonicOrder = 4;
    config.subsonicQ = 0.707;
}

LoudnessCorrectionProcessor::~LoudnessCorrectionProcessor()
{
}

void LoudnessCorrectionProcessor::configure(double sr, double refLevel,
                                            double refOffset, double att,
                                            int modeParam)
{
    std::lock_guard<std::mutex> lock(configMutex);
    config.sampleRate = sr;
    config.referenceLevel = refLevel;
    config.referenceOffset = refOffset;
    config.attenuation = (att < 0.0) ? 0.0 : (att > 2.0 ? 2.0 : att);
    config.mode = (modeParam == LOUDNESS_MODE_ISO226) ? LOUDNESS_MODE_ISO226
                                                      : LOUDNESS_MODE_CLASSIC;

    // Коэффициенты и biquad-state здесь не трогаем (BUG-06): аудио-поток
    // сам пересчитает их по dirty-флагу из целостного снапшота. До пересчёта
    // продолжают звучать старые коэффициенты — переход между блоками, без
    // обращения к полурасписанным параметрам.
    coeffsDirty.store(true, std::memory_order_release);
}

void LoudnessCorrectionProcessor::setVolume(double vol)
{
    volumeDb.store(vol, std::memory_order_release);
    coeffsDirty.store(true, std::memory_order_release);
}

void LoudnessCorrectionProcessor::setEnabled(bool en)
{
    enabled.store(en, std::memory_order_release);
}

void LoudnessCorrectionProcessor::setTuningParams(
    double pLsFreq, double pLsSlope, double pLsRatio,
    double pHsFreq, double pHsSlope, double pHsRatio,
    double pIsoBasePhon, double pIsoQ)
{
    std::lock_guard<std::mutex> lock(configMutex);
    config.lsFreq     = (pLsFreq < 1.0)     ? 1.0     : pLsFreq;
    config.lsSlope    = (pLsSlope < 0.05)   ? 0.05    : (pLsSlope > 1.0 ? 1.0 : pLsSlope);
    config.lsRatio    = (pLsRatio < 0.0)    ? 0.0     : (pLsRatio > 1.0 ? 1.0 : pLsRatio);
    config.hsFreq     = (pHsFreq < 1.0)     ? 1.0     : pHsFreq;
    config.hsSlope    = (pHsSlope < 0.05)   ? 0.05    : (pHsSlope > 1.0 ? 1.0 : pHsSlope);
    config.hsRatio    = (pHsRatio < 0.0)    ? 0.0     : (pHsRatio > 1.0 ? 1.0 : pHsRatio);
    config.isoBasePhon = (pIsoBasePhon < 20.0) ? 20.0 : (pIsoBasePhon > 90.0 ? 90.0 : pIsoBasePhon);
    config.isoQ       = (pIsoQ < 0.1)       ? 0.1    : (pIsoQ > 24.0 ? 24.0 : pIsoQ);
    coeffsDirty.store(true, std::memory_order_release);
}

void LoudnessCorrectionProcessor::setSubsonicFilter(bool enable, double freq, int order, double qFactor)
{
    std::lock_guard<std::mutex> lock(configMutex);
    config.subsonicEnabled = enable;
    config.subsonicFreq    = (freq < 5.0)     ? 5.0     : (freq > 200.0 ? 200.0 : freq);
    config.subsonicOrder   = (order < 1)      ? 1       : (order > 8 ? 8 : order);
    config.subsonicQ       = (qFactor < 0.1)  ? 0.1     : (qFactor > 2.0 ? 2.0 : qFactor);
    coeffsDirty.store(true, std::memory_order_release);
}

void LoudnessCorrectionProcessor::getLowShelfParams(const ConfigParams& cfg,
                                                     double volume, double& freq,
                                                     double& s, double& gain,
                                                     double& preAmp)
{
    freq = cfg.lsFreq;
    s = cfg.lsSlope;
    double volDiff = cfg.referenceLevel - cfg.referenceOffset - volume;
    if (volDiff > 0.0)
    {
        // Below reference: boost bass.
        gain = volDiff * cfg.lsRatio / (1.0 - cfg.lsRatio) * cfg.attenuation;
        preAmp = -gain;
    }
    else if (volDiff < 0.0)
    {
        // Above reference: gentle bass cut.
        preAmp = 0.0;
        gain = volDiff * cfg.lsRatio * std::exp(volDiff / 90.0) * cfg.attenuation;
    }
    else
    {
        gain = 0.0;
        preAmp = 0.0;
    }
}

void LoudnessCorrectionProcessor::getHighShelfParams(const ConfigParams& cfg,
                                                      double volume, double& freq,
                                                      double& s, double& gain)
{
    freq = cfg.hsFreq;
    s = cfg.hsSlope;
    double volDiff = cfg.referenceLevel - cfg.referenceOffset - volume;
    if (volDiff > 0.0)
    {
        // Below reference: boost treble (less aggressive than bass).
        gain = volDiff * cfg.hsRatio * std::exp(-volDiff / 100.0) * cfg.attenuation;
    }
    else if (volDiff < 0.0)
    {
        // Above reference: gentle treble cut.
        /* BUG-09: share one spec with the C path (peq_loudness.c) and the UI
         * curve (LoudnessCurveCalculator). With the default hsRatio = 0.225
         * this is 0.175, which is why the constant was invisible until a
         * user changed hsRatio. */
        gain = volDiff * cfg.hsRatio * 0.778 * std::exp(volDiff / 80.0) * cfg.attenuation;
    }
    else
    {
        gain = 0.0;
    }
}

void LoudnessCorrectionProcessor::recomputeCoefficients(double volume,
                                                        const ConfigParams& cfg)
{
    if (cfg.mode == LOUDNESS_MODE_ISO226)
    {
        // ---- ISO 226:2023 mode ----
        // Compute the loudness correction gains for all 29 bands.
        double isoGains[ISO226_NUM_BANDS];
        double maxGain = computeIso226Gains(cfg, volume, isoGains);

        // Check if correction is near-zero (bypass for best quality)
        double maxAbs = 0.0;
        for (int i = 0; i < ISO226_NUM_BANDS; i++)
        {
            double absGain = std::abs(isoGains[i] * cfg.attenuation);
            if (absGain > maxAbs)
                maxAbs = absGain;
        }
        neutral = maxAbs < 0.2;

        /* Build peaking biquads for each band */
        /* Q for 1/3-octave: Q = sqrt(2^(1/3)) / (2^(1/3) - 1) ≈ 4.318 */
        const double qThirdOctave = cfg.isoQ;
        double nyquist = cfg.sampleRate * 0.5;

        isoActiveBandCount = 0;
        for (int i = 0; i < ISO226_NUM_BANDS; i++)
        {
            double gain = isoGains[i] * cfg.attenuation;
            if (std::abs(gain) < 0.05)
            {
                // Skip near-zero bands
                isoBandsL[i] = BiQuad();
                isoBandsR[i] = BiQuad();
                continue;
            }

            double freq = iso226Freqs[i];
            if (freq < 1.0) freq = 1.0;
            if (freq > nyquist * 0.98) freq = nyquist * 0.98;

            // Q mode (isBandwidthOrS = false)
            isoBandsL[i] = BiQuad(BiQuad::PEAKING, gain, freq, cfg.sampleRate,
                                  qThirdOctave, false);
            isoBandsR[i] = BiQuad(BiQuad::PEAKING, gain, freq, cfg.sampleRate,
                                  qThirdOctave, false);
            isoActiveBandCount++;
        }

        // Pre-amp: offset by the ACTUAL peak of the combined cascade.
        // Adjacent peaking filters overlap and sum, so the cascade peak
        // is higher than any individual band gain. Evaluate the combined
        // response at each band center and between adjacent centers.
        double cascadePeak = maxGain; // floor: at least the max individual gain
        if (isoActiveBandCount > 0)
        {
            for (int i = 0; i < ISO226_NUM_BANDS; i++)
            {
                /* BUG-09: evaluate on a denser probe grid (center plus the
                 * geometric means with both neighbours). With only "center +
                 * mean to the right" the peak estimate could sit in a dead
                 * zone between probes and under-report the cascade peak. */
                double testFreqs[3] = {iso226Freqs[i], 0.0, 0.0};
                if (i > 0)
                    testFreqs[1] = std::sqrt(iso226Freqs[i - 1] * iso226Freqs[i]);
                if (i < ISO226_NUM_BANDS - 1)
                    testFreqs[2] = std::sqrt(iso226Freqs[i] * iso226Freqs[i + 1]);

                for (int tf = 0; tf < 3; tf++)
                {
                    double f = testFreqs[tf];
                    if (f <= 0.0) continue;
                    double w = 2.0 * M_PI * f / cfg.sampleRate;
                    double total = 0.0;
                    for (int j = 0; j < ISO226_NUM_BANDS; j++)
                    {
                        double gain = isoGains[j] * cfg.attenuation;
                        if (std::abs(gain) < 0.05) continue;
                        if (iso226Freqs[j] > nyquist * 0.98) continue;
                        double w0 = 2.0 * M_PI * iso226Freqs[j] / cfg.sampleRate;
                        double A = std::pow(10.0, gain / 40.0);
                        double alpha = std::sin(w0) / (2.0 * qThirdOctave);
                        double b0 = 1.0 + alpha * A;
                        double b1 = -2.0 * std::cos(w0);
                        double b2 = 1.0 - alpha * A;
                        double a0 = 1.0 + alpha / A;
                        double a1 = -2.0 * std::cos(w0);
                        double a2 = 1.0 - alpha / A;
                        double cosw = std::cos(w), cos2w = std::cos(2.0 * w);
                        double sinw = std::sin(w), sin2w = std::sin(2.0 * w);
                        double num_re = b0 + b1 * cosw + b2 * cos2w;
                        double num_im = b1 * sinw + b2 * sin2w;
                        double den_re = a0 + a1 * cosw + a2 * cos2w;
                        double den_im = a1 * sinw + a2 * sin2w;
                        double num_sq = num_re * num_re + num_im * num_im;
                        double den_sq = den_re * den_re + den_im * den_im;
                        if (den_sq > 1e-20 && num_sq > 0.0)
                            total += 10.0 * std::log10(num_sq / den_sq);
                    }
                    if (total > cascadePeak)
                        cascadePeak = total;
                }
            }
        }
        double preAmpDb = -cascadePeak;
        attFactor = std::exp(preAmpDb / 6.0 * std::log(2.0));

        isoPreampLinear = attFactor;
        // Fall through to subsonic filter rebuild below (don't return)
    }
    else
    {

    // ---- Classic mode (original) ----
    double freqLS, sLS, gainLS, preAmp;
    double freqHS, sHS, gainHS;

    getLowShelfParams(cfg, volume, freqLS, sLS, gainLS, preAmp);
    attFactor = std::exp(preAmp / 6.0 * std::log(2.0));

    getHighShelfParams(cfg, volume + preAmp, freqHS, sHS, gainHS);

    neutral = std::max(std::abs(gainLS), std::abs(gainHS)) < 0.2;

    // Clamp frequencies to valid range for the sample rate
    double nyquist = cfg.sampleRate * 0.5;
    if (freqLS < 1.0) freqLS = 1.0;
    if (freqLS > nyquist * 0.98) freqLS = nyquist * 0.98;
    if (freqHS < 1.0) freqHS = 1.0;
    if (freqHS > nyquist * 0.98) freqHS = nyquist * 0.98;

    // Construct fresh biquads with S-slope shelf mode (isBandwidthOrS = true).
    // This produces coefficient math identical to the original's
    // upDateBiquadCoefficients() runtime path.
    lowShelfL = BiQuad(BiQuad::LOW_SHELF, gainLS, freqLS, cfg.sampleRate, sLS, true);
    lowShelfR = BiQuad(BiQuad::LOW_SHELF, gainLS, freqLS, cfg.sampleRate, sLS, true);
    highShelfL = BiQuad(BiQuad::HIGH_SHELF, gainHS, freqHS, cfg.sampleRate, sHS, true);
    highShelfR = BiQuad(BiQuad::HIGH_SHELF, gainHS, freqHS, cfg.sampleRate, sHS, true);
    }

    // ---- Subsonic (infrasonic) high-pass filter ----
    // Rebuild regardless of mode; applied before loudness correction.
    // Spec: floor(order/2) 2nd-order biquads + one first-order stage for
    // odd orders (each order = 6 dB/oct), identical in C and Kotlin.
    if (cfg.subsonicEnabled)
    {
        double nyquist = cfg.sampleRate * 0.5;
        double f = cfg.subsonicFreq;
        if (f < 1.0) f = 1.0;
        if (f > nyquist * 0.98) f = nyquist * 0.98;

        int numStages = cfg.subsonicOrder / 2; // floor(order/2)
        for (int i = 0; i < numStages && i < MAX_SUBSONIC_STAGES; i++)
        {
            subsonicL[i] = BiQuad(BiQuad::HIGH_PASS, 0.0, f, cfg.sampleRate,
                                  cfg.subsonicQ, false);
            subsonicR[i] = BiQuad(BiQuad::HIGH_PASS, 0.0, f, cfg.sampleRate,
                                  cfg.subsonicQ, false);
        }
        // Clear unused stages
        for (int i = numStages; i < MAX_SUBSONIC_STAGES; i++)
        {
            subsonicL[i] = BiQuad();
            subsonicR[i] = BiQuad();
        }

        bool firstOrder = (cfg.subsonicOrder % 2) != 0;
        subsonicFirstL.configure(f, cfg.sampleRate, firstOrder);
        subsonicFirstR.configure(f, cfg.sampleRate, firstOrder);
    }
    else
    {
        subsonicFirstL.configure(20.0, cfg.sampleRate, false);
        subsonicFirstR.configure(20.0, cfg.sampleRate, false);
    }

    // Publish mirror state for the per-sample hot path (audio-thread only)
    activeMode = cfg.mode;
    activeSubsonicStages = cfg.subsonicEnabled ? cfg.subsonicOrder / 2 : 0;
    activeSubsonicFirstOrder = cfg.subsonicEnabled && (cfg.subsonicOrder % 2) != 0;
}

void LoudnessCorrectionProcessor::processInterleaved(float* data, size_t numFrames)
{
    if (!enabled)
        return;

    // Lazily recompute coefficients if volume or parameters changed
    if (coeffsDirty.load(std::memory_order_acquire))
    {
        ConfigParams cfg;
        {
            std::lock_guard<std::mutex> lock(configMutex);
            cfg = config;
        }
        double vol = volumeDb.load(std::memory_order_acquire);
        recomputeCoefficients(vol, cfg);
        coeffsDirty.store(false, std::memory_order_release);
    }

    // The subsonic filter runs even when the loudness correction is neutral,
    // so bypass only when there is nothing to apply at all.
    int numSubsonicStages = activeSubsonicStages;
    bool subsonicFirstOrderActive = activeSubsonicFirstOrder;

    if (neutral && numSubsonicStages == 0 && !subsonicFirstOrderActive)
        return; // near-zero correction and no subsonic filter: bypass

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
        ConfigParams cfg;
        {
            std::lock_guard<std::mutex> lock(configMutex);
            cfg = config;
        }
        double vol = volumeDb.load(std::memory_order_acquire);
        recomputeCoefficients(vol, cfg);
        coeffsDirty.store(false, std::memory_order_release);
    }

    // Subsonic filter runs independently of loudness neutral/bypass
    int numSubsonicStages = activeSubsonicStages;
    bool subsonicFirstOrderActive = activeSubsonicFirstOrder;

    if (neutral && numSubsonicStages == 0 && !subsonicFirstOrderActive)
        return;

    // If only subsonic is active (neutral loudness), apply just the HP cascade
    if (neutral)
    {
        for (size_t i = 0; i < numFrames; ++i)
        {
            double sL = left[i];
            double sR = right[i];
            if (subsonicFirstOrderActive)
            {
                sL = subsonicFirstL.process(sL);
                sR = subsonicFirstR.process(sR);
            }
            for (int s = 0; s < numSubsonicStages && s < MAX_SUBSONIC_STAGES; s++)
            {
                sL = subsonicL[s].process(sL);
                sR = subsonicR[s].process(sR);
            }
            left[i]  = (float)sL;
            right[i] = (float)sR;
        }
        subsonicFirstL.removeDenormals();
        subsonicFirstR.removeDenormals();
        for (int s = 0; s < numSubsonicStages && s < MAX_SUBSONIC_STAGES; s++)
        {
            subsonicL[s].removeDenormals();
            subsonicR[s].removeDenormals();
        }
        return;
    }

    if (activeMode == LOUDNESS_MODE_ISO226)
    {
        // ISO 226 mode: 29-band peaking EQ cascade with pre-amp
        if (isoActiveBandCount == 0)
            return;

        double preamp = isoPreampLinear;
        for (size_t i = 0; i < numFrames; ++i)
        {
            double sampleL = left[i];
            double sampleR = right[i];

            // Subsonic HP filter before loudness correction
            if (subsonicFirstOrderActive)
            {
                sampleL = subsonicFirstL.process(sampleL);
                sampleR = subsonicFirstR.process(sampleR);
            }
            for (int s = 0; s < numSubsonicStages && s < MAX_SUBSONIC_STAGES; s++)
            {
                sampleL = subsonicL[s].process(sampleL);
                sampleR = subsonicR[s].process(sampleR);
            }

            sampleL *= preamp;
            sampleR *= preamp;

            for (int b = 0; b < ISO226_NUM_BANDS; b++)
            {
                sampleL = isoBandsL[b].process(sampleL);
                sampleR = isoBandsR[b].process(sampleR);
            }

            left[i]  = (float)sampleL;
            right[i] = (float)sampleR;
        }

        // Remove denormals periodically
        subsonicFirstL.removeDenormals();
        subsonicFirstR.removeDenormals();
        for (int b = 0; b < ISO226_NUM_BANDS; b++)
        {
            isoBandsL[b].removeDenormals();
            isoBandsR[b].removeDenormals();
        }
        for (int s = 0; s < numSubsonicStages && s < MAX_SUBSONIC_STAGES; s++)
        {
            subsonicL[s].removeDenormals();
            subsonicR[s].removeDenormals();
        }
        return;
    }

    // Classic mode
    for (size_t i = 0; i < numFrames; ++i)
    {
        double sampleL = left[i];
        double sampleR = right[i];

        // Subsonic HP filter before loudness correction
        if (subsonicFirstOrderActive)
        {
            sampleL = subsonicFirstL.process(sampleL);
            sampleR = subsonicFirstR.process(sampleR);
        }
        for (int s = 0; s < numSubsonicStages && s < MAX_SUBSONIC_STAGES; s++)
        {
            sampleL = subsonicL[s].process(sampleL);
            sampleR = subsonicR[s].process(sampleR);
        }

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
    subsonicFirstL.removeDenormals();
    subsonicFirstR.removeDenormals();
    lowShelfL.removeDenormals();
    lowShelfR.removeDenormals();
    highShelfL.removeDenormals();
    highShelfR.removeDenormals();
    for (int s = 0; s < numSubsonicStages && s < MAX_SUBSONIC_STAGES; s++)
    {
        subsonicL[s].removeDenormals();
        subsonicR[s].removeDenormals();
    }
}

/* ================================================================ */
/* ISO 226:2023 equal-loudness-level contour data and computation   */
/* ================================================================ */

// ISO 226:2023 Table 1: 29 preferred one-third-octave frequencies (Hz)
const double LoudnessCorrectionProcessor::iso226Freqs[ISO226_NUM_BANDS] = {
    20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0,
    200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0,
    2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0
};

// ISO 226:2023 Table 1: exponent α_f for loudness perception
const double LoudnessCorrectionProcessor::iso226Af[ISO226_NUM_BANDS] = {
    0.635, 0.602, 0.569, 0.537, 0.509, 0.482, 0.456, 0.433, 0.412, 0.391,
    0.373, 0.357, 0.343, 0.330, 0.320, 0.311, 0.303, 0.300, 0.295, 0.292,
    0.290, 0.290, 0.289, 0.289, 0.289, 0.293, 0.303, 0.323, 0.354
};

// ISO 226:2023 Table 1: magnitude of linear transfer function L_U (dB)
const double LoudnessCorrectionProcessor::iso226Lu[ISO226_NUM_BANDS] = {
    -31.5, -27.2, -23.1, -19.3, -16.1, -13.1, -10.4, -8.2, -6.3, -4.6,
    -3.2, -2.1, -1.2, -0.5, 0.0, 0.4, 0.5, 0.0, -2.7, -4.2,
    -1.2, 1.4, 2.3, 1.0, -2.3, -7.2, -11.2, -10.9, -3.5
};

// ISO 226:2023 Table 1: threshold of hearing T_f (dB)
const double LoudnessCorrectionProcessor::iso226Tf[ISO226_NUM_BANDS] = {
    78.1, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9,
    14.4, 11.4, 8.6, 6.2, 4.4, 3.0, 2.2, 2.4, 3.5, 1.7,
    -1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6, 13.9, 12.3
};

// ISO 226:2023 Formula (1): SPL (dB) from loudness level L_N (phons)
static double iso226_spl_from_phon(double af, double lu, double tf, double lN)
{
    // L_f = (10/α_f) * log10[ (4e-10)^(0.3-α_f) * (10^(0.03*L_N) - 10^0.072)
    //                          + 10^(α_f*(T_f+L_U)/10) ] - L_U
    double term1 = std::pow(4e-10, 0.3 - af) *
                   (std::pow(10.0, 0.03 * lN) - std::pow(10.0, 0.072));
    double term2 = std::pow(10.0, af * (tf + lu) / 10.0);
    double inner = term1 + term2;
    if (inner <= 0.0)
        return 0.0; // below threshold — no correction needed
    return (10.0 / af) * std::log10(inner) - lu;
}

double LoudnessCorrectionProcessor::computeIso226Gains(
    const ConfigParams& cfg, double volume, double gains[ISO226_NUM_BANDS])
{
    // The ISO 226 formula requires absolute phon values (valid range 20-90).
    // The UI provides relative dB values. We use a fixed base phon level
    // (80 phon = typical loud listening level) and derive the actual phon
    // levels from the volume difference, mirroring the classic mode's volDiff:
    //   volDiff = referenceLevel - referenceOffset - volume
    //   refPhon = BASE_PHON (80)
    //   curPhon = BASE_PHON - volDiff
    //
    // When volDiff = 0 (at reference level), refPhon = curPhon → no correction.
    // When volDiff > 0 (below reference), curPhon < refPhon → boost bass/treble.

    const double basePhon = cfg.isoBasePhon;
    double volDiff = cfg.referenceLevel - cfg.referenceOffset - volume;
    double refPhon = basePhon;
    double curPhon = basePhon - volDiff;

    // Clamp phon values to valid ISO 226 range [20, 90]
    if (refPhon < 20.0) refPhon = 20.0;
    if (refPhon > 90.0) refPhon = 90.0;
    if (curPhon < 20.0) curPhon = 20.0;
    if (curPhon > 90.0) curPhon = 90.0;

    double maxGain = 0.0;

    for (int i = 0; i < ISO226_NUM_BANDS; i++)
    {
        double refSpl = iso226_spl_from_phon(iso226Af[i], iso226Lu[i],
                                             iso226Tf[i], refPhon);
        double curSpl = iso226_spl_from_phon(iso226Af[i], iso226Lu[i],
                                             iso226Tf[i], curPhon);

        double gain = curSpl - refSpl + (refPhon - curPhon);

        gains[i] = gain;
        if (gain > maxGain)
            maxGain = gain;
    }

    return maxGain;
}
