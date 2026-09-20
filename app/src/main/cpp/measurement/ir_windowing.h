/*
 * ir_windowing.h — Оконные функции для импульсной характеристики.
 *
 * Применяет:
 *  - Левое окно Tukey: плавное нарастание от 0, обрезает THD (t < 0)
 *  - Правое окно Hann: плавное затухание, устраняет усечение сигнала
 *
 * Tukey окно: косинус-сферическое окно с параметром alpha.
 * alpha = 0 → прямоугольное, alpha = 1 → окно Hann.
 */

#ifndef IR_WINDOWING_H
#define IR_WINDOWING_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Применить комбинированное окно: левое Tukey + правое Hann.
 *
 * ir:            массив сэмплов IR (модифицируется in-place)
 * len:           длина массива
 * leftRamp:      количество сэмплов для левого Tukey окна (нарастание)
 * rightStart:    индекс начала правого окна Hann (затухание до конца)
 */
void ir_apply_tukey_left_hann_right(float *ir, int len,
                                     int leftRamp, int rightStart);

/*
 * Применить окно Hann к массиву.
 */
void ir_apply_hann(float *ir, int len);

/*
 * Применить окно Tukey с параметром alpha.
 * alpha: доля окна, занимаемая косинусным переходом (0..1).
 */
void ir_apply_tukey(float *ir, int len, double alpha);

#ifdef __cplusplus
}
#endif

#endif /* IR_WINDOWING_H */
