/*
 * ir_windowing.c — Реализация оконных функций для IR.
 */

#include "ir_windowing.h"
#include <math.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

void ir_apply_tukey_left_hann_right(float *ir, int len,
                                     int leftRamp, int rightStart)
{
    if (!ir || len <= 0) return;

    /* Левое окно Tukey: плавное нарастание от 0 до 1 за leftRamp сэмплов.
     * Используем ту же формулу, что и окно Tukey, но только для левой части.
     * w(n) = 0.5 * (1 + cos(pi * (n/leftRamp - 1)))  для 0 <= n < leftRamp
     * w(n) = 1.0                                      для n >= leftRamp */
    if (leftRamp > 0 && leftRamp < len) {
        for (int n = 0; n < leftRamp; n++) {
            double w = 0.5 * (1.0 + cos(M_PI * ((double)n / leftRamp - 1.0)));
            ir[n] *= (float)w;
        }
    }

    /* Правое окно Hann: плавное затухание от 1 до 0.
     * w(n) = 0.5 * (1 + cos(pi * (n - rightStart) / (len - rightStart)))
     * для rightStart <= n < len */
    int rightLen = len - rightStart;
    if (rightLen > 0 && rightStart < len) {
        for (int n = rightStart; n < len; n++) {
            double t = (double)(n - rightStart) / rightLen;
            double w = 0.5 * (1.0 + cos(M_PI * t));
            ir[n] *= (float)w;
        }
    }
}

void ir_apply_hann(float *ir, int len)
{
    if (!ir || len <= 0) return;
    for (int n = 0; n < len; n++) {
        double w = 0.5 * (1.0 - cos(2.0 * M_PI * n / (len - 1)));
        ir[n] *= (float)w;
    }
}

void ir_apply_tukey(float *ir, int len, double alpha)
{
    if (!ir || len <= 0) return;
    if (alpha < 0.0) alpha = 0.0;
    if (alpha > 1.0) alpha = 1.0;

    int rampLen = (int)(alpha * len / 2.0);
    if (rampLen < 1) rampLen = 1;

    /* Левый переход */
    for (int n = 0; n < rampLen; n++) {
        double w = 0.5 * (1.0 + cos(M_PI * ((double)n / rampLen - 1.0)));
        ir[n] *= (float)w;
    }

    /* Плоская часть — без модификации */

    /* Правый переход */
    int rightStart = len - rampLen;
    for (int n = rightStart; n < len; n++) {
        double t = (double)(n - rightStart) / rampLen;
        double w = 0.5 * (1.0 + cos(M_PI * t));
        ir[n] *= (float)w;
    }
}
