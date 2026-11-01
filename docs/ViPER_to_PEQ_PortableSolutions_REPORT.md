# ViPER4Android → Loudness PEQ: портативные решения

Аудит **всех 320 коммиттов** `C:\dev\RootlessViPER4Android` по **реальному diff** (`git show`), с сопоставлением
каждого решения с текущим состоянием `C:\dev\Loundess-PEQ-RootlessJamesDSP`.

Режим: **только read-only статический анализ** (`git log/show/diff --stat`, чтение файлов). Ничего не
собиралось, не устанавливалось, не запускалось из source-репозитория. **Ни одной строки кода в target не
перенесено** — это отчёт, а не патч.

Рабочие артефакты реестра: `C:\dev\DSP_GIT\codeview\ViPER_commits_raw.tsv` (320 строк, покрытие 100 %),
`batch_00.tsv … batch_07.tsv`, `ViPER_commits_index.txt`, `ViPER_commit_exts.txt`, `ViPER_commit_flags.txt`.

---

## 0. TL;DR — топ-10 (в порядке ценности/стоимости)

| # | Что | Почему это важно прямо сейчас | Стоимость | Статус в target |
|---|-----|-------------------------------|-----------|-----------------|
| 1 | **Vacuum tube — математическое no-op** (`postgain = 1/pregain`) | Эффект в target физически не слышен: drive полностью компенсируется. В source это исправлено асимметрическим клиппером + `pow(pregain, 0.6)` | **S** | дефект подтверждён, не починен |
| 2 | **Crossfeed: пересборка конволверов каждый блок + гонка `advXF.mode`** | Target требует `convLong_S_S` **И** `convLong_T_S`; при сборке только одного — 4 конволвера пересобираются на каждый вызов. Плюс `mode` читается на каждый сэмпл → запись состоянием `bs2b[2..4]` поверх полей DDC/convolver | **S** | оба бага живые |
| 3 | **`dbb.c`: запись за `originalBuf[960]` при смене 48→44.1 кГц** | `if (pos == factor)` вместо `>=`; фаза никогда не оборачивается →heap OOB в реальном потоке | **S** | живой defekt |
| 4 | **`vdc.c`: неограниченный парсер DDC-строки** | `numberCount` считается до конца строки (обе секции), циклы идут до `numberCount` → запись за `DirectForm2[sosCount]` при хвостовой запятой | **S** | живой |
| 5 | **`selectConvPartitions`: неинициализированный `type_best` + выход за таблицы бенчмарка** | `int type_best;` без значения; при блоке 65536 `s = 10` читает `convbench_c0/c1[10]` при `MAX_BENCHMARK = 10` | **S** | живой |
| 6 | **Блокировки DSP-цепи** (`CompressorProcess`, mEQ, arbEq под `jdsp_lock`; `SetMode` под локом; deadlock в `JamesDSPSetSampleRate`) | В target `CompressorProcess` вызывается **до** `jdsp_lock`, а `FFTCompanderInit` перестраивает длины/указатели с треда настроек. И `JamesDSPSetSampleRate(forceRefresh=1)` берёт нерекурсивный мьютекс и внутри зовёт `CrossfeedEnable`, который берёт его снова → self-deadlock | **M** | живой, класс крашей audio |
| 7 | **Rate-dependent redesign никогда не происходит** | Target из Kotlin зовёт `setSamplingRate(handle, value, false)` — `forceRefresh` из приложения не выставляется **никогда**, поэтому пересчёт DDC-коэффициентов, констант времени compander и stereo widening на смену частоты не выполняется | **M** | живой |
| 8 | **Отсутствие `isfinite`-гардов в основной цепи** | Единичный NaN в `tmpBuffer` отравляет анализ, smooth-оценки и состояние фильтров; в source это закрыто точечно (process/DBB/echo/vinyl/MBD) | **S** | живой |
| 9 | **Soname/HAL-упаковка** (`NO_SONAME` + явный `-Wl,-soname,libjamesdsp.so`) | Target собирает `libjamesdsp_effect.so`, а `patch_audio_config.sh` регистрирует `libjamesdsp.so`; bionic регистрирует библиотеку по **soname**, не по пути загрузки | **S** | не сделано |
| 10 | ** Crash-диагностика: `CrashGuard.cpp` (sigaction) + JVM `UncaughtExceptionHandler` + breadcrumb-лог** | Native-краш ауди-сервиса сейчас неотличим от зависания; в source есть воспроизведённый пайплайн (pc/lr/sp/x0 + фильтрованный `/proc/self/maps`) | **M** | файлов нет вообще |

Далее — полный реестр и карточки.

---

## 1. Методология и цифры

### 1.1 Как считывался каждый коммит

1. Инвентарь: `git log --all --no-merges --pretty='%h|%ad|%an|%s'` + `git show --stat --numstat` для каждого SHA.
   Рабочие таблицы — `codeview/batch_00..07.tsv` (по 40 коммиттов), формат
   `sha|date|author|subject|files|binaryFiles|diffLines|kindFlags`.
2. Полное чтение diff: `git show <sha>` **целиком**, включая новые/переименованные файлы,
   `.c/.h/.cpp/.kt/.kts/.xml/.aidl/.rc/.prop/.sh`, sepolicy и `module.prop`. Для base-коммита `8c3d56e`
   (357 027 diff-строк, 1513 файлов) вместо построчного чтения — структурная характеристика
   (`--numstat`, `ls-tree`, выборочное открытие файлов); это честно помечено в §5.
3. Каждый коммит переведён в одну формулировку «что делает код» (не «что обещает message») и классифицирован:

   - **A** — DSP-алгоритмы; **B** — AudioEngine: порядок цепи, gain staging, буферы, блокировки;
   - **C** — root/SELinux-обход, hidden-API, инъекция, HAL-атрибуты, sepolicy;
   - **D** — AIDL/HAL/API-совместимость, Android 13/14/15+; **E** — build-инженерия (buildSrc/gradle, Magisk,
     CI, подпись); **F** — Android/UI/пресеты/логирование/локализация; **G** — тесты, on-device harness'ы.
   Приоритет портирования: **A > B > C > D > E > G > F**.
4. Сопоставление с target: чтение `docs/`, `NOTICE`, `next_step/`, `1_AIDL_HAL`, `buildSrc/`,
   `app/src/main/{cpp,java,res}`, `magisk-module/` — и **проверка по строкам**, что заявленный defekt в target
   действительно жив (а не уже починен).
5. Статус: `NEW` / `PARTIAL` / `DEEPER` / `CONFLICT` / `PORTED` / `NOT_APPLIC`; стоимость `S/M/L/XL`.

### 1.2 Цифры (механически пересчитаны из реестра)

| Метрика | Значение |
|---|---|
| Коммиттов в репозитории, `git rev-list --count --all` | **320** |
| Коммиттов без merge'ов, `--all --no-merges` | 320 (merge-ов нет) |
| Веток | 3 |
| Строк данных в `ViPER_commits_raw.tsv` | **320** |
| **Покрытие diff** | **320 / 320 = 100 %** |
| Контроль целостности SHA | `comm -3 <(git log --all --no-merges --pretty='%h' \| sort) <(awk -F'\|' '{print $1}' tsv \| sort -u)` → **0 различий** |
| Кандидаты `YES` (портить) | **139** |
| `NO` (не портить) | **154** |
| Вердикт не зафиксирован в колонке (сдвиг/пропуск при записи реестра) | **27** — см. §5, пункт U-1 || Приоритет HIGH / MED / LOW | 78 / 78 / 163 |
| `YES` ∩ `HIGH` | **76** |
| `YES` ∩ `NEW` | 104 |
| Категории (встречаемость, с составными): F 149, A 87, B 62, E 67, D 42, G 52, C 26 | — |
| Статус в target: NEW 157, NOT_APPLIC 113, PORTED 18, PARTIAL 18, DEEPER 7, UNKNOWN 6 | — |

> Всего записей «строка ↔ SHA» — 320; из них 319 корректно разбираются по 7-колоночной схеме, одна строка
> (`d2c88e3`) потеряла хвостовые колонки при записи. Это не влияет на покрытие diff — только на
> автоматическую агрегацию вердиктов.

### 1.3 Почему `NO` у 154 коммиттов (сгруппированные причины)

- **NOT_APPLIC — 113.** Root-only инфраструктура source (Magisk-модуль, sepolicy-патчи, AIDL-HAL-прокси,
  `service.sh`/`post-fs-data.sh`), V4A-специфичный UI/тематика/иконки/пресеты, README/fastlane/crowdin,
  эффекты, которых в архитектуре target нет (Liveprog-слоты, VST3-обёртки), а также правки файлов,
  отсутствующих в target (`viperextras*.c`, `vreverb.c` и т. п.).
