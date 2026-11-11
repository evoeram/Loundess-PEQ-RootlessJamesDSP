/*
 * BalanceProcessor — L/R balance for the RootlessJamesDSP local engine.
 *
 * Mirrors the root engine's balance.c semantics: a single slider
 * p in [-1, +1]; the opposite channel is attenuated on a dB-linear
 * curve (BALANCE_MAX_ATTENUATION_DB * |p| at full deflection), the
 * near channel stays at unity. Header-only: the wrapper CMake target
 * globs sources, and this is too small to warrant its own TU.
 */

#pragma once

#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>

class BalanceProcessor
{
public:
    // Full-deflection attenuation of the opposite channel, in dB
    static constexpr double kMaxAttenuationDb = 32.0;

    BalanceProcessor()
        : m_enabled(false)
        , m_gainBitsL(floatToBits(1.0f))
        , m_gainBitsR(floatToBits(1.0f))
    {
    }

    void setEnabled(bool enabled) { m_enabled.store(enabled, std::memory_order_release); }
    bool isEnabled() const { return m_enabled.load(std::memory_order_acquire); }

    // balance in [-1, +1]; clamped. p > 0 dims the left channel
    // (image moves right), p < 0 dims the right channel.
    void setBalance(float balance)
    {
        if (balance < -1.0f) balance = -1.0f;
        if (balance > 1.0f) balance = 1.0f;

        float gl = 1.0f, gr = 1.0f;
        if (balance > 0.0f)
            gl = attenuationLinear(balance);
        else if (balance < 0.0f)
            gr = attenuationLinear(-balance);

        m_gainBitsL.store(floatToBits(gl), std::memory_order_release);
        m_gainBitsR.store(floatToBits(gr), std::memory_order_release);
    }

    void processInterleaved(float* data, size_t numFrames)
    {
        if (!m_enabled.load(std::memory_order_acquire))
            return;
        const float gl = bitsToFloat(m_gainBitsL.load(std::memory_order_acquire));
        const float gr = bitsToFloat(m_gainBitsR.load(std::memory_order_acquire));
        if (gl == 1.0f && gr == 1.0f)
            return;
        for (size_t i = 0; i < numFrames; i++)
        {
            data[i * 2] *= gl;
            data[i * 2 + 1] *= gr;
        }
    }

private:
    static float attenuationLinear(float p)
    {
        const float attDb = static_cast<float>(kMaxAttenuationDb) * p;
        return std::pow(10.0f, attDb / 20.0f);
    }

    // lock-free-ish publication of floats via int atomics
    static uint32_t floatToBits(float f)
    {
        uint32_t bits;
        static_assert(sizeof(bits) == sizeof(f), "float size mismatch");
        __builtin_memcpy(&bits, &f, sizeof(bits));
        return bits;
    }
    static float bitsToFloat(uint32_t bits)
    {
        float f;
        __builtin_memcpy(&f, &bits, sizeof(f));
        return f;
    }

    std::atomic<bool> m_enabled;
    std::atomic<uint32_t> m_gainBitsL;
    std::atomic<uint32_t> m_gainBitsR;
};
