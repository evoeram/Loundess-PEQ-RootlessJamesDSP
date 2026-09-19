/*
 * Ported from EqualizerAPO's filters/BiQuadFilter.h.
 * Original: Copyright (C) 2014 Jonas Thedering, GPLv2.
 *
 * Changes for the RootlessJamesDSP / Android NDK port:
 *  - Removed IFilter base class and std::wstring channel names (now std::string).
 *  - Removed MSVC AVRT vtable pragmas and __declspec intrinsics.
 *  - Added an ARM64 NEON (float64x2_t, 2-wide) processing path alongside the
 *    existing x86 AVX512/AVX2/SSE2 paths. ARM32 and other targets use the
 *    scalar fallback.
 */

#pragma once

#include "BiQuad.h"
#include <vector>
#include <string>
#include <cstddef>

#if defined(__ARM_NEON) && defined(__aarch64__) && !defined(_M_ARM64)
#include <arm_neon.h>
#define BIQUAD_HAS_NEON_F64 1
#endif

#if (defined(__AVX512F__) || defined(__AVX2__) || defined(__SSE2__)) && !defined(__ARM_NEON)
#include <immintrin.h>
#endif

class BiQuadFilter
{
public:
    BiQuadFilter(BiQuad::Type type, double dbGain, double freq, double bandwidthOrQOrS, bool isBandwidthOrS, bool isCornerFreq);
    ~BiQuadFilter() = default;

    bool getInPlace() const { return true; }

    // Returns the (possibly reordered) channel names. Mirrors the original
    // initialize() signature, but using std::string instead of std::wstring.
    std::vector<std::string> initialize(float sampleRate, unsigned maxFrameCount, std::vector<std::string> channelNames);

    void process(double** output, double** input, unsigned frameCount);

    BiQuad::Type getType() const;
    double getDbGain() const;
    double getFreq() const;
    double getBandwidthOrQOrS() const;
    bool getIsBandwidthOrS() const;
    bool getIsCornerFreq() const;

private:
    BiQuad::Type type;
    double dbGain;
    double freq;
    double bandwidthOrQOrS;
    bool isBandwidthOrS;
    bool isCornerFreq;

    size_t channelCount;

    // Coefficient and state vectors (Structure of Arrays for SIMD)
    std::vector<double> a0, a1, a2, b1, b2; // Coefficients
    std::vector<double> x1, x2, y1, y2;     // State variables

#if defined(__AVX512F__) && !defined(__ARM_NEON)
    void process_avx512(double** output, double** input, unsigned frameCount, unsigned startChannel, unsigned numChannels);
#endif
#if (defined(__AVX2__) || defined(__AVX512F__)) && !defined(__ARM_NEON)
    void process_avx256(double** output, double** input, unsigned frameCount, unsigned startChannel, unsigned numChannels);
#endif
#if defined(__SSE2__) && !defined(__ARM_NEON)
    void process_sse128(double** output, double** input, unsigned frameCount, unsigned startChannel, unsigned numChannels);
#endif
#if defined(BIQUAD_HAS_NEON_F64)
    void process_neon2(double** output, double** input, unsigned frameCount, unsigned startChannel, unsigned numChannels);
#endif
    void process_scalar(double** output, double** input, unsigned frameCount, unsigned startChannel);
};
