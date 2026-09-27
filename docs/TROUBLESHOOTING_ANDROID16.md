# Troubleshooting: Android 16 / OnePlus 15 и новые устройства

## Симптомы
- JamesDSP эффект не виден в системе после установки magisk-модуля
- Эффект виден, но не применяется к аудио
- Приложение работает, но DSP не активен

## Диагностика

### Шаг 1: Проверка загрузки библиотеки
```bash
adb logcat | grep -iE "jamesdsp|libjamesdsp|AudioFlinger.*jdsp"
```

**Если логов нет** — библиотека не загружается. Возможные причины:
- SELinux блокирует загрузку (см. Шаг 2)
- Неверный путь `audio_effects.xml` для данного устройства (см. Шаг 3)
- AIDL-only HAL не поддерживает legacy C-API эффекты (см. Шаг 4)

**Если `No such file or directory`** — SELinux-контекст неверный:
```bash
adb shell ls -Z /vendor/lib/soundfx/libjamesdsp.so
adb shell ls -Z /vendor/lib64/soundfx/libjamesdsp.so
```
Должно быть `u:object_r:vendor_file:s0`. Если другое — модуль не применил `chcon`.

### Шаг 2: SELinux
На KernelSU/SukiSU/APatch убедитесь, что `sepolicy.rule` применён:
```bash
adb shell dmesg | grep -i selinux | grep -i jamesdsp
```

Дополнительная проверка:
```bash
adb shell ls -Z /vendor/lib/soundfx/libjamesdsp.so
# Ожидаемый результат: u:object_r:vendor_file:s0
```

Если контекст неверный, выполните вручную:
```bash
adb shell su -c "chcon u:object_r:vendor_file:s0 /vendor/lib/soundfx/libjamesdsp.so"
adb shell su -c "chcon u:object_r:vendor_file:s0 /vendor/lib64/soundfx/libjamesdsp.so"
adb shell su -c "killall audioserver"
```

### Шаг 3: Пути audio_effects
OnePlus 15 (Snapdragon 8 Elite) использует SKU `sun`:
```
/vendor/etc/audio/sku_sun/audio_effects.xml
/vendor/etc/audio/sku_sun/audio_effects.conf
```

Модуль v6.3.0.4+ включает конфиги для SKU: `lahaina`, `shima`, `yupik`, `sun`, `pistachio`, `crow`, `kalama`.

Проверьте, какой SKU использует ваше устройство:
```bash
adb shell getprop ro.vendor.audio.audiofx.config
adb shell ls /vendor/etc/audio/sku_*
```

Если вашего SKU нет — скопируйте конфиг из `sku_sun` в нужную директорию.

### Шаг 4: AIDL-only HAL
На устройствах с AIDL-only audio effect HAL (некоторые Pixel 8/9 на Android 15+,
возможно OnePlus 15 на Android 16):

```bash
adb shell ls /vendor/etc/vintf/manifest/ | grep audio
# Если есть android.hardware.audio.effect-aidl.xml — устройство использует AIDL HAL
```

**Если AIDL-only:**
- Legacy C-API эффекты могут не загрузиться
- Movie Mode (DynamicsProcessing) ограничен 32 полосами
- **Решение:** используйте rootless-режим (capture loop) — он не зависит от HAL

### Шаг 5: Rootless fallback
Если magisk-модуль не работает на вашем устройстве:
1. Удалите magisk-модуль
2. Установите rootless APK (rootlessFull)
3. Используйте Standard или Low-Latency режим (capture loop)
4. Эти режимы работают через MediaProjection, а не через HAL

### Шаг 6: Дополнительные проверки OnePlus
OnePlus использует нестандартные пути и ODM-модули:
```bash
# Проверка ODM
adb shell ls /odm/etc/audio_effects*
# Проверка vendor audio
adb shell ls /vendor/etc/audio_effects*
adb shell ls /vendor/etc/audio/*audio_effects*
```

OnePlus также использует Audio Modification Library (AML). Если AML установлен,
он может перехватывать конфиги. Модуль v6.3.0.4+ включает AML workaround в `service.sh`.

## Известные ограничения Android 16 (Baklava)

1. **Screen capture restrictions** — усилены по сравнению с Android 15.
   Включите "Disable screen share protection" в Developer Options.

2. **DynamicsProcessing 32-band limit** — сохраняется с Android 15.
   Код детектит через `SdkCheck.isVanillaIceCream` (API 35+).

3. **Foreground service restrictions** — новые ограничения на фоновые сервисы.
   Приложение использует `FOREGROUND_SERVICE_MEDIAPlayback`.

4. **SELinux enforcing** — строже на Android 16.
   `sepolicy.rule` обязателен для KernelSU/SukiSU/APatch.
