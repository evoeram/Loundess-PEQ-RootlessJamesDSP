# HiBy R4 — Audio Policy Configuration Analysis

## Проблема: сдвиг частот PEQ-фильтров на ~1/8 октавы влево

### Симптомы (от пользователя Володимир, HiBy R4)

- PEQ-фильтр, настроенный на 5600 Гц, фактически работает на ~5145 Гц
- Сдвиг: `5600 × (44100/48000) = 5145.0 Гц` — точное совпадение
- Октавный сдвиг: `log₂(44100/48000) = -0.1223 октавы ≈ -0.98/8 октавы` (≈ "одна восьмая октавы влево")
- Частота дискретизации фиксируется на 44.1 кГц для музыкальных плееров, YouTube, браузеров
- Частота дискретизации 48 кГц для генераторов сигналов → сдвига нет
- Родной общесистемный эквалайзер HiBy этой проблемой не страдает
- Проблема воспроизводится со всеми сторонними общесистемными эквалайзерами

### Корневая причина

В `audio_policy_configuration.xml` HiBy R4 (Qualcomm trinket, Android 12, SDK 31):

```xml
<!-- primary output: 48 kHz only -->
<mixPort name="primary output" role="source" flags="AUDIO_OUTPUT_FLAG_FAST|AUDIO_OUTPUT_FLAG_PRIMARY">
    <profile name="" format="AUDIO_FORMAT_PCM_16_BIT"
             samplingRates="48000" channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
</mixPort>

<!-- deep_buffer: 44.1 kHz ONLY — музыкальные плееры идут через этот путь -->
<mixPort name="deep_buffer" role="source"
        flags="AUDIO_OUTPUT_FLAG_DEEP_BUFFER">
    <profile name="" format="AUDIO_FORMAT_PCM_16_BIT"
             samplingRates="44100" channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
</mixPort>
```

Android AudioFlinger направляет музыкальные приложения (через `AudioTrack` с `DEEP_BUFFER` или по умолчанию) в `deep_buffer` mixPort, который захардкожен на **44100 Hz**.

JamesDSP (и любой сторонний эквалайзер через AudioEffect API) получает от AudioFlinger поток на 44100 Hz, но **библиотека libjamesdsp.so инициализируется на 48000 Hz** (жёстко задано в коде или по умолчанию). Biquad-коэффициенты рассчитываются для 48000, а применяются к потоку 44100 → все частоты сдвигаются на коэффициент `44100/48000 = 0.91875`.

Генераторы сигналов и некоторые системные звуки идут через `primary output` (48 kHz) → совпадение с ожидаемой частотой DSP → сдвига нет.

### Почему родной эквалайзер HiBy работает правильно

Родной эквалайзер HiBy либо:
1. Корректно детектит фактическую частоту дискретизации потока (44100) и пересчитывает коэффициенты, либо
2. Встроен в AudioFlinger ниже уровня AudioEffect API и имеет доступ к реальному sample rate

### Возможные решения

#### Вариант A: Патч audio_policy_configuration.xml через Magisk (рекомендуется)

Добавить 44100 в `samplingRates` для `deep_buffer` mixPort (или заменить 44100 на 48000):

```xml
<!-- Патч: deep_buffer на 48 kHz -->
<mixPort name="deep_buffer" role="source"
        flags="AUDIO_OUTPUT_FLAG_DEEP_BUFFER">
    <profile name="" format="AUDIO_FORMAT_PCM_16_BIT"
             samplingRates="48000" channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
</mixPort>
```

Или расширить список:
```xml
             samplingRates="44100,48000"
```

Это заставит AudioFlinger направлять deep_buffer потоки на 48 kHz. Если приложение пишет 44100, AudioFlinger сделает resampling.

**Плюс:** не требует изменения в коде JamesDSP, работает для всех сторонних эквалайзеров.
**Минус:** может добавить лёгкий resampling на уровне HAL (обычно незаметный).

#### Вариант B: Детекция sample rate в JamesDSP

В `JamesDspWrapper.cpp` / `libjamesdsp` — читать фактическую частоту дискретизации из параметров AudioEffect и пересчитывать biquad-коэффициенты для реального sample rate.

**Плюс:** работает на любом устройстве без патчей системы.
**Минус:** требует модификации C++ кода libjamesdsp.

#### Вариант C: Комбинированный (A + B)

Патчить audio_policy + детекция sample rate в JamesDSP как страховка.

### Файлы

- `audio_policy_configuration.xml` — основной конфиг HiBy R4 (из `/vendor/etc/audio_policy_configuration.xml`)
- `audio_policy_configuration (2).xml` — альтернативный конфиг (из `/vendor/etc/audio/audio_policy_configuration.xml`, идентичен по содержимому, другой путь)
- `audio_sub.xml` — копия основного (для анализа)

### Характеристики устройства

| Параметр | Значение |
|---|---|
| Модель | HiBy R4 |
| SoC | Qualcomm trinket (trinket = SDM632?) |
| Android | 12 (SDK 31) |
| HAL version | 2.0 |
| Primary output | 48000 Hz |
| Deep buffer | **44100 Hz** (проблема!) |
| Direct PCM | 44100,48000,64000,88200,96000,128000,176400,192000 |
| Compressed offload | 32000,44100,48000,88200,96000,176400,192000,... |
| A2DP output | 48000 Hz |
| BT A2DP (device ports in alt config) | 44100,48000,88200,96000 |

### Дополнительное наблюдение

HiBy закомментировал `raw` mixPort (см. комментарий `fibo_zhangshenghui:Close raw path`), что может влиять на маршрутизацию low-latency потоков. В альтернативном конфиге `raw` присутствует на 48000.
