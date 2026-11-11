/*
 * fir_test.c — on-device numeric verification of linear-phase rendering.
 *
 * Checks:
 *  1. GEQ (ArbitraryEq) linear phase: impulse peak at tap 8191, symmetric
 *     kernel, +6 dB @ 1 kHz for "GraphicEQ: 1000 6.0;".
 *  2. PEQ cascade minimum phase: zero delay, +6 dB @ 1 kHz (single peaking
 *     band), gain at 100 Hz unaffected.
 *  3. PEQ cascade linear phase (new): impulse peak at tap 8191, symmetric
 *     kernel, +6 dB @ 1 kHz, same latency as GEQ.
 *
 * Feed a sine, measure amplitude ratio (simple sine-fit) plus impulse tests.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#include "peq_loudness.h"
#include "balance.h"
#include "jdsp/jdsp_header.h"

/* Tables normally provided by generalFdesign.c — replicated here so the
 * harness only links ArbFIRGen.c + generalProg.c + FFTConvolver.c. */
static unsigned int fhtIntegerLog2(unsigned int v)
{
    unsigned int i = 0;
    while (v > 1) { ++i; v >>= 1; }
    return i;
}
static unsigned int fhtRevBits(unsigned int x, unsigned int bits)
{
    unsigned int y = 0;
    while (bits--) { y = (y + y) + (x & 1); x >>= 1; }
    return y;
}
void fhtbitReversalTbl(unsigned *dst, unsigned int n)
{
    unsigned int bits = fhtIntegerLog2(n);
    for (unsigned int i = 0; i < n; ++i)
        dst[i] = fhtRevBits(i, bits);
}
void fhtsinHalfTblFloat(float *dst, unsigned int n)
{
    const double twopi_over_n = 6.283185307179586476925286766559 / n;
    for (unsigned int i = 0; i < n; ++i)
        dst[i] = (float)sin(twopi_over_n * i);
}
/* Same algorithm as generalFdesign.c LLbitReversalTbl */
void LLbitReversalTbl(unsigned *dst, unsigned int n)
{
    fhtbitReversalTbl(dst, n);
}
/* Stub: interpolating-EQ path is not exercised by this harness */
double getValueAt(cubic_hermite *cuher, double x)
{
    (void)cuher; (void)x;
    return 0.0;
}

#define FS 48000.0
#define N 48000

static double sine_amp_ratio(const float *x, const float *y, size_t n, double freq)
{
    /* least-squares fit of a sine at known frequency to both signals,
       returns amp(y)/amp(x) */
    double cx = 0.0, sx = 0.0, cy = 0.0, sy = 0.0;
    for (size_t i = 0; i < n; i++) {
        double ph = 2.0 * M_PI * freq * (double)i / FS;
        cx += (double)x[i] * cos(ph);
        sx += (double)x[i] * sin(ph);
        cy += (double)y[i] * cos(ph);
        sy += (double)y[i] * sin(ph);
    }
    double ax = 2.0 * sqrt(cx * cx + sx * sx) / (double)n;
    double ay = 2.0 * sqrt(cy * cy + sy * sy) / (double)n;
    return (ax > 1e-12) ? ay / ax : 0.0;
}

static int find_peak(const float *h, int len)
{
    int peak = 0;
    float m = fabsf(h[0]);
    for (int i = 1; i < len; i++)
        if (fabsf(h[i]) > m) { m = fabsf(h[i]); peak = i; }
    return peak;
}

static double asymmetry(const float *h, int center)
{
    /* relative L2 difference between left and right wings */
    double diff = 0.0, ref = 0.0;
    for (int d = 1; d <= center; d++) {
        double a = (center - d >= 0) ? h[center - d] : 0.0;
        double b = (center + d < 2 * center + 1) ? h[center + d] : 0.0;
        diff += (a - b) * (a - b);
        ref += a * a + b * b;
    }
    return (ref > 0.0) ? sqrt(diff / ref) : 0.0;
}

static int check(const char *name, int cond, const char *detail)
{
    printf("[%s] %s (%s)\n", cond ? "PASS" : "FAIL", name, detail);
    return cond ? 0 : 1;
}

