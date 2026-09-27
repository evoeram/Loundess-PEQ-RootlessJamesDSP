# AIDL Audio Effect HAL — статус и план поддержки

## Проблема

Android 14+ переводит audio effect HAL с legacy C-API (`AUDIO_EFFECT_LIBRARY_INFO_SYM`) на AIDL
(`android.hardware.audio.effect.IEffect` / `IFactory`). На устройствах с AIDL-only HAL
(Pixel 8/9 на Android 15+, OnePlus 15 на Android 16, будущие устройства) legacy C-API эффекты
могут не загрузиться вообще.

**Текущий статус:** `libjamesdsp.so` — legacy C-API эффект. Регистрируется через
`audio_effects.xml` v2.0 (`<library name="jdsp" path="libjamesdsp.so"/>`).

На большинстве устройств (Android 12-15) legacy C-API всё ещё работает через обратную
совместимость (`libeffectproxy.so`). На Android 16+ (Baklava) с AIDL-only HAL это может
перестать работать.

## Вариант 1: AIDL-обёртка (полноценное решение)

Создание AIDL-эффекта требует:

### 1. AIDL-интерфейсы
```
android/hardware/audio/effect/IEffect.aidl
android/hardware/audio/effect/IFactory.aidl
android/hardware/audio/effect/Range.aidl
android/hardware/audio/effect/EffectExtension.aidl
```

### 2. Реализация
- `libjdsp_aidl.so` — AIDL-сервис, обёртывающий `libjamesdsp.so`
- Регистрация через `vendor.audio-effects-aidl.xml` (VINTF manifest)
- Implementation of `IEffect` с делегированием к существующему C-API

### 3. Конфигурация
```xml
<!-- audio_effects.xml -->
<libraries>
    <library name="jdsp_aidl" path="libjdsp_aidl.so"/>
</libraries>
<effects>
    <effect name="jamesdsp" library="jdsp_aidl" uuid="f27317f4-c984-4de6-9a90-545759495bf2"/>
</effects>
```

### 4. Сборка
- Soong-модуль с `aidl_interface { name: "android.hardware.audio.effect-V1-ndk" }`
- Зависимость от `libjamesdsp.so` (existing C-API library)

### Оценка: 40-80 часов, требует NDK + AIDL codegen

## Вариант 2: Fallback через libeffectproxy (краткосрочное решение)

На устройствах, где legacy C-API всё ещё работает (большинство Android 12-15), но
AIDL-proxy нужен:

```xml
<effectProxy name="jamesdsp" library="proxy" uuid="...">
    <libsw library="jdsp" uuid="f27317f4-c984-4de6-9a90-545759495bf2"/>
</effectProxy>
```

Это позволяет системе выбрать SW-реализацию через proxy, даже если HW-путь
недоступен. Уже используется для других эффектов (bassboost, virtualizer и т.д.)
в `sku_*/audio_effects.xml`, но **не** для jamesdsp — он указан как прямой effect.

### Оценка: 2-4 часа

## Вариант 3: Rootless fallback (app-level)

В rootless-режиме (без magisk-модуля) AIDL-проблема не актуальна —
`DynamicsProcessing` API работает через Android framework, а не через HAL.

В root-режиме с AIDL-only HAL можно переключиться на rootless-режим:
- Session 0 `DynamicsProcessing` через `AudioEffect` (как в Movie Mode)
- Или capture loop (Standard/Low-Latency Mode)

### Оценка: 0 часов (уже реализовано в app)

## Текущее ограничение

На Android 15+ с AIDL HAL: DynamicsProcessing (Movie Mode) ограничен 32 полосами
(вместо 128). Код в `AndroidEq.kt` уже детектит это через `SdkCheck.isVanillaIceCream`.

На Android 16: ограничение сохраняется, плюс возможно полное отсутствие legacy C-API.
`SdkCheck.isBaklava` добавлен для будущей детекции.

## Рекомендация

1. **Немедленно:** Применить Вариант 2 (effectProxy для jamesdsp) — 2-4 часа
2. **Среднесрочно:** Начать Вариант 1 (AIDL-обёртка) — до массового распространения Android 16
3. **Fallback:** Вариант 3 уже работает — rootless-режим不受влияет на AIDL HAL
