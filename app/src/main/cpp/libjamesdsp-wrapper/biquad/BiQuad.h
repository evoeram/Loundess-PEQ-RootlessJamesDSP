/*
 * Ported from EqualizerAPO's filters/BiQuad.h and filters/BiQuad.cpp.
 * Original: Copyright (C) 2013-2014 Jonas Thedering, GPLv2.
 *
 * Changes for the RootlessJamesDSP / Android NDK port:
 *  - Removed stdafx.h and MSVC-specific keywords (__forceinline, __declspec(align)).
 *  - Made the class header-only (coefficients are computed in the constructor).
 *  - Replaced platform-specific M_PI/M_LN2 with portable fallback defines.
 *  - Kept the exact RBJ-style coefficient math identical to the original.
 */

#pragma once

#include <cmath>
#include <cstdlib>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif
#ifndef M_LN2
#define M_LN2 0.69314718055994530942
#endif

class BiQuad
{
public:
    enum Type
    {
        LOW_PASS, HIGH_PASS, BAND_PASS, NOTCH, ALL_PASS, PEAKING, LOW_SHELF, HIGH_SHELF
    };

    BiQuad() : a0(1.0), x1(0.0), x2(0.0), y1(0.0), y2(0.0)
    {
        a[0] = a[1] = a[2] = a[3] = 0.0;
    }

    BiQuad(Type type, double dbGain, double freq, double srate, double bandwidthOrQOrS, bool isBandwidthOrS)
        : x1(0.0), x2(0.0), y1(0.0), y2(0.0)
    {
        double A;
        if (type == PEAKING || type == LOW_SHELF || type == HIGH_SHELF)
            A = pow(10, dbGain / 40);
        else
            A = pow(10, dbGain / 20);
        double omega = 2 * M_PI * freq / srate;
        double sn = sin(omega);
        double cs = cos(omega);
        double alpha;

        if (!isBandwidthOrS) // Q
            alpha = sn / (2 * bandwidthOrQOrS);
        else if (type == LOW_SHELF || type == HIGH_SHELF) // S
            alpha = sn / 2 * sqrt((A + 1 / A) * (1 / bandwidthOrQOrS - 1) + 2);
        else // BW
            alpha = sn * sinh(M_LN2 / 2 * bandwidthOrQOrS * omega / sn);

        double beta = 2 * sqrt(A) * alpha;

        double b0, b1, b2, a0, a1, a2;

        switch (type)
        {
        case LOW_PASS:
            b0 = (1 - cs) / 2;
            b1 = 1 - cs;
            b2 = (1 - cs) / 2;
            a0 = 1 + alpha;
            a1 = -2 * cs;
            a2 = 1 - alpha;
            break;
        case HIGH_PASS:
            b0 = (1 + cs) / 2;
            b1 = -(1 + cs);
            b2 = (1 + cs) / 2;
            a0 = 1 + alpha;
            a1 = -2 * cs;
            a2 = 1 - alpha;
            break;
        case BAND_PASS:
            b0 = alpha;
            b1 = 0;
            b2 = -alpha;
            a0 = 1 + alpha;
            a1 = -2 * cs;
            a2 = 1 - alpha;
            break;
        case NOTCH:
            b0 = 1;
            b1 = -2 * cs;
            b2 = 1;
            a0 = 1 + alpha;
            a1 = -2 * cs;
            a2 = 1 - alpha;
            break;
        case ALL_PASS:
            b0 = 1 - alpha;
            b1 = -2 * cs;
            b2 = 1 + alpha;
            a0 = 1 + alpha;
            a1 = -2 * cs;
            a2 = 1 - alpha;
            break;
        case PEAKING:
            b0 = 1 + (alpha * A);
            b1 = -2 * cs;
            b2 = 1 - (alpha * A);
            a0 = 1 + (alpha / A);
            a1 = -2 * cs;
            a2 = 1 - (alpha / A);
            break;
        case LOW_SHELF:
            b0 = A * ((A + 1) - (A - 1) * cs + beta);
            b1 = 2 * A * ((A - 1) - (A + 1) * cs);
            b2 = A * ((A + 1) - (A - 1) * cs - beta);
            a0 = (A + 1) + (A - 1) * cs + beta;
            a1 = -2 * ((A - 1) + (A + 1) * cs);
            a2 = (A + 1) + (A - 1) * cs - beta;
            break;
        case HIGH_SHELF:
            b0 = A * ((A + 1) + (A - 1) * cs + beta);
            b1 = -2 * A * ((A - 1) + (A + 1) * cs);
            b2 = A * ((A + 1) + (A - 1) * cs - beta);
            a0 = (A + 1) - (A - 1) * cs + beta;
            a1 = 2 * ((A - 1) - (A + 1) * cs);
            a2 = (A + 1) - (A - 1) * cs - beta;
            break;
        default:
            b0 = 1; b1 = 0; b2 = 0; a0 = 1; a1 = 0; a2 = 0;
            break;
        }

        this->a0 = b0 / a0;
        this->a[0] = b1 / a0;
        this->a[1] = b2 / a0;
        this->a[2] = a1 / a0;
        this->a[3] = a2 / a0;
    }

    inline void removeDenormals()
    {
        const double denorm_limit = 2.2250738585072014e-308; // DBL_MIN
        if (std::abs(x1) < denorm_limit) x1 = 0.0;
        if (std::abs(x2) < denorm_limit) x2 = 0.0;
        if (std::abs(y1) < denorm_limit) y1 = 0.0;
        if (std::abs(y2) < denorm_limit) y2 = 0.0;
    }

    inline double process(double sample)
    {
        // changed order of additions leads to better pipelining (kept from original)
        double result = a0 * sample + a[1] * x2 + a[0] * x1 - a[3] * y2 - a[2] * y1;

        x2 = x1;
        x1 = sample;

        y2 = y1;
        y1 = result;

        return result;
    }

    inline void setCoefficients(double ain[], const double& a0in)
    {
        for (int i = 0; i < 4; i++)
            a[i] = ain[i];
        a0 = a0in;
    }

    double gainAt(double freq, double srate)
    {
        double omega = 2 * M_PI * freq / srate;
        double sn = sin(omega / 2.0);
        double phi = sn * sn;
        double b0 = this->a0;
        double b1 = this->a[0];
        double b2 = this->a[1];
        double a0 = 1.0;
        double a1 = this->a[2];
        double a2 = this->a[3];

        double dbGain = 10 * log10(pow(b0 + b1 + b2, 2) - 4 * (b0 * b1 + 4 * b0 * b2 + b1 * b2) * phi + 16 * b0 * b2 * phi * phi)
            - 10 * log10(pow(a0 + a1 + a2, 2) - 4 * (a0 * a1 + 4 * a0 * a2 + a1 * a2) * phi + 16 * a0 * a2 * phi * phi);

        return dbGain;
    }

    void getCoefficients(double(&out_coeffs)[4], double& out_a0) const
    {
        out_a0 = this->a0;
        for (int i = 0; i < 4; ++i)
        {
            out_coeffs[i] = this->a[i];
        }
    }

private:
    alignas(16) double a[4];
    double a0;

    double x1, x2;
    double y1, y2;
};