- **PORTED — 18.** Уже перенесено ранее и подтверждено по строкам: `dynamicEq.c/h`
  (`NOTICE:130-142`), LUFS-нормализатор (`NOTICE:150-160`), FET-компрессор/Clarity/Field Surround/AGC/Cure
  (`NOTICE:168-191`), `1e19ee3` (rate--refresh архитектура).
- **Прочее.** Изменения только в сообщениях/метаданных, чистая локализация, косметика, дублирующие fix'ы.

---

## 2. Сводная таблица кандидатов

Полный перечень 76 `YES+HIGH` лежит в `ViPER_commits_raw.tsv`; ниже — все, что попало в детальные карточки,
плюс группировка остальных. «Стоимость» — оценка объёма переноса в target (S ≤ 30 строк, M ≤ 150,
L ≤ 500, XL — новая подсистема).

| SHA | Кат | Что делает код (по diff) | Target | Стоимость | Карточка |
|---|---|---|---|---|---|
| `8d01ece` | A | tubeSat-асимметрия + `postgain = 1/pow(pregain, 0.6)`, кламп 12→24 дБ | NEW/дефект | S | K1 |
| `24fa885` | A,B | crossfeed: условие пересборки `(!S_S && !T_S)` вместо `||` | NEW/дефект | S | K2 |
| `1f5151f` | B | deadlock: `*Enable` сами берут нерекурсивный мьютекс, а `SetSampleRate` держит его | NEW/дефект | M | K2/K6 |
| `d3a15fb` | A | `CrossfeedSetMode` под локом + `memset` состояния, чтение mode один раз | NEW/дефект | S | K2 |
| `2ed7170` | A,G | `dbb.c`: `pos >= factor` вместо `==` + сброс фазы при смене частоты | NEW/дефект | S | K3 |
| `95f6844` | A,G | `vdc.c`: границы секции по `sosCount*5`, подсчёт чисел внутри секции | NEW/дефект | S | K4 |
| `9772580` | A,G | `selectConvPartitions`: `type_best = 1`, `s = min(s, MAX_BENCHMARK-1)` | NEW/дефект | S | K5 |
| `d8d0c49`, `0cf7d75` | A | `unsigned remaining` не должен оборачиваться; сегментная согласованность | PARTIAL (в source закрыто 4 из 12 мест) | S | K7 |
| `88744ab` | B,G | `CompressorProcess` + JNI/HAL-путь под мьютексом (пересборка FFTCompander) | NEW/дефект | S | K6 |
| `bb80913` | A,B,G | mEQ-интерполяция и arbEq-parser/phase/enable под мьютексом | NEW/дефект | S | K6 |
| `57ce68f` | A,B | `SetSampleRate` переселективует DDC-коэффы и re-init констант compander | NEW | M | K8 |
| `ed58602` | A,B | `StereoEnhancementDesign()` вне лока на смену частоты + isfinite-гарды | NEW | S | K8 |
| `—` (JNI-контур) | B | `setSamplingRate(..., false)` из приложения | NEW/дефект | S | K8 |
| `d523a8d`, `50276f8` | A | drop non-finite sample перед limiter'ом; isfinite в DBB-обоих каналах | NEW | S | K9 |
| `8ff9e05` | C,E | `NO_SONAME` + `-Wl,-soname,libjamesdsp.so`; restricted-uid в allowlist | NEW | S | K10 |
| `4293c71` | F,G | `CrashGuard.cpp`: sigaction SIGSEGV/BUS/FPE/ILL/ABRT, pc/lr/sp/x0 + карты | NEW | M | K11 |
| `d69429b`, `b980c22` | C,B | allowlist `addMatchingUid` вместо `excludeUid`; silence-gate на `track.stop()` | NEW | M | K12 |
| `6a35e1e`, `100dc32` | A,B | enum `JdspEffectId` + `chainOrder[]` + диспетчер; дописывание пропущенных ID | NEW | L | K13 |
| `7c61e8a`, `306a7b2` | A,B | `ArbitraryResponseEqualizerSetPhaseMode` (min/linear); per-channel PEQ | NEW | M/L | K14 |
| `16d98c0`, `f8c4d12`, `9190730`, `c35c9b1`, `a0918f0`, `f8aa719`, `25fa073`, `c96fda5`, `b8d7d94`, `536c533`, `54ca557`, `a5d21b6`, `29d5565`, `20ea4a5`, `d8c333a`, `8a22fdb`, `09531eb` | A,G | новые DSP-эффекты: maximiser, multiband distortion, Dattorro/FDN-Hall/Room reverb, granular + phase-vocoder pitch shift, vinyl noise, transient shaper, multiband imaging, exciter, low-end, balance, treble/bass exciter, hpsurround v2, ViPER Dynamic System | NEW (в target этих файлов нет) | XL | K15 |

**Мелкие, но дешёвые и полезные (S, вне карточек):** `33a4810` gradle-задача `verifyLaunchAllowlist`
(decode base64 из исходников и проверить, что ни один пакет не «потерялся»); `3dcf3b2`/`9c55dc1`
согласованность дефолтов C-init ↔ Kotlin ↔ XML + тест `checkDefaultsAreAudible`; `5672330` `perf_harness`
(ns/sample и метод замера denormal-stall); `02ef76b` устранение `MODE_MULTI_PROCESS` (в target 10 мест);
`9c55dc1` `SwitchPreferenceGroup.childVisibilityFilter`; `15b4a99` `EffectLayoutManager.reload` после пресета;
`12f1481` try/catch вокруг dispatch секции настроек + Toast; `d7b9da3` ключи preference'ов карточек.

---

## 3. Детальные карточки

Формат: проблема → решение (код source с путями) → состояние target → план переноса → лицензия → приёмка.

Пути в source — относительно `C:\dev\RootlessViPER4Android\`, в target — относительно
`C:\dev\Loundess-PEQ-RootlessJamesDSP\`. Префикс C-движка в обоих одинаковый:
`app\src\main\cpp\libjamesdsp\Main\libjamesdsp\jni\jamesdsp\jdsp\` (далее `…jdsp\`).

---

### K1. Vacuum tube: эффект, который ничего не делает — `8d01ece` (A, S)

**Проблема.** `postgain = 1/pregain` даёт ровно `x → y` (drive и makeup сокращаются), остаётся лишь
пропадающий член амплитуды в квадрате. Плюс кламп 12 дБ отсекает верх диапазона ручки.

**Решение source.**
`…jdsp\Effects\vacuumTube.c:12-17`

```c
static inline double tubeSat(double x)
{
	if (x >= 0.0)
		return x / (1.0 + 0.85 * x);
	return x / (1.0 - 0.55 * x);
}
```

`…vacuumTube.c:84-85` — насыщение применяется **до** децимации (в oversampled-домене):

```c
upsample[0][j] = (float)tubeSat(bandCh1[0] + (harmonic2Ch1 + harmonic3Ch1 + harmonic4Ch1 + harmonic5Ch1) * 0.2 + allpassCh1 + bandCh1[5]);
upsample[1][j] = (float)tubeSat(bandCh2[0] + (harmonic2Ch2 + harmonic3Ch2 + harmonic4Ch2 + harmonic5Ch2) * 0.2 + allpassCh2 + bandCh2[5]);
```

`…vacuumTube.c:131-141` — частичная, не полная компенсация:

```c
void VacuumTubeSetGain(JamesDSPLib *jdsp, double dbGain)
{
	if (dbGain > 24.0)
		dbGain = 24.0;
	...
	jdsp->tube.pregain = db2magf(dbGain);
	jdsp->tube.postgain = (float)(1.0 / pow((double)jdsp->tube.pregain, 0.6));
}
```

**Target (подтверждено).** `…jdsp\Effects\vacuumTube.c:115-123`:

```c
void VacuumTubeSetGain(JamesDSPLib *jdsp, double dbGain)
{
	if (dbGain > 12.0)
		dbGain = 12.0;
	...
	jdsp->tube.postgain = 1.0f / jdsp->tube.pregain;
}
```

Гармоники складываются без насыщения — `…vacuumTube.c:72-73` (oversampled-путь) и `:101`
(не-oversampled-путь). Вызов из JNI — `…jni\jamesdsp\jamesdsp.c:417` (`VacuumTubeSetGain(..., tubedrive)`).

**План переноса.**
1. Скопировать `tubeSat()` как `static inline` в начало файла target.
2. Обернуть выражения `…vacuumTube.c:72` и `:73` в `tubeSat(...)`; для `:101` — решить, нужен ли гард
   (в source не-oversampled путь тоже проходит насыщение; уточнить по `VTProcess`-структуре).
3. Заменить `1.0f / jdsp->tube.pregain` на `pow((double)jdsp->tube.pregain, -0.6)`.
4. Кламп 12.0 → 24.0. **Проверить диапазон слайдера в res/xml** — если UI даёт только ±12 дБ,
   синхронизировать `android:max`/`app:precision` с новой границей, иначе change не достигнет пользователя.

**Лицензия.** GPL-3.0 → GPL-3.0, производная работа. Section 5 (attribution) в `NOTICE`: добавить запись
`Vacuum tube saturation correction — ported from RootlessViPER4Android (Copyright (c) alienware377 and
contributors)` по образцу уже существующей записи `NOTICE:134-141`. Это **не новый лицензионный режим** —
прецедент в файле есть.

**Приёмка.**
- юнит/хост: синус 1 кГц, `tubedrive = +18 дБ` → спектр на выходе должен содержать 2-ю и 4-ю гармоники
  (асимметрическая кривая даёт чётные), при `tubedrive = 0` — идентичность (±0.01 дБ);
- on-device (OnePlus MT2111): A/B-переключение «Vacuum tube» на музыкальном файле — эффект слышим,
  громкость не «складывается» вдвое; лог `logcat` не даёт clipping-предупреждений.

---

### K2. Crossfeed: четыре независимых бага в одном файле — `24fa885`, `1f5151f`, `d3a15fb` (A,B / M)

**Проблема 1 — пересборка на каждый блок.** Target требует наличия **обоих** длинных конволверов:
`…jdsp\Effects\crossfeed.c:47`

```c
if (jdsp->crossfeedForceRefresh || !jdsp->advXF.conv[0] || ... || !jdsp->advXF.convLong_S_S || !jdsp->advXF.convLong_T_S)
```

Но собирает он только `convLong_S_S` (`…crossfeed.c:70-72` в source-логике; в target — аналогичный путь),
поэтому условие вечно истинно и 4 конволвера пересоздаются в каждом вызове `Enable`.

Source-формулировка, `…Effects\crossfeed.c:55`:

```c
if (jdsp->crossfeedForceRefresh || !jdsp->advXF.conv[0] || !jdsp->advXF.conv[1] || !jdsp->advXF.conv[2] || (!jdsp->advXF.convLong_S_S && !jdsp->advXF.convLong_T_S))
```

**Проблема 2 — гонка по `advXF.mode` с порчей памяти.** Target читает `mode` трижды, в том числе на каждый
сэмпл (`…crossfeed.c:109`, `:117`, `:121-122`):

```c
BS2BProcess(&jdsp->advXF.bs2b[jdsp->advXF.mode], &tmpL, &tmpR);   /* target :117 */
else if (jdsp->advXF.mode < 5)
	FFTConvolver2x4x2Process(jdsp->advXF.conv[jdsp->advXF.mode - 2], ...);   /* target :121-122 */
