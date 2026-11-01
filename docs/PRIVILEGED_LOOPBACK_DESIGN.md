# Privileged loopback mode — дизайн (скелет)

Альтернативный режим захвата, устраняющий проблему SPL burst'а архитектурно: вместо
MediaProjection + mute-эффектов на чужие сессии — **LOOP_BACK-маршрут динамической
AudioPolicy через Shizuku**. Прецедент: sk2andy/android-realtime-voice-isolation.

## Почему это убирает burst как класс

Pure `ROUTE_FLAG_LOOP_BACK` **удаляет совпадающие плееры из обычного рендера**:
медиа-микс целиком уходит в наш sink и на устройство не играет. Оригинала на выходе
нет — играет только наш выходной трек. Следствия:

- нет mute-эффектов на чужие сессии → нет гонки attach → нет SPL burst;
- нет зависимости от `AudioPlaybackConfiguration` (sanitized на Android 12+);
- нет session-tracking (пул, playerEverSeen, anchor'ы) — вся механика mute не нужна;
- пер-приложенный выбор (blocklist/allowlist) делается `AudioMixingRule`-фильтрами.

## Цена / ограничения

| Ограничение | Детали |
|---|---|
| Shizuku запущен + грант | UserService живёт в shell/root UID; без root Shizuku перезапускается после ребута |
| Android 13+ | adb-shell не имеет `MODIFY_AUDIO_ROUTING` на 12; на 13+ нужна shell-атрибуция (`installShellAttribution`) |
| Формат микса фиксирован при регистрации | смена sample rate = перерегистрация policy (close+open) |
| Фильтр контента | только `USAGE_MEDIA/GAME/UNKNOWN`; звонки/алармы не захватываются |
| DRM/offload/tunneled | может миновать framework mix |
| OEM | динамическая AudioPolicy — AOSP-механизм, на вендорских ROM не гарантирован |

## Архитектура

```text
Shizuku (shell UID) ── bindUserService ──> LoopbackCaptureUserService (процесс shell)
                                              ├─ LoopbackAudioPolicy (рефлексия, hidden API)
                                              │    AudioMixingRule: USAGE_MEDIA/GAME/UNKNOWN
                                              │    excludeMixRule(RULE_MATCH_UID, наш uid)
                                              │    AudioMix ROUTE_FLAG_LOOP_BACK, 48 кГц стерео
                                              └─ AudioRecord sink (REMOTE_SUBMIX_SOURCE + addr tag)
                                                     │ PCM16 stereo blocks (20 мс)
                                                     ▼ AIDL oneway onPcm()
Процесс приложения (RootlessAudioProcessorService)
    LoopbackCaptureClient ──> LoopbackPcmSource (read()-фасад под существующий capture-цикл)
    engine.processInt16(...)  → AudioTrack (как сейчас)
```

Аудиотракт (engine, выходной трек, silence-gate, queueControl) остаётся без изменений —
меняется только **источник PCM**. Mute-механика (sessionDatabase, пул, anchor'ы) в этом
режиме не запускается.

### Транспорт PCM

Скелет: AIDL oneway `onPcm(byte[])` — 20 мс блок 16-bit стерео = 7680 байт, ~380 КБ/с,
binder справляется. Эволюция: shared memory (`MemoryFile`/`SharedMemory` fd) — тем же
интерфейсом, заменой `byte[]` на fd; интерфейс менять не придётся.

### Где живёт AudioRecord — решение

AudioPolicy и её sink **обязаны** жить в процессе владельца policy (shell UID):
создание policy проверяет `MODIFY_AUDIO_ROUTING` по вызывающему UID. Поэтому
UserService — единственный контейнер. Альтернатива на будущее: перенести в UserService
и весь DSP-движок (нативная .so доступна процессу UserService), убрав IPC-копию PCM —
Phase 2.

## Файлы скелета

| Файл | Роль |
|---|---|
| `loopback/ILoopbackCapture.aidl` | интерфейс управления захватом |
| `loopback/ILoopbackCaptureCallback.aidl` | oneway-колбэк: onStarted/onPcm/onError/onStopped |
| `loopback/LoopbackAudioPolicy.kt` | рефлексия AudioPolicy+AudioRecord (в shell-процессе) |
| `loopback/ShellAttribution.kt` | подмена `mAttributionSource` на com.android.shell (Android 13+) |
| `loopback/LoopbackCaptureUserService.kt` | Shizuku UserService: policy + record + насос PCM |
| `loopback/LoopbackCaptureClient.kt` | app-сторона: bindUserService, приём PCM, `LoopbackPcmSource` |

## Интеграция в RootlessAudioProcessorService (Phase 2, не в скелете)

1. **Настройка**: новый `key_capture_mode` (`media_projection` / `privileged_loopback`),
   карточка в онбординге рядом с Shizuku-карточкой (инфраструктура уже есть).
2. **startRecording()**: в loopback-режиме
   - не строить `buildAudioRecord` (MediaProjection) и не запускать sessionManager;
   - `LoopbackCaptureClient.start(...)` → дождаться `onStarted`;
   - `recorder` подменить на `LoopbackPcmSource` (реализует `read()` блокировкой);
   - `isProcessorIdle` — не из sessionList (он пуст), а из «есть ли активные плееры»
     (`AudioPlaybackCallback` по активным конфигам) или просто silence-gate.
3. **Отключить**: mute-механику целиком (sessionDatabase, пул, anchors, capture-gap
   ускорение refs) — она не нужна; `syncFromPlaybackConfigs` и dump-путь не опрашивать.
4. **Пересоздание рекордера** (`recreateRecorderRequested`): в loopback-режиме →
   рестарт UserService-захвата (перерегистрация policy).
5. **Fallback**: любой отказ Shizuku/policy (не 13+, OEM-отказ) → откат на
   MediaProjection-режим с пользовательским сообщением.

## Риски / открытые вопросы

- ColorOS-реализация динамической AudioPolicy — проверить первым делом на MT2111
  (регистрация возвращает код; AUDIO_POLICY_SUCCESS=0).
- Приоритет mix'а против MediaProjection — одновременно два захвата не нужны, режимы
  взаимоисключающие.
- Уровень микса: loopback отдаёт пост-фейд-микс на фикс. громкости; тег `fixedVolume`
  (как у sk2andy) отключает следование за output volume — уточнить желаемое поведение
  (для DSP громкость рулит наш post gain; system volume должен влиять → возможно,
  БЕЗ fixedVolume).
- Рестарт audioserver убивает policy → UserService должен уметь реконнект/перерегистрацию.