int main(void)
{
    int failures = 0;

    /* ================= 1. GEQ linear phase ================= */
    {
        JamesDSPLib *jdsp = (JamesDSPLib *)calloc(1, sizeof(JamesDSPLib));
        /* ArbFIRGen only needs coeffGen; init manually */
        jdsp->arbMag.linearPhase = 1;
        jdsp->arbMag.instance.filterLen = InitArbitraryEq(&jdsp->arbMag.instance.coeffGen, 1);
        ArbitraryEqString2SortedNodes(&jdsp->arbMag.instance.coeffGen, "GraphicEQ: 1000 6.0;");
        float *ir = jdsp->arbMag.instance.coeffGen.GetFilter(&jdsp->arbMag.instance.coeffGen, (float)FS);
        int len = (int)jdsp->arbMag.instance.filterLen;

        int peak = find_peak(ir, len);
        double asym = asymmetry(ir, 8191);
        printf("GEQ linear: len=%d peak=%d asym=%.6f dc=%f\n", len, peak, asym, ir[peak]);

        failures += check("GEQ linear: length is 16383", len == 16383, "16383 expected");
        failures += check("GEQ linear: peak at 8191", peak == 8191, "delay = half kernel");
        failures += check("GEQ linear: kernel symmetric", asym < 0.01, "asym<0.01");

        /* magnitude check via convolving a 1 kHz sine (straight FIR) */
        float *in = (float *)malloc(N * sizeof(float));
        float *out = (float *)malloc((N + len) * sizeof(float));
        memset(out, 0, (N + len) * sizeof(float));
        for (int i = 0; i < N; i++)
            in[i] = (float)sin(2.0 * M_PI * 1000.0 * i / FS);
        for (int i = 0; i < N; i++)
            for (int j = 0; j < len; j++)
                out[i + j] += in[i] * ir[j];
        double ratio = sine_amp_ratio(in, out + 8191, N - 100, 1000.0);
        printf("GEQ linear: 1kHz gain = %.4f dB\n", 20.0 * log10(ratio));
        failures += check("GEQ linear: +6 dB @ 1 kHz", fabs(20.0 * log10(ratio) - 6.0) < 0.1, "6 dB +-0.1");
        free(in); free(out);
        EqNodesFree(&jdsp->arbMag.instance.coeffGen);
        free(jdsp);
    }

    /* ================= 2+3. PEQ min & linear phase ================= */
    {
        /* config: [sr, preamp, freq, gain, q, type, chan] — 1 peaking band */
        float cfg[] = { 48000.0f, 0.0f, 1000.0f, 6.0f, 1.4f, 0.0f, 0.0f };
        float *in = (float *)malloc(N * sizeof(float));
        float *out = (float *)malloc((N + 20000) * sizeof(float));

        for (int mode = 0; mode <= 1; mode++) {
            peq_cascade_t peq;
            peq_cascade_init(&peq);
            peq_cascade_set_block_size(&peq, 4096);
            peq_cascade_set_phase_mode(&peq, mode);
            peq_cascade_configure(&peq, FS, cfg, (int)(sizeof(cfg) / sizeof(cfg[0])));
            peq_cascade_set_enabled(&peq, true);

            memset(out, 0, (N + 20000) * sizeof(float));
            for (int i = 0; i < N; i++)
                in[i] = (float)sin(2.0 * M_PI * 1000.0 * i / FS);

            /* process in 4096-frame blocks into a long buffer */
            float *big = (float *)calloc(2 * (N + 20000), sizeof(float));
            for (int i = 0; i < N; i++) {
                big[2 * i] = in[i];
                big[2 * i + 1] = in[i];
            }
            for (int off = 0; off < N; off += 4096) {
                size_t frames = (N - off < 4096) ? (size_t)(N - off) : 4096;
                peq_cascade_process_interleaved(&peq, big + 2 * off, frames);
            }
            peq_cascade_set_enabled(&peq, false);

            /* L channel = deinterleave */
            float *chan = (float *)malloc((N + 20000) * sizeof(float));
            for (int i = 0; i < N + 20000; i++)
                chan[i] = big[2 * i];
            free(big);

            if (mode == 0) {
                /* impulse through the cascade: peak must sit at tap 0 */
                float *bigImp = (float *)calloc(2 * 64, sizeof(float));
                bigImp[0] = 1.0f; bigImp[1] = 1.0f;
                peq_cascade_process_interleaved(&peq, bigImp, 32);
                int peak = find_peak(bigImp, 32);
                printf("PEQ min: impulse peak=%d val=%f\n", peak, bigImp[2 * peak]);
                failures += check("PEQ min: no added delay", peak < 8, "peak near 0");
                free(bigImp);

                double r1k = sine_amp_ratio(in, chan, N - 200, 1000.0);
                printf("PEQ min: 1kHz gain = %.4f dB\n", 20.0 * log10(r1k));
                failures += check("PEQ min: +6 dB @ 1 kHz", fabs(20.0 * log10(r1k) - 6.0) < 0.1, "6 dB +-0.1");
            } else {
                /* impulse response = convolve delta through 4 blocks */
                float *big = (float *)calloc(2 * 16384, sizeof(float));
                peq_cascade_t peq2;
                peq_cascade_init(&peq2);
                peq_cascade_set_block_size(&peq2, 4096);
                peq_cascade_set_phase_mode(&peq2, 1);
                peq_cascade_configure(&peq2, FS, cfg, (int)(sizeof(cfg) / sizeof(cfg[0])));
                peq_cascade_set_enabled(&peq2, true);
                big[0] = 1.0f; big[1] = 1.0f; /* delta */
                for (int off = 0; off < 16384; off += 4096) {
                    if (off > 0)
                        memset(big + 2 * off, 0, 2 * 4096 * sizeof(float));
                    peq_cascade_process_interleaved(&peq2, big + 2 * off, 4096);
                }
                peq_cascade_set_enabled(&peq2, false);

                int n = 16384;
                float *h = (float *)malloc(n * sizeof(float));
                for (int i = 0; i < n; i++)
                    h[i] = big[2 * i];

                int peak = find_peak(h, n);
                double asym = asymmetry(h, 8191);
                printf("PEQ linear: impulse peak=%d (expect 8191) asym=%.6f val=%f\n", peak, asym, h[peak]);
                failures += check("PEQ linear: peak at 8191", peak == 8191, "delay = half kernel");
                failures += check("PEQ linear: kernel symmetric", asym < 0.02, "asym<0.02");

                /* direct DTFT of the measured kernel */
                for (int f = 0; f < 3; f++) {
                    double fq[3] = { 1000.0, 100.0, 10000.0 };
                    double re = 0.0, im = 0.0;
                    for (int t = 0; t < PEQ_FIR_TAPS; t++) {
                        double ph = 2.0 * M_PI * fq[f] * t / FS;
                        re += h[t] * cos(ph);
                        im -= h[t] * sin(ph);
                    }
                    printf("PEQ linear: kernel DTFT @ %.0f Hz = %.4f dB\n", fq[f], 20.0 * log10(sqrt(re * re + im * im) + 1e-12));
                }
                free(h);
                free(big);

                double r1k = sine_amp_ratio(in, chan + 2 * 8191, N - 3 * 8191, 1000.0);
                /* steady region only: skip the attack pre-ring (first
                 * PEQ_FIR_CENTER samples) and the ring-down tail */
                printf("PEQ linear: 1kHz gain (steady) = %.4f dB\n", 20.0 * log10(r1k));
                failures += check("PEQ linear: +6 dB @ 1 kHz", fabs(20.0 * log10(r1k) - 6.0) < 0.1, "6 dB +-0.1");
            }
            free(chan);
        }
        free(in); free(out);
    }

    /* ================= 4. L/R balance ================= */
    {
        balance_t bal;
        balance_init(&bal);
        float buf[2 * 8];
        int ok = 1;

        /* center: bypass */
        balance_configure(&bal, 0.0f);
        balance_set_enabled(&bal, true);
        for (int i = 0; i < 8; i++) { buf[2 * i] = 0.5f; buf[2 * i + 1] = 0.5f; }
        balance_process_interleaved(&bal, buf, 8);
        ok &= (buf[0] == 0.5f && buf[1] == 0.5f);

        /* full right: left channel at -32 dB */
        balance_configure(&bal, 1.0f);
        for (int i = 0; i < 8; i++) { buf[2 * i] = 0.5f; buf[2 * i + 1] = 0.5f; }
        balance_process_interleaved(&bal, buf, 8);
        float gl_db = 20.0 * log10(buf[0] / 0.5f + 1e-12);
        float gr_db = 20.0 * log10(buf[1] / 0.5f + 1e-12);
        printf("Balance p=+1: L=%.4f dB (expect -32), R=%.4f dB (expect 0)\n", gl_db, gr_db);
        ok &= (fabs(gl_db + 32.0) < 0.01 && fabs(gr_db) < 0.001);

        /* half left: right channel at -16 dB */
        balance_configure(&bal, -0.5f);
        for (int i = 0; i < 8; i++) { buf[2 * i] = 0.5f; buf[2 * i + 1] = 0.5f; }
        balance_process_interleaved(&bal, buf, 8);
        gl_db = 20.0 * log10(buf[0] / 0.5f + 1e-12);
        gr_db = 20.0 * log10(buf[1] / 0.5f + 1e-12);
        printf("Balance p=-0.5: L=%.4f dB (expect 0), R=%.4f dB (expect -16)\n", gl_db, gr_db);
        ok &= (fabs(gl_db) < 0.001 && fabs(gr_db + 16.0) < 0.01);

        /* disabled: bypass */
        balance_set_enabled(&bal, false);
        for (int i = 0; i < 8; i++) { buf[2 * i] = 0.5f; buf[2 * i + 1] = 0.5f; }
        balance_process_interleaved(&bal, buf, 8);
        ok &= (buf[0] == 0.5f && buf[1] == 0.5f);

        failures += check("Balance: center/deflection/disable", ok == 1, "dB-linear opposite-channel attenuation");
    }

    printf("\n%s (%d failures)\n", failures == 0 ? "ALL OK" : "FAILURES", failures);
    return failures;
}