```

`bs2b[]` — 2 элемента, `mode` принимает 0…5. Если с трда настроек `mode` меняется на 2…4 посреди блока,
цикл продолжает обращаться к `bs2b[2..4]` и **пишет состояние фильтров поверх последующих полей DDC и
convolver**. Source фиксирует это комментарием и одним снимком значения,
`…Effects\crossfeed.c:120-141`:

```c
const int mode = jdsp->advXF.mode;   /* read once */
if (mode < 2) { ... BS2BProcess(&jdsp->advXF.bs2b[mode], &tmpL, &tmpR); ... }
else if (mode < 5) FFTConvolver2x4x2Process(jdsp->advXF.conv[mode - 2], ...);
```

**Проблема 3 — сеттинг mode без лока и без сброса состояния.** Source (`…crossfeed.c:100-117`):

```c
	if (nMode < 0) nMode = 0;
	if (nMode > 5) nMode = 5;
	/* Under the lock: CrossfeedProcess reads the mode and the BS2B state on
	   the audio thread. Not to be called with the lock held. */
	jdsp_lock(jdsp);
	if (nMode < 2)
	{
		memset(&jdsp->advXF.bs2b, 0, sizeof(jdsp->advXF.bs2b));
		... BS2BInit(&jdsp->advXF.bs2b[1], ...);
	}
	jdsp->advXF.mode = nMode;
	jdsp_unlock(jdsp);
```

**Проблема 4 — рекурсивный лок.** Target уже вызывает `jdsp_lock` внутри `CrossfeedEnable`
(`…crossfeed.c:46`), а `JamesDSPSetSampleRate` берёт тот же **нерекурсивный** мьютекс
(`…jdsp\jdspController.c:1226`; `pthread_mutex_init(&jdsp->m_in_processing, NULL)` — `…jdspController.c:1088`)
и из-под него зовёт `CrossfeedEnable` в блоке `forceRefresh` (`…jdspController.c:1266-1280`). Это гарантированный
self-deadlock при `forceRefresh = 1`. (Сейчас не срабатывает только потому, что из приложения
`forceRefresh` никогда не `true` — см. K8; при переносе K8 это станет активным.)

**План переноса.**
1. `…crossfeed.c:47` — `||` → `&&` в паре условий `convLong_*` (формулировка source-строки 55).
2. `CrossfeedProcess` — снять `mode` в локальную константу один раз, использовать её во всех трёх местах.
3. `CrossfeedSetMode` — клампы 0…5, `jdsp_lock`/`jdsp_unlock`, `memset` состояния при `mode < 2`.
4. Убрать `jdsp_lock`/`jdsp_unlock` из `CrossfeedEnable` и вместо этого сделать контракт
   «*Enable/SetMode вызываются либо без лока, либо через `*ForceRefresh` + отдельный drain-путь» — как в
   source; обязательно синхронизировать с правкой `JamesDSPSetSampleRate` (K6/K8), иначе deadlock останется.

**Лицензия.** Как K1: запись в `NOTICE` (GPL-3.0, RootlessViPER4Android lineage). Патч в `bs2b.c`
при затронутых строках требует проверки — в target `bs2b.c` **не имеет license-заголовка**, см. §5 U-5.

**Приёмка.**
- unit-host harness: заполнить `advXF` несущими значениями маркерами, вызвать `CrossfeedSetMode` из второго
  треда, одновременно гоняя `CrossfeedProcess` 10⁶ сэмплов, затем проверить canary-байты в структурах,
  идущих после `bs2b[]` (analog `-Wl,--wrap=calloc` guard-bytes из practice source);
- on-device: переключение 6 режимов crossfeed во время воспроизведения — без слышимых щелчков и без
  рестарта ауди-сервиса; `perf` — отсутствие аллокаций в steady-state (assert на count `malloc` за 60 с).

---

### K3. `dbb.c`: heap OOB при переходе 48 → 44.1 кГц — `2ed7170` (A,G, S)

**Проблема.** Target, `…jdsp\Effects\dbb.c:327-328`:

```c
		dbb->originalBuf[pos] = (x1[framePos] + x2[framePos]) * 0.5f;
		pos++;
		if (pos == dbb->downsampler.factor)
```

После смены частоты `factor` может стать **меньше** текущей фазы `pos` (48→44.1 кГц), тогда `==` никогда не
срабатывает, `pos` растёт, а `originalBuf` — это фиксированные 960 элементов
(`…jdsp\jdsp_header.h:232` — `float originalBuf[960];`). Запись за конец буфера в реальном ауди-потоке.

**Решение source.** `…Effects\dbb.c:336-342` + сброс при пересборе даунсемплера:

```c
	int pos = dbb->downsamplerPos;
	...
		if (pos >= dbb->downsampler.factor)   /* source :342 */
```

и явный `pos = 0` (`source :431`) в пути сброса; при смене частоты фаза и буфер обнуляются.

**План переноса.** `==` → `>=`; в функции обновления даунсемплера/`BassBoostSetParam` (которая трогает
`dbb->downsampler`) добавить `dbb->downsamplerPos = 0` и `memset(originalBuf, …)` — ровно как в source.
Никакого UI/schema-изменения не требуется.

**Лицензия.** K1-образец, запись в `NOTICE`.

**Приёмка.** юнит: заглушить `downsampler.factor = 1`, подать 5000 сэмплов, проверить, что `pos` остаётся в
`[0, factor)`; on-device: проигрывание с динамическим переключением выхода 48k→44.1k (или ASRC-путь) —
отсутствие SIGSEGV; отдельно — ASAN-сборка хост-харнеса (см. K-тесты `tools/*_harness.c` у source).

---

### K4. `vdc.c`: неограниченный парсер DDC-строки — `95f6844` (A,G, S)

**Проблема.** Target `…jdsp\Effects\vdc.c:50-52, 63`:

```c
	int numberCount = (countChars(fs48, ',') + 1);   /* считает до конца строки, а не до конца секции */
	int sosCount = numberCount / 5;
	...
	while (counter < numberCount)                     /* парсит больше чисел, чем мест в массиве */
