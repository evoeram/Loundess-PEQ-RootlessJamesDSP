/* Standalone verification of dynamicEq.c against the JNI call sequence.
 * Feeds sines through the module with realistic block sizes and measures
 * gain reduction / transparency. */
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include "dynamicEq.h"

#define SR 48000

static double rms(const float *x, size_t n)
{
    double s = 0;
    for (size_t i = 0; i < n; i++) s += (double)x[i] * x[i];
    return sqrt(s / n);
}

static double run_case(dynamicEq_t *de, int block, double freq, double ampDb,
                       double bandFreq, int msMode)
{
    const double amp = pow(10.0, ampDb / 20.0);
    float *buf = malloc(sizeof(float) * block * 2);
    double phase = 0, inRms = 0, outRms = 0;
    int blocks = (int)(2.0 * SR / block);
    for (int b = 0; b < blocks; b++) {
        for (int i = 0; i < block; i++) {
            float s = (float)(amp * sin(phase));
            phase += 2.0 * M_PI * freq / SR;
            if (phase > 2.0 * M_PI) phase -= 2.0 * M_PI;
            buf[i * 2] = s;
            buf[i * 2 + 1] = s;
        }
        if (b == 0) inRms = rms(buf, block * 2);
        dynamicEq_process_interleaved(de, buf, block);
        if (b >= blocks - 10) outRms = rms(buf, block * 2);
    }
    free(buf);
    (void)bandFreq;
    return 20.0 * log10(outRms / inRms);
}

int main(void)
{
    dynamicEq_t de;
    dynamicEq_init(&de);

    /* Case 1: default band 1 (180Hz, q1, thr-22, r3, att15, rel150, range-6,
     * compressor); sine at 180 Hz, -10 dBFS -> expect about -6 dB */
    float cfg[8] = { 180.f, 1.f, -22.f, 3.f, 15.f, 150.f, -6.f, 0.f };
    dynamicEq_configure(&de, SR, cfg, 1);
    dynamicEq_set_mix_ms(&de, 100.0, DYNEQ_MS_STEREO);
    dynamicEq_set_enabled(&de, true);
    printf("compress 180Hz sine -10dBFS, 480-block: %.2f dB (expect ~-6)\n",
           run_case(&de, 480, 180.0, -10.0, 180.0, 0));
    printf("compress 180Hz sine -10dBFS, 192-block: %.2f dB (expect ~-6)\n",
           run_case(&de, 192, 180.0, -10.0, 180.0, 0));

    /* Case 2: band at 5 kHz must not touch a 180 Hz sine */
    float cfg2[8] = { 5000.f, 1.f, -30.f, 4.f, 5.f, 50.f, -8.f, 0.f };
    dynamicEq_configure(&de, SR, cfg2, 1);
    printf("compress 5kHz band, 180Hz sine: %.3f dB (expect 0)\n",
           run_case(&de, 480, 180.0, -10.0, 5000.0, 0));

    /* Case 3: expander 1kHz, thr-20, r2, range+12; sine -30 dBFS -> ~+5 dB */
    float cfg3[8] = { 1000.f, 1.f, -20.f, 2.f, 10.f, 100.f, 12.f, 1.f };
    dynamicEq_configure(&de, SR, cfg3, 1);
    printf("expand 1kHz sine -30dBFS: %.2f dB (expect ~+5)\n",
           run_case(&de, 480, 1000.0, -30.0, 1000.0, 0));

    /* Case 4: loud signal above expander threshold -> transparent */
    printf("expand 1kHz sine -5dBFS: %.3f dB (expect 0)\n",
           run_case(&de, 480, 1000.0, -5.0, 1000.0, 0));

    /* Case 5: disabled -> transparent */
    dynamicEq_set_enabled(&de, false);
    printf("disabled: %.3f dB (expect 0)\n",
           run_case(&de, 480, 1000.0, -10.0, 1000.0, 0));

    dynamicEq_destroy(&de);
    return 0;
}