```

Выделяется `sosCount` структур `DirectForm2`, а цикл идёт до `numberCount`. Если чисел не кратно 5
(хвостовая запятая, шестое число в строке), запись уходит за конец массива.

**Решение source** — `…Effects\vdc.c:55-68`:

```c
	char *end = DDCString + strlen(DDCString);
	char *end441 = fs48 > fs44_1 ? fs48 : end;
	char *end48  = fs44_1 > fs48 ? fs44_1 : end;
	int numberCount = 1;
	for (char *c = fs48; c < end48; c++)
		numberCount += *c == ',';
	int sosCount = numberCount / 5;
	if (!sosCount)
		return 0;
	// Parse exactly sosCount * 5 numbers per section...
	int limit = sosCount * 5;
```

**План переноса.** Перенести расчёт границы секции (`end441`/`end48`) и `limit = sosCount * 5`; заменить условие
циклов на `counter < limit`; для второй секции — симметрично через `end441`. Массив 446 `.vdc`-файлов в
target не меняется: парсер обязан стать строже, а не изменить формат.

**Лицензия.** K1-образец.

**Приёмка.** юнит-тест парсера: корректная строка → N/SOS; строка с хвостовой запятой и с «шестым числом» →
без OOB (guard-bytes); fuzz-прогон по всем 446 `.vdc` из ассета — ни одного отклонения от сегодняшнего
результата для валидных файлов.

---

### K5. `selectConvPartitions`: неинициализированное значение и выход за таблицы — `9772580` (A,G, S)

**Проблема.** Target `…jdsp\jdspController.c:185-196`:

```c
	int type_best, begin_m, end_m;
	int s = (int)log2(latency) - 6;
	...
	end_m = MAX_BENCHMARK - s;
```

`type_best` может остаться неинициализированным (если цикл ни разу не улучшил `cpu_load_best` и одно-сегментная
ветка тоже не улучшит) — а返回值 используется как индекс типа конвуции. `MAX_BENCHMARK = 10`
(`…jdsp\jdsp_header.h:12`, одинаково в обоих репозиториях), таблицы `convbench_c0/c1[MAX_BENCHMARK]`
(`…jdspController.c:122-123`); при `latency = 65536` `s = 10` → чтение за концом обеих таблиц (и HAL может
дать такой блок).

**Решение source** — `…jdspController.c:184-199`:

```c
	// Starts as the single-segment type, so a loop that never runs, or a
	// prediction that is NaN or huge, cannot return an uninitialised value.
	int type_best = 1, begin_m, end_m;
	...
	// The benchmark tables have MAX_BENCHMARK entries, for blocks of 64 up to
	// 32768. A 65536-frame block ... read one past both tables. Use the largest
	// entry for anything bigger.
	if (s > MAX_BENCHMARK - 1)
		s = MAX_BENCHMARK - 1;
```

**План переноса.** Две строки в target-функцию (та же сигнатура `…jdspController.c:178-` — проверить имя и
порядок аргументов перед правкой). Дополнительно стоит вынести `s + m` в отдельную проверку: в source
остается `convbench_c0[s + m]` при `m < end_m = MAX_BENCHMARK - s`, то есть `s + m ≤ MAX_BENCHMARK - 1` —
после клампа корректно.

**Лицензия.** K1-образец.

**Приёмка.** юнит: параметрический прогон `blockSize ∈ {64 … 131072}` — возврат всегда ∈ {1,2}; клампы
valgrind/ASAN на индексах таблиц; on-device: блок 8192 (верх UI-диапазона) и не-степень-2 размер — без
изменения порядка «лучшего типа» относительно текущей сборки (иначе — зафиксировать как улучшение).

---

### K6. Блокировки в цепи: `CompressorProcess`, mEQ, arbEq, `SetSampleRate` deadlock — `88744ab`, `bb80913`, `7a6b9cf`, `1f5151f` (B, M)

**Проблема.** `…jdspController.c:325-337` (target):

```c
	if (jdsp->compEnabled)
		CompressorProcess(jdsp, n);     /* :326 — ВНЕ лока */
	...
	if (jdsp->arbitraryMagEnabled)
		ArbitraryResponseEqualizerProcess(jdsp, n);
	jdsp_lock(jdsp);                    /* :337 — лок начинается только здесь */
```

Параллельно тред настроек доходит до `CompressorSetParam` → `FFTCompanderInit` (`…Effects\dynamic.c:1616, 1621`),
который пересобирает transform/lengths/windows/**функцию-указатель**. Блок, идущий через наполовину
построенное состояние, читает за пределы по новому размеру (source-комментарий: «read gigabytes out of
bounds»). Тот же класс у mEQ: `…Effects\multimodalEQ.c:147-149` — `memcpy` массивов `freq/gain` без лока,
в то время как `MultimodalEqualizerEnable` (`…multimodalEQ.c:234`) их пересобирает.

Дополнительно `7a6b9cf` (PARTIAL): при `Disable` сначала сбрасывать флаг enabled, затем `lock/unlock` —
drain «в полёте» блока, и только потом освобождать память.

**Решение source.** Фрагмент `…jdspController.c:496-545` (в source цепь — switch-диспетчер):

```c
	case JDSP_EFX_COMPRESSOR:
		// Locked: a settings change re-runs FFTCompanderInit on this state
		// (transform, lengths, windows, function pointer), and a block
		// running on the half-built result jumped through a stale pointer
		// with the new lengths and read gigabytes out of bounds.
		jdsp_lock(jdsp);
		if (jdsp->compEnabled) CompressorProcess(jdsp, n);
		jdsp_unlock(jdsp);
		break;
	case JDSP_EFX_PITCHSHIFT:  jdsp_lock(jdsp); ... jdsp_unlock(jdsp); break;
	case JDSP_EFX_DIFFSURROUND: jdsp_lock(jdsp); ... jdsp_unlock(jdsp); break;
	case JDSP_EFX_EQUALIZER:
		// Both EQs locked: their convolvers are rebuilt and refreshed from
		// the settings thread, and a block must not run through them halfway.
		jdsp_lock(jdsp);
		if (jdsp->equalizerEnabled) MultimodalEqualizerProcess(jdsp, n);
		jdsp_unlock(jdsp);
		break;
	case JDSP_EFX_ARBITRARYMAG: jdsp_lock(jdsp); ... jdsp_unlock(jdsp); break;
```

**План переноса (без перестройки цепи - минимальный шаг).**
1. В существующей линейной цепи target обернуть `CompressorProcess`, `MultimodalEqualizerProcess`,
   `ArbitraryResponseEqualizerProcess`, `PitchShiftProcess` (если есть), `DiffSurroundProcess` (если есть)
   в `jdsp_lock`/`jdsp_unlock` — отдельными парами, а не расширением уже существующего блока `:337`.
2. Проверить, что ни один из этих process не зовёт функцию, которая сама берёт лок
   (иначе — deadlock на нерекурсивном `m_in_processing`).
3. Перенести контракт «Enable/SetMode — вызывающий держит лок; SetSampleRate — не держит» и убрать
   `jdsp_lock` из `CrossfeedEnable`/`ArbitraryResponseEqualizerEnable`/`MultimodalEqualizerEnable`/
   `CompressorEnable`/`DDCEnable`, добавив взятие мьютекса на уровне JNI-обёртки. Без этого K8 включит deadlock.
4. `7a6b9cf`: порядок в каждом `*Disable` — флаг → lock/unlock → free.

**Стоимость/риск.** L по числу мест, но каждый шаг локальный и рецензируемый; обязателен стресс-тест
(см. приёмку), иначе возможна тихая деградация по CPU.

**Приёмка.** Стресс: тред A гоняет `JamesDSPProcess` 5 минут, тред B каждые 20 мс меняет compander
transform / mEQ axis / crossfeed mode / arbEq phase; ASAN + halt-on-heap-overflow. Критерий — 0 ошибок и
0 рестартов сервиса. На MT2111 — отдельный прогон в Low-Latency Mode (`QueueController` активен).

---

### K7. `FFTConvolver`: unsigned-обёртка `remaining` — `d8d0c49`, `0cf7d75` (A, S; в source закрыто лишь частично)

**Проблема.** 12 мест в target вычисляют `irLen - i * blockSize` как `unsigned` без проверки, что
`i * blockSize < irLen`: `…Effects\eel2\numericSys\FFTConvolver.c:459, 507, 620, 640, 660, 680, 816, 836, 889,
907, 1009, 1029`. При несовпадении числа сегментов и длины ИР получается огромное «остаток»-значение →
копирование за пределы сегмента.

**Решение source (важный нюанс).** В source гард проставлен **только в 4 из 12** мест —
`…FFTConvolver.c:487, 535, 920, 938`:

```c
const unsigned int remaining = i * conv->_blockSize >= irLen ? 0 : irLen - (i * conv->_blockSize); // zero, not a wrap, past the end
```

Остальные sites в source остались без гарда. Значит: брать **идиому**, а не слепо копировать файл, и
применить её ко всем 12 местам target — это строго лучше, чем source.

Связанное решение — `0cf7d75`/`arbLoadFilters` (`…Effects\arbEqConv.c:78-81`): перед refresh проверять
`(len + bs - 1) / bs == convState._segCount`, иначе загружать целиком. В target такой проверки нет:
`…Effects\arbEqConv.c:26-30` зовёт `FFTConvolver2x2RefreshImpulseResponse(...)` безусловно.

**План переноса.** 12 замен по строчке + гард согласованности сегментов в
`ArbitraryResponseEqualizerStringParser`/`Enable`. UI/schema не затрагиваются.

**Лицензия.** Файл `FFTConvolver.c` в target **не имеет license-заголовка** (в отличие от уже помеченных
`dynamicEq.c`). Перед портированием определить происхождение файла (см. §5 U-5); если он из EEL/`libjamesdsp`
upstream — запись в `NOTICE` только для портированных строк ViPER.

**Приёмка.** юнит: ИР длиной `blockSize*k ± 1` во всех 4 вариантах конволвера; фазовый переключатель
min↔linear 100 раз подряд (см. K13) — guard-bytes чистые.

---

### K8. Rate-dependent redesign никогда не выполняется — `57ce68f`, `ed58602`, `1e19ee3` + JNI-контур (B, M)

**Проблема (главный «системный» пункт).** Цепочка в target:

- `app\src\main\java\me\timschneeberger\rootlessjamesdsp\interop\JamesDspLocalEngine.kt:23` —
  `JamesDspWrapper.setSamplingRate(handle, value, false)`;
- `interop\JamesDspWrapper.kt:27` — `external fun setSamplingRate(self, sampleRate, forceRefresh)`;
- `app\src\main\cpp\...\jamesdsp.c:195` тоже передаёт `0`; `forceRefresh = 1` встречается только в
  `Main\CLI\main.c:283`.

Итог: блок `…jdspController.c:1266-1280` (пересчет BassBoost/DDC-коэффов, arbEq, mEQ, Compressor,
`StereoEnhancementRefresh`) из приложения **не вызывается никогда**. Всё, что спроектировано под 48 кГц,
остаётся спроектированным под 48 кГц при 44.1/96/192 кГц.

**Решение source.**
- `57ce68f`: в `JamesDSPSetSampleRate` безусловно переселективовать DDC-коэффициенты по новой частоте и
  re-init константы времени compander (это **не** требует `forceRefresh`).
- `ed58602`: `StereoEnhancementDesign()` — lock-free, вызывается на смену частоты; плюс isfinite-гарды.
- `1e19ee3` (**PORTED** — уже в target): архитектура «design-часть в `Refresh()`, вызываемом снаружи
  `forceRefresh`». Проверить перед работой, какие `Refresh` в target действительно подключены.

**План переноса.**
1. Разделить «дешёвое, всегда нужное» (DDC-пер-select, compander τ, stereo widening design) от «дорогого»
   (полная пересборка конволверов) — первые вызывать безусловно, как в source.
2. Только после K6.3 (снятие лока с `*Enable`) рассмотреть передачу `forceRefresh = true` при
   `ACTION_SAMPLE_RATE_UPDATED` из `RootlessAudioProcessorService`; иначе — включить намеренно и
   зарепортировать deadlock как известный блокатор.
3. `fd2c634`: на `ACTION_SAMPLE_RATE_UPDATED` делать `clearCache()` + `syncWithPreferences()` **для всех
   карточек** (сейчас только convolver-путь) — иначе UI расходится с DSP.

**Приёмка.** замер на MT2111: при 44.1 кГц проверить, что DDC выбирает SR_44100-набор (лог/гребень
коэффициентов), compander τ пересчитаны; субъективно — тон-блоу 44.1-кГц материал с DDC не звучит «не туда».
Отдельный regression-тест: `setSamplingRate` при активных 12 эффектах — время восстановления < заданного
порога и без glitch (запись через `AudioRecord` loopback).

---

### K9. Отсутствие `isfinite`-гардов в основной цепи — `d523a8d`, `50276f8`, `f9b3f4e`, `ed58602` (A, S)

**Проблема.** Один NaN/Inf (от переполнения, от poisoned-памяти, от деления на 0 в design-коде) в
`tmpBuffer` распространяется по всей цепи и остаётся там навсегда: фильтры хранят состояние, envelope'ы
гладят. В target гарды есть только в уже портированных standalone-модулях; в `JamesDSPProcess` их нет.

**Решение source (типичные формулировки).**
- `d523a8d`: после postGain, **до** limiter — сброс не-конечного сэмпла в 0;
- `50276f8`: `DBBProcess` — проверка обоих каналов, иначе NaN отравляет анализ, smoothed boost и оба
  состояния фильтров;
- `7a6b9cf`/`ed58602`: при записи не-конечного в состояние SVF — обнулить состояние, а не оставить NaN;
- `80f9faf` (смежное): envelope flush-to-zero при < 1e-20 — на x86 субнормальные операции в 90–250 раз
  медленнее (актуально и для ARM, но эффект меньше).

**План переноса.** Точечно, в том же порядке, что source: перед limiter'ом в main chain; в DBB analysis;
в каждом envelope/one-pole с sticky-состоянием. Это ~15 строк, но они должны быть **после** K6, чтобы
не маскировать баги «NaN из-за гонки».

**Лицензия.** K1-образец.

**Приёмка.** юнит: входной блок с одним NaN → через ≤1 блок выход конечен; тест на denormal: silence-after-
loud замер по методу `5672330`.

---

### K10. Soname / упаковка HAL-библиотеки — `8ff9e05` (C,E, S)

**Проблема.** Target: CMake-цель называется `jamesdsp_effect` (`app\src\main\cpp\CMakeLists.txt:60`),
артефакт `libjamesdsp_effect.so`; комментарий `:73-74` говорит переименовать при деплое. Магisk-сборщик
действительно стейджит файл как `libjamesdsp.so`
(`magisk-module\build-module.sh:101-122` → `DEST="$STAGING/libs/$ABI/libjamesdsp.so"`),
и `magisk-module\src\common\patch_audio_config.sh:109, 121, 187` регистрируют **`libjamesdsp.so`**.
Но ELF-soname остаётся `libjamesdsp_effect.so`, а **bionic регистрирует библиотеку по soname**, а не по пути
загрузки — аудиоэффект-хост получает несовпадение имени.

**Решение source.** `CMakeLists.txt:96-104`:

```cmake
# bionic registers a library under its soname rather than the path it was
# opened from, so the soname has to say so. CMake derives its own -soname from
# the target name ...
set_target_properties(jamesdsp_hal PROPERTIES NO_SONAME TRUE)
target_link_options(jamesdsp_hal PRIVATE
        -Wl,-soname,libjamesdsp.so)
```

**План переноса.** Для цели, попадающей в модуль: `NO_SONAME TRUE` + явный `-Wl,-soname,libjamesdsp.so`
(или `set_target_properties(... OUTPUT_NAME "jamesdsp")`, если переименование в сборщике тогда можно
убрать — выбрать один механизм, не два). Проверить `readelf -d` на собранном `.so`: `NEEDED`/`SONAME` =
`libjamesdsp.so`.

**Лицензия.** Build-инженерия, не DSP — NOTICE-запись не требуется (кроме случаев дословного переноса
комментариев/скриптов — тогда общая GPL-заметка о происхождении).

**Приёмка.** На устройстве: `adb shell auditd`/`avc: denied` отсутствуют; `dumpsys media.audio_flinger | grep
jamesdsp` показывает эффект; регресс — сборка модуля без ручного переименования.

---

### K11. Crash-диагностика: native sigaction + JVM handler + breadcrumbs — `4293c71` и смежные (F,G, M)

**Состояние target.** `CrashGuard.*` — ни одного файла в репозитории (проверено `find`), `UncaughtExceptionHandler` —
0 совпадений. Native-падение ауди-сервиса сейчас даёт только «сервис перезапустился».

**Что делает source.** `CrashGuard.cpp`: `sigaction` на SIGSEGV/SIGBUS/SIGFPE/SIGILL/SIGABRT, дамп
`pc/lr/sp/x0` + фильтрованный `/proc/self/maps` (только строки, относящиеся к библиотекам приложения /
`libjamesdsp`), кольцевой breadcrumb-лог, heartbeat каждые 2000 вызовов процесса, durable
`crash_history.txt` + JVM-side handler.

**План переноса.**
1. Новый `.cpp`/`.h` в `app/src/main/cpp/` + включение в `add_library(jamesdsp_effect ...)` список
   (`app\src\main\cpp\CMakeLists.txt:60-70`) и `target_link_libraries`.
2. Инициализация из `JNI_OnLoad`; регистрация JVM handler из `Application`/сервиса.
3. Хранить логи там же, где `PreferenceCache` хранит диапностики (выбрать один путь, не плодить второй
   механизм).
4. UI: карточка «Диагностика» со списком crash'ов (в source это `F`-часть — в target можно отложить).

**Лицензия.** GPL-3.0, NOTICE-запись (код, а не build-скрипт).

**Приёмка.** На MT2111: искусственно вызвать SIGSEGV (тестовый JNI-экспорт, убрать перед релизом) → файл
`crash_history.txt` содержит pc/lr/sp/x0 и минимум 5 строк карт; сервис поднимается автоматически;
`adb logcat -b crash` не остаётся пустым.

---

### K12. Allowlist-сессии и silence-gate в rootless-захвате — `d69429b`, `8ff9e05`, `b980c22` (C,B, M)

**Состояние target.** `app\src\main\java\...\service\RootlessAudioProcessorService.kt:1399` —
`excluded.forEach { configBuilder.excludeUid(it) }`; `addMatchingUid` и `allowedUids` в репозитории
не встречаются (проверено grep'ом). Есть «silence gate» по данным `docs\PROCESSING_MODES.md`? — в
`RootlessAudioProcessorService.kt:597` присутствует порог `1e-7f` для `floatBuffer`, но нет остановки
трека по длительному цифровому молчанию.

**Что даёт source.**
- `d69429b`: инверсия модели — вместо «заглушить всех, кроме…» whitelist-режим через `addMatchingUid`
  (API **запрещает** одновременно include- и exclude-фильтры), с полем `allowedUids`;
- `8ff9e05`: restricted-uid **тоже** должен фильтроваться, иначе rootless-заглушение этого приложения =
  полная тишина для пользователя;
- `b980c22`: 20 с цифрового молчания (порог `1e-7`) → `track.stop()`, пропуск DSP и записи, захват
  продолжается, мгновенный restart при появлении сигнала.

**План переноса (порядок важен).**
1. Схема: новая pref-ключ + `PreferenceCache`-поле `allowedUids` (namespace по образцу существующих
   allowlist-настроек); миграция — при отсутствии ключа поведение остаётся `excludeUid` (обратная
   совместимость не ломается).
2. `RootlessAudioProcessorService.kt:1390-1405` — ветвление include/exclude; явная проверка и лог при
   попытке смешать оба (запрет API).
3. Restricted-uid: фильтровать `UserManager`-restricted-пакеты в обоих режимах.
4. Silence-gate: счётчик последовательных «молчаливых» блоков у `:597` → `track.stop()`/`track.play()`.

**Лицензия.** GPL-3.0, NOTICE-запись (логика не тривиальна).

**Приёмка.** На MT2111: allowlist-режим — звук обрабатывается только у выбранных приложений, у
restricted-uid приложения **не** наступает тишина; тишина 25 с → `dumpsys media.audio_flinger` показывает
stopped track, воспроизведение сразу восстанавливается; A/V-синхронизация в YouTube не ухудшается
(метрика — сравнение с `docs\PROCESSING_MODES.md` диапазоном Standard Mode).

---

### K13. Порядок DSP-цепи как данные — `6a35e1e`, `100dc32` (A,B, L)

**Что делает source.** Жёстко зашитая цепь заменена enum `JdspEffectId` + `int chainOrder[JDSP_EFX_MAX]`
(`…jdsp_header.h:956` `#define JDSP_EFX_MAX 48`, `:1207` поле, `:998` `JDSP_EFX_COUNT`) и
`jdspDispatchEffect()`-switch'ем (`…jdspController.c:488-…`). `100dc32`: `JamesDSPSetChainOrder` **дедуплицирует**
и **дописывает пропущенные** ID — иначе сохранённый пользователями порядок молча теряет каждый новый эффект.

**Почему это X-фактор в target.** Это же решение делает K6 дешёвым (лок становится атрибутом одного case),
позволяет реализовать V4A-порядок (`6152d9b`), и снимает класс багов «новый эффект добавлен в цепь, но не
в сохранённый порядок».

**План переноса.**
1. Добавить enum + поле в `jdsp_header.h` target (не копируя source-struct целиком — у target другой набор
   эффектов, включая `peq_loudness.c`).
2. Переписать `JamesDSPProcess` target на диспетчер по `chainOrder`, сохранив **текущий порядок вызовов**
   как default — иначе это будет audible-изменение, а не рефакторинг.
3. `setChainOrder` JNI + Kotlin-обёртка + `PreferenceCache` (массив int; сериализация как в source).
4. Миграция: существующие пресеты не содержат порядка → применить default; дефолт = текущий порядок.
5. UI-карточка — отложить (категория F, низкий приоритет).

**Риск.** Высокий: трогается ядро. Требует побайтовой сверки выходного сигнала «до/после» на фиксированном
входе при всех эффектах выключенных и при каждом эффекте по отдельности.

**Лицензия.** GPL-3.0, NOTICE.

**Приёмка.** golden-file: один и тот же WAV через старую и новую сборку → макс. разница ≤ 1e-6 (без
аудиодушного сравнения); тест дедупа: порядок с дублем и с пропуском 5 эффектов → восстановленная цепь
полная; on-device smoke: музыка + `logcat` без рестартов 30 мин.

---

### K14. FIR-эквалайзер: линейная/минимальная фаза и пер-канал — `7c61e8a`, `306a7b2`, `0cf7d75` (A,B, M/L)

**Состояние target.** `…Effects\arbEqConv.c:9` — `InitArbitraryEq(&jdsp->arbMag.instance.coeffGen, 0)`:
режим фазы зашит (0), функции `ArbitraryResponseEqualizerSetPhaseMode` нет,
`ArbitraryResponseEqualizerStringParserStereo` нет, вызов parser всегда использует один и тот же `eqFil`
для обоих каналов (`…arbEqConv.c:28-29`).

**Что делает source.** `7c61e8a` — `SetPhaseMode(min|linear)` с корректным `EqNodesFree` + повторным
`InitArbitraryEq(coeffGen, mode)`; `306a7b2` — `...StringParserStereo` + `arbKeepFilters` (генератор
возвращает владеемые буферы) → независимые L/R кривые; `0cf7d75` — `arbLoadFilters` с проверкой
сегментного количества (линейная фаза удваивает длину: 8192 vs 16383).

**Почему это ценно именно для Loudness-PEQ-форка.** FIR-режим с линейной фазой и раздельными каналами — это
основа для per-ear коррекции (главная цель Loudness/PEQ-направления; ср. `docs\FAQ_FIR_EQ.md`).

**План переноса.**
1. C: `arbKeepFilters`-семантика (parser возвращает буфер, который надо освободить), `SetPhaseMode`,
   `...StringParserStereo`, `arbLoadFilters`-гард.
2. JNI: новые external-функции + `JamesDspLocalEngine`/`JamesDspWrapper` проброс.
3. Схема: ключ `key_arbeq_phase_mode` (enum int) и второй текстовый поле для R-кривой; `PreferenceCache`
   namespace; миграция — при пустом R-поле считать L==R (текущее поведение).
4. UI: сегмент-контрол «Фаза: линейная / минимальная» + чекбокс «раздельно L/R»
   (`SwitchPreferenceGroup.childVisibilityFilter` — K-«мелочи»).
5. HAL/JNI command ids: если используется числовая схема параметров — зарезервировать блок и не занимать
   существующие id.

**Лицензия.** GPL-3.0, NOTICE.

**Приёмка.** юнит: та же кривая в min и linear режиме → одинаковая АЧХ (±0.1 дБ) и разная фазовая;
L/R-раздельная загрузка → разные impulse-массивы; переключение фазы 200 раз без OOB (guard-bytes) и без
изменения громкости.

---

### K15. Пакет новых DSP-эффектов (XL, только по явному запросу)

Ни одного из этих файлов в target нет (проверено listing'ом `…jdsp\Effects\`; присутствуют `arbEqConv.c`,
`dynamicEq.c`, `lufs_normalizer.c`, **нет** `maximizer.c`, `multibandDist.c` и др. из этого списка):

| Эффект | Source-код (проверенные строки) | Ключевая идея |
|---|---|---|
| **Maximiser** `16d98c0` | `…Effects\maximizer.c:19` `#define MAX_MASK (MAXR_BUFLEN - 1)`; `:40-47` | O(1) скользящий максимум на monotonic-deque, безопасно для unsigned-wrap; 4 режима; true-peak через oversampling + lookahead |
| **Multiband distortion** `f8c4d12` | — | 8 shaper'ов, RBJ-биквад каскад полос, split-route `out = (x-b) + f(b)` — идентичность при drive 0 |
| Butterworth-каскад для LP/HP | `…Effects\multibandDist.c:279-292` | `stages = slope/12`; Q секции `1/(2 cos((2k+1)π/(4·stages)))`, пользовательский Q только на последней секции. `5ea9281` даёт то же + Kotlin-`cascadeFor` для графического PEQ |
| **Reverb: Dattorro plate / 8-line FDN Hall / Room** `9190730`, `c35c9b1` | `…Effects\vreverb*.c` | Hadamard-матрица, split RT60 hi/lo, Schroeder-diffusers, allpass внутри линии, взаимно простые длины |
| **Pitch shift (granular + phase vocoder)** `a0918f0`, `f8aa719` | — | 8192-кольцо/канал, две головы на пол-окна, equal-power sin-кроссфейд; PV 1024/hop 256, in-place radix-2 FFT |
| **Haas/differential surround фикс** `a1809da` | — | чтение за буфером каждые 0.37 с: отрицательный `readPos` + `LEN` округляется ровно в `LEN` |
| **Vinyl noise** `25fa073`, `80f9faf` | — | ОДИН LCG (1664525/1013904223) на 9 источников шума; envelope flush-to-zero < 1e-20 (субнормалы) |
| **Transient shaper** `c96fda5` | — | `gain = 20log10(fast/slow)` − 2.5 дБ deadzone, пары attack/sustain-огибающих |
| **Multiband imaging без кроссовера** `b8d7d94` | — | ширина = gain по side-сигналу → shelf/peak/shelf только на side |
| **Exciter (sum-of-deltas)** `536c533` | — | 4 телескопические полосы, `out = x + Σ amount·(shape(band·drive)/drive − band)` — точный bypass |
| **Low-end** `54ca557` | — | HP 4-го порядка с Butterworth-Q 0.54119610/1.30656296 |
| **Balance** `a5d21b6` | — | attenuation-only дальняя сторона, swap до balance, mono-blend, one-pole 25 мс |
| **Treble/bass exciter** `29d5565`, `20ea4a5`, `d8c333a` | — | `spectrumExtension.c`, `bassex.c`, `vdynbass.c` (4-каск. one-pole lattice, 19 ViperFX-пресетов) |
| **Haas/hpsurround v2** `8a22fdb` | — | ITD 0.30 мс, 3 отражения 9/17/29 мс, head-shadow LP 1100 Гц, damper LP 3500 Гц |
| **ZDF SVF вместо Chamberlin** `ed763c0` | — | Chamberlin расходится выше fs/6 → topology-preserving `g = tan(π·fc/fs)` + non-finite линейка |

**Почему не в топ-10.** Каждый из них — новая подсистема: C-файл + JNI + параметрическая схема +
`PreferenceCache` + карточка UI + `EffectLayoutManager` + пресеты + тесты. Порядок A > B… не отменяет
того, что **сначала** стоит починить живые дефекты K1–K5, K7–K10 (стоимость S, эффект гарантированный).

**План переноса (для последующего запроса).** Для одного эффекта: C-файл → запись в `CMakeLists.txt` →
enum-id в цепи (после K13) → JNI-обёртка → `ParametricEqSurface`/`KnobView`-карточка → `key_*` в
`res/xml` → миграция (дефолт = bypass, идентичность) → unit + on-device smoke → NOTICE-запись.

**Лицензия.** Все — GPL-3.0 (производная): обязательны NOTICE-записи по образцу `NOTICE:134-141` /
`:168-191`, с указанием конкретной пары файлов и `Copyright (c) alienware377 and contributors`.

**Приёмка (общая).** «identity test»: все ручки в дефолт → макс. разница ≤ 1e-6; «audible test»:
сканирование параметра даёт монотонное изменение ожидаемой метрики (RMS/спектр/фазa); «default-consistency
test» по образцу source (`3dcf3b2`: дефолты C-init ↔ Kotlin ↔ XML сверяются автоматически).

---

## 4. Анти-портинг лист (не переносить — с причиной по коду)

| SHA | Что | Почему не портить |
|---|---|---|
| `70facca`, `3cf49cd`, `90d0d3c`, `e413426`, `eafcc6f`, `ec52885` | AIDL-HAL-подсистема source: vendored `aidl_api` snapshot (98 файлов + `.hash`), `generate.sh --lang=ndk --structured --stability=vintf --version=3 --min_sdk_version=33`, попытка proxy `IFactory` и её отказ (AOSP-сервис хардкодит имя инстанса, оба заявляют `default`, проигравший молчит) | Target — **rootless**-форк; на Pixel-маршруте нужен не proxy, а dlopen-`.so` под overlay (вывод `3cf49cd`). Порт целиком = ~100 vendored-файлов + APEX-специфика. Это отдельный проект, не «порт решения». См. `docs\AIDL_SUPPORT.md` — сначала решить, нужен ли target AIDL-HAL вообще. |
| `6685cde` | HAL-контракт: reject non-float/mono, `ACCUMULATE` accessMode, чанкование по `RV4A_BLOCKSIZE` | Относится к HAL-эффект-пути source, которого в target нет. |
| `5ba4983`, `1345fe5` | Fork-эффекты в **root**-режиме (`PARAM_FORK_BASE=26000`, `enable = id+100`), утечка ~271 МБ на `SET_CONFIG` | Завязаны на root-mode HAL-параметризацию source. У target другой набор param-id. Идея «не пересоздавать engine на SET_CONFIG» ценна, но должна быть реализована на target-контракте, а не перенесена. |
| `6152d9b` | V4A-only режим: пин захардкоженного ViPER-порядка `intArrayOf(...)`, скрытие поиска/групп | Прямой конфликт с продуктовой идеей Loudness-PEQ (PEQ-центричный UI). Портить только после K13 и как опциональный пресет порядка. |
| `ad33f7a`, `be13d93` | Liveprog-слоты 1..3 (JNI `setLiveprogSlot`, ключи `key_liveprog_file2..4`), live-drag PEQ | Liveprog-скрипты (EEL) в target не являются поддерживаемой фичей форка; `be13d93` — «compile никогда не звал EnableSlot» — дефект той подсистемы, которую не планируем брать. |
| `e7deb6a`, `6443408` | VST3-обёртки (`bassex`, `multibandDist`) | Target не собирается выпускать VST. Отдельный артефакт, вне текущей roadmap. |
| `302b549` | Magisk release-engineering: CI штампует `module.prop` из `AndroidConfig`, reject CRLF/Unpart… | В target есть свой `magisk-module/build-module.sh`; сверить, а не копировать: CI-workflow target уже другой (`project-ci-module-workflow`). |
| README/fastlane/crowdin/иконки/тема (≈35 коммитов `F`) | `80f9faf`-класс cosmetics, `AppTheme.VIPER`, «Icons v2», «README screenshots», «onboarding rename» | Чистая косметика чужого бренда. |
| `a9246db` | Limiter-режим 3 «ViPER classic»: 256-window tournament-tree max + 256 lookahead | В target уже есть `lufs_normalizer` и свои limiter-режимы; смысл — вайб-ViPER, не дефект. Отложить до явного продуктового запроса. (цифра «208 мс» из message **не проверена** — см. §5.) |
| `9feab0a` | Большие буферы эффектов на `calloc` при Enable / `free` при Disable + `JamesDSPReleaseEffectBuffers` | Правильная идея, **но** экономия байтов, заявленная в message, не проверена; на target-памяти эффект нужно мерить. Брать после K13, отдельно от него. |

---

## 5. Unknowns / «не проверено»

| # | Пункт | Статус |
|---|---|---|
| U-1 | В реестре `ViPER_commits_raw.tsv` у **27 строк** колонка вердикта содержит значение приоритета (сдвиг/пропуск при записи), т.е. YES/NO для них механически не читается: `9787ea6, f0ea801, 6f8cde3, c9168e0, 4962170, bf8d3c7, e78fdb9, 2ade5d2, 1561667, 5d5f090, f3e8418, 0dd5b8b, b69b808, 8654bab, 5ce0b41, 9f6fbb7, 15b4a99, 4d667ec, d2c88e3, 769d1c2, 563d157, 0cbeb86, c48324d, 5e30dbf, 81c69a0, 80f9faf, 0c92f8e`. Часть этих коммитов (`80f9faf`, `769d1c2`, `0cbeb86`, `c48324d` и др.) разобрана по diff и описана в §2/§3 —
**содержание известно, не зафиксирован только формальный YES/NO в таблице**. Диф-покрытие этих коммитов — 100 %. | не проверено (формально) |
| U-2 | `8c3d56e` — base-коммит, 1513 файлов / 357 027 diff-строк. Охарактеризован структурно (`--numstat`, `git ls-tree`, выборочное открытие файлов), **не построчно**. Возможные отдельные дефекты, занесённые в этом import'е, не классифицированы. | не проверено |
| U-3 | Численные утверждения, взятые из commit **messages** и не пересчитанные мной: «~271 МБ утечки на instance» (`1345fe5`), «208 мс» (`a9246db`), «экономия байтов» (`9feab0a`), «192→167 ns/sample» (`perf`-коммит MBD), «субнормалы в 90–250 раз медленнее» (`80f9faf`). Как обоснование для портирования не использовать — только как гипотезу, требующую замера. | не проверено |
| U-4 | Тела vendored-файлов: снимок `aidl_api` (98 файлов), RubberBand/VST-зависимости — просмотрены структурно (пути, заголовки, объём), не построчно. | не проверено |
| U-5 | Происхождение и лицензия `…Effects\bs2b.c` и `…eel2\numericSys\FFTConvolver.c` в **target**: license-заголовков нет. До переноса правок в эти файлы нужно установить upstream-авторство (иначе NOTICE-запись будет неполной). | не проверено |
| U-6 | `9c55dc1`, `d7b9da3` и часть `F`-коммитов: UI-структура source (карточки/ключи) отличается от target `EffectLayoutManager`; переносимость на уровне «механизм», а не «строк», не подтверждена макетом. | не проверено |
| U-7 | Целостность `chainOrder`-миграции на уже сохранённых пользовательских пресетах target (K13) — реальное содержимое prefs на устройстве не инспектировалось. | не проверено |

---

## 6. Дорожная карта (батчами; S-портирования первыми)

### Батч 0 — подготовка (0.5 дня)
- Завести golden-файл harness: фиксированный WAV + фиксированный набор параметров → дамп выхода, чтобы
  любой последующий шаг сверялся побайтово. Без этого шага K13/K15 непроверяемы.
- Решить U-5 (происхождение `bs2b.c`, `FFTConvolver.c`).

### Батч 1 — «мелкие, гарантированные, опасные дефекты» (все S, ≈1–2 дня, без изменения поведения при исправных входных данных)
1. **K1** vacuumTube (`8d01ece`)
2. **K3** dbb phase/`originalBuf` (`2ed7170`)
3. **K4** vdc parse limits (`95f6844`)
4. **K5** `selectConvPartitions` (`9772580`)
5. **K6-частично / K7** `remaining`-гарды во всех 12 местах + `arbLoadFilters`-гард
6. **K2** crossfeed: `&&`-условие + однократное чтение `mode` + `memset` при смене режима
7. **K10** soname
   Каждый пункт — отдельный коммит + своя приёмка. Порядок внутри батча не важен, кроме K2: `CrossfeedSetMode`
   под лок переносится только вместе с Батчем 2.

### Батч 2 — блокировки и контракт лока (M, ≈3–4 дня)
8. **K6** лок вокруг compander/mEQ/arbEq (+ pitchshift/diffsurround, если присутствуют в target)
9. **K6.3** снятие `jdsp_lock` из `*Enable`, перенос контракта на вызывающий уровень
10. **K2-часть** `CrossfeedSetMode` под лок (завершение)
11. **7a6b9cf** порядок в `*Disable` (флаг → drain → free)

### Батч 3 — частота дискретизации «по-настоящему» (M, ≈2–3 дня)
12. **K8** безусловные пересчёты (`57ce68f`, `ed58602`): DDC-пер-select, compander τ, stereo widening design
13. **K8.2** решение по `forceRefresh` из приложения — **только после** Батча 2
14. **`fd2c634`** `clearCache()`/`syncWithPreferences()` для всех карточек на `ACTION_SAMPLE_RATE_UPDATED`
15. **`2675a58`** `JamesDSPASRCWorkLength` — размер scratch под худший ratio (сейчас `ctMemBlk` в
    `…jdspController.c:~1250` не учитывает запас по коэффициенту)

### Батч 4 — устойчивость и наблюдаемость (M, ≈2–3 дня)
16. **K9** `isfinite`-гарды (после Батча 2 — чтобы NaN не маскировал гонки)
17. **K11** `CrashGuard.cpp` + JVM handler + breadcrumbs
18. **`5672330`** `perf_harness` (ns/sample + denormal-метод) — как инструмент, нужен для приёмки 15–17
19. **`33a4810`** `verifyLaunchAllowlist` + **`3dcf3b2`** default-consistency тест

### Батч 5 — rootless-поведение (M, ≈2–3 дня)
20. **K12** allowlist `addMatchingUid` + restricted-uid + silence-gate
21. **`02ef76b`** ликвидация `MODE_MULTI_PROCESS` (10 мест) / `b387a23` порядок teardown projection/engine
22. **`9feab0a`** effect buffers calloc/free on Enable-Disable (с замером, U-3)

### Батч 6 — FIR/PEQ возможности (M–L, ≈4–6 дней)
23. **K14** `SetPhaseMode` (linear/min) + per-channel parser + `arbKeepFilters` + UI-схема/миграция
24. **`5ea9281`** Butterworth-каскад в C + `cascadeFor` в `BiquadUtils.kt` — для крутизны slope у PEQ/MBD-карточек

### Батч 7 — архитектура цепи (L, ≈5–8 дней, риск высокий)
25. **K13** enum `JdspEffectId` + `chainOrder[]` + диспетчер + дедуп/дописка `setChainOrder` (`100dc32`)
26. После него — дешёво становятся: `6152d9b` (пресет порядка), `3c8d95c` (лок как атрибут case), `9feab0a`

### Батч 8 — новые эффекты (XL, по явному продуктовому запросу)
27. **K15**,Recommended порядок внутри: maximiser → multiband distortion (+Butterworth из 24) → transient shaper
    → exciter/low-end/imaging → vreverb-модели → pitch shift → vinyl/balance → treble/bass exciter (уже частично
    покрыты существующими модулями target).

### Всегда, для каждого пункта
- NOTICE-запись перед коммитом, если переносится DSP/логика (GPL-3.0 производная, образец — `NOTICE:134-141`).
- Приёмка из карточки — до merge; на device — по существующему workflow (MT2111 / Magisk 30.7),
  с фиксацией baseline-неудач, чтобы не приписать регрессию портированию.

---

## Приложение A — self-check: по одной реальной добавленной строке из случайных коммитов

Проверка «не message-only»: ниже — строки, взятые из `+`-блоков `git show <sha>` (каждая подтверждена
программно: `git show <sha> | grep -c "^+.*<фрагмент>"` ≥ 1), из разных категорий и разных батчей реестра.

| SHA | Кат | Строка из diff (фактический `+`) |
|---|---|---|
| `03ae109` | E | `set +e` |
| `86cd00e` | F | `import android.graphics.Canvas` |
| `eafcc6f` | D,E | `Five further packages had to be vendored to get` |
| `5b4a0e6` | A | `#define SWITCH  (48000)         /* one second in, on a zero crossing */` |
| `0129ec2` | A,G | `a modulation far above 100 % swung the smooth models'` |
| `d7b9da3` | F | `android:key="@string/key_vreverb_room` |
| `9c55dc1` | F | `# six cards, plus two controls that live inside older cards.` |
| `3dcf3b2` | E,F | `# has a master switch that map can actually find.` |

Дополнительная контрольная выборка по **карточному** покрытию (строки, приведённые в §3, открыты в файлах
обоих репозиториев по указанным номерам строк: `vacuumTube.c:12-17, 84-85, 131-141` / `crossfeed.c:47, 55,
100-141` / `dbb.c:327-342` / `vdc.c:50-68` / `jdspController.c:185-196, 325-337, 1226, 1266-1280` /
`FFTConvolver.c:459…1029, 487, 535, 920, 938` / `arbEqConv.c:9, 26-30, 78-81` /
`multibandDist.c:279-292` / `maximizer.c:19, 40-47` / `CMakeLists.txt:60, 96-104` /
`JamesDspLocalEngine.kt:23` / `RootlessAudioProcessorService.kt:597, 1399` / `NOTICE:134-141, 150-160,
168-191`).

**Итог самопроверки:** 320/320 коммитов открыты по diff (100 %); ни одно утверждение о дефекте в target не
сделано без строки, прочитанной в target-файле; все пробелы и непроверенные числа вынесены в §5, а не
«догаданы».

---

## Приложение B — артефакты аудита

| Файл | Содержимое |
|---|---|
| `C:\dev\DSP_GIT\codeview\ViPER_commits_raw.tsv` | реестр 320 коммиттов: `sha\|date\|cat\|что_делает_код\|verdict\|prio\|target_status` |
| `C:\dev\DSP_GIT\codeview\ViPER_commits_raw.tsv.bak` | снимка реестра до попыток нормализации (не изменён) |
| `C:\dev\DSP_GIT\codeview\batch_00.tsv … batch_07.tsv` | инвентарь по 40 коммиттов: `sha\|date\|author\|subject\|files\|binary\|difflines\|kindFlags` |
| `C:\dev\DSP_GIT\codeview\ViPER_commits_index.txt`, `ViPER_commit_exts.txt`, `ViPER_commit_flags.txt` | индекс SHA, трекExtensions, битовые флаги типа изменения |

Отчёт подготовлен в режиме read-only: source-репозиторий не изменялся, сборки/установки/запуски из него не
выполнялись, в target не перенесено ни одной строки кода.
