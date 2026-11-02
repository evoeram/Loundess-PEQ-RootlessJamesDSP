# План исправления ошибок и оптиссмизации

## 1. Контекст аудита

* База: ветка `master`, коммит `3e80b75` (`release: v1.6.26`).
* Проверены собственные Kotlin/C/C++-компоненты: rootless capture loop, JNI wrapper, PEQ/Loudness, Voice Isolation, backup/restore, receivers и CI.
* Архивные копии сторонних исходников под `app/src/main/cpp/libjamesdsp/VeryOldOpen_source_edition` в активный аудит не включались.
* Это план, а не набор исправлений: исходный код в рамках аудита не изменялся.

### Проверка, выполненная во время аудита

`git diff --check HEAD~3..HEAD` завершился без ошибок.

Команда `./gradlew testRootlessFullDebugUnitTest --stacktrace` не запустилась: в локальном окружении не задан `JAVA_HOME` и отсутствует команда `java`. Поэтому пункты ниже основаны на статическом анализе; перед реализацией нужно восстановить JDK 17 и получить базовый тестовый прогон.

## 2. Приоритеты

* **P1 — до следующего релиза:** потеря/повреждение звука или данных, crash, race в аудиопотоке, небезопасный импорт.
* **P2 — следующий рабочий цикл:** ошибки отдельных режимов, диагностики и Android-версий; устранение RT-деградаций.
* **P3 — после стабилизации:** память, CPU, APK и поддерживаемость, с обязательным измерением результата.

| ID      | Приоритет | Область                | Краткая проблема                                                                                 |
| ------- | --------- | ---------------------- | ------------------------------------------------------------------------------------------------ |
| BUG-01  | P1        | USB PCM 24/32          | `AudioTrack.write(ByteBuffer)` возвращает байты, но результат используется как число samples     |
| BUG-02  | P1        | Жизненный цикл capture | Старый аудиопоток может пережить `stopRecording()` и работать параллельно с новым                |
| BUG-03  | P1        | Жизненный цикл engine  | `JamesDspLocalEngine.close()` не вызывает `super.close()` и освобождает native handle по таймеру |
| BUG-04  | P1        | Backup/restore         | TAR path traversal, отсутствие лимитов и неатомарное восстановление                              |
| BUG-05  | P1        | Subsonic               | Фильтр пропускается при neutral loudness; нечётные порядки реализованы неверно                   |
| BUG-06  | P1        | Native Loudness        | Параметры и biquad state изменяются параллельно без полноценной синхронизации                    |
| BUG-07  | P2        | Liveprog/JNI           | Сохранённый `JNIEnv*` используется в callback без привязки к потоку                              |
| BUG-08  | P2        | Voice Isolation        | Дополнительная задержка рассчитана без умножения на `hopSize`                                    |
| BUG-09  | P2        | Loudness/PEQ UI        | Разные формулы в C, C++ и Kotlin; график не всегда совпадает со звуком                           |
| BUG-10  | P2        | Android 15             | Известный запрет FGS-запуска из `BOOT_COMPLETED` оставлен как FIXME                              |
| BUG-11  | P2        | Broadcast API          | Exported power receiver принимает вызов без проверки action и разрешения                         |
| BUG-12  | P2        | Legacy JNI PCM24       | Packed PCM24 получает неверный frame count                                                       |
| OPT-01  | P2        | Real-time DSP          | Выделения памяти и лишние копии на каждый PCM16/Float-блок                                       |
| OPT-02  | P2        | Loudness CPU           | Тяжёлый пересчёт на audio thread и обработка всех 29 ISO-полос, включая passthrough              |
| OPT-03  | P3        | Voice Isolation        | `Future` на каждый блок, дублирование ONNX sessions и удержание model bytes                      |
| OPT-04  | P3        | Native build           | Глобальные `-Ofast` и `-DDEBUG` применяются ко всем конфигурациям                                |
| TEST-01 | P1        | Качество/CI            | CI только собирает APK; unit-тесты и lint не запускаются                                         |

## 3. Детальный план исправлений

### BUG-01 — нормализовать единицы записи PCM

**Доказательства**

* `RootlessAudioProcessorService.kt:1416–1473`: ветки Float/Short возвращают samples, а `ENCODING_PCM_24BIT_PACKED` и `ENCODING_PCM_32BIT` возвращают результат `AudioTrack.write(ByteBuffer, …)` в байтах.
* `RootlessAudioProcessorService.kt:1109–1110,1180–1181`: одно и то же значение передаётся в `QueueController.onWritten()` и `LatencyTracer.onWritten()`.
* `QueueController.kt:35–37` и `LatencyTracer.kt:166–183` делят его на 2, ожидая interleaved samples.

**Последствия**

* Очередь завышается в 3 раза для PCM24 и в 4 раза для PCM32; контроллер может постоянно выбрасывать блоки.
* При включённом tracer завышенный frame count может привести к чтению за границей исходного массива.

**Действия**

1. Ввести один результат записи, например `WriteResult(samplesWritten, bytesWritten)`, либо всегда возвращать samples из обоих helper-методов.
2. Для `ByteBuffer` переводить результат в samples через `bytesPerSample(outputEncoding)`.
3. Явно обрабатывать отрицательные коды ошибок и partial write, не складывая их с успешными значениями.
4. Проверить, что counters QueueController, LatencyTracer и фактический playback head используют frames в одной системе координат.

**Регрессионные тесты**

* PCM16, PCM Float, PCM24 packed, PCM32; полный и частичный write.
* `samplesWritten == expectedSamples`, `framesWritten == samplesWritten / 2`.
* Tracer не обращается за пределы массива; QueueController не входит в цикл постоянных drop.

***

### BUG-02 — сделать остановку capture loop детерминированной

**Доказательства**

* `RootlessAudioProcessorService.kt:1215–1221`: после `join(500)` ссылка на поток обнуляется без проверки `isAlive`.
* `RootlessAudioProcessorService.kt:1231–1239`: при restart общий `isProcessorDisposing` сразу возвращается в `false`.
* Поток может блокироваться в `AudioRecord.read()`, `AudioTrack.write()` или Voice Isolation `Future.get()`; `interrupt()` не гарантирует выход из всех этих вызовов.

**Последствия**

Старый и новый потоки способны одновременно использовать engine, PCM source и UI/service callbacks. Возможны двойной захват, гонки ресурсов, устаревший `finally`, зависший или не освобождённый `AudioRecord`/`AudioTrack`.

**Действия**

1. Ввести per-run `generation`/`CancellationToken`, а не один переиспользуемый Boolean для всех поколений потоков.
2. Сначала снять блокировки ввода-вывода: остановить recorder/track/PCM source, разбудить ожидание worker, затем дождаться завершения.
3. Не запускать новый поток, пока предыдущий жив; timeout должен завершаться ошибкой/отложенным restart, а не потерей ссылки.
4. В `finally` освобождать только ресурсы своего generation.
5. `onDestroy()` должен отменять `applicationScope` и связанные delayed callbacks.

**Критерии приёмки**

* 100 циклов start/stop/restart и смены sample rate без двух живых capture loops.
* Принудительно заблокированные fake source/sink корректно пробуждаются и завершаются.
* После `onDestroy()` нет активных callbacks, coroutines и аудиоресурсов сервиса.

***

### BUG-03 — синхронизовать закрытие local engine

**Доказательства**

* `JamesDspLocalEngine.kt:40–54`: отсутствует `super.close()`, поэтому `syncScope` из `JamesDspBaseEngine.kt:36–44` не отменяется.
* Native handle освобождается через `Timer().schedule(100)`; фиксированные 100 ms не доказывают завершение in-flight JNI-вызовов.
* Для сравнения, `JamesDspRemoteEngine.close()` вызывает `super.close()`.

**Действия**

1. Вызывать `super.close()` и дожидаться завершения/отмены синхронизации настроек.
2. Заменить таймер на явный lifecycle lock: запрет новых вызовов → ожидание активных JNI-операций → `free()`.
3. Согласовать этот lock с BUG-02, чтобы `free()` выполнялся только после остановки audio thread.
4. Удалить `Timer`, который также создаёт лишний поток и усложняет владение handle.

**Регрессионные тесты**

* Одновременные `syncWithPreferences()`, `process*()` и `close()` не обращаются к освобождённому handle.
* Повторные create/close не оставляют `syncScope`, receiver и Timer-потоков.

***

### BUG-04 — безопасный и восстанавливаемый импорт backup

**Доказательства**

* `Tar.kt:119–142`: entry name напрямую конкатенируется с `targetFolder` без canonical-path проверки.
* `BackupManager.kt:172–176`: фильтр разрешает, среди прочего, имена, начинающиеся с `profiles/`; это не исключает `profiles/../../…`.
* Нет ограничений на размер entry, общий распакованный размер, число файлов и metadata.
* `BackupManager.kt:124–153`: старые данные удаляются до успешного завершения копирования новых.

**Действия**

1. Нормализовать separators, вычислять canonical destination и требовать `destination.startsWith(targetRoot + separator)`.
2. Отклонять абсолютные пути, `..`, symlink-like/необычные entries и неподдерживаемые типы.
3. Разрешать только точные корни и расширения backup-схемы, а не `contains()`.
4. Добавить лимиты на entry, total bytes, metadata и количество файлов; прекращать чтение при превышении.
5. Восстанавливать во временную директорию, валидировать всё, затем атомарно менять данные местами с rollback.
6. Всегда очищать временную директорию через `finally`/`deleteRecursively()`.

**Регрессионные тесты**

* `../`, вложенный traversal, backslash traversal, абсолютный путь, огромный entry и gzip bomb отклоняются.
* Ошибка на середине restore сохраняет прежние preferences и файлы пользователя.

***

### BUG-05 — исправить Subsonic и привести порядки к одной спецификации

**Доказательства**

* `LoudnessCorrectionProcessor.cpp:342–343`: `processInterleaved()` возвращается при `neutral` до вызова `processDeinterleaved()`, где реализован независимый subsonic path.
* `peq_loudness.c:769–793` и C++ `processDeinterleaved()` показывают, что intended behavior — обрабатывать subsonic даже при neutral loudness.
* В C/C++ число biquad stages равно `ceil(order/2)`, но first-order stage отсутствует: заказанный order 1 фактически становится order 2, order 3 — order 4.
* `LoudnessCurveCalculator.kt:141–157` дополнительно добавляет first-order response после `ceil(order/2)` biquads, поэтому график для order 1 показывает order 3.

**Действия**

1. Удалить преждевременный `neutral` return из interleaved path; оставить единую проверку в deinterleaved implementation.
2. Определить формулу: `floor(order/2)` biquads плюс один first-order HP для нечётного order.
3. Реализовать одинаково в C, C++ и Kotlin; добавить корректный first-order state, а не только response на графике.
4. Уточнить Q-стратегию каскада: одинаковый Q всех секций не является стандартным Butterworth для order > 2. Либо рассчитывать секционные Q, либо явно документировать другой фильтр.

**Критерии приёмки**

* При neutral loudness subsonic продолжает влиять на impulse/sine sweep.
* Для order 1–8 измеренный низкочастотный slope соответствует `6 * order dB/oct` в пределах выбранного допуска.
* C, C++ и Kotlin response совпадают на едином наборе частот.

***

### BUG-06 — устранить data races в Loudness

**Доказательства**

* `LoudnessCorrectionProcessor.cpp:52–119` изменяет параметры и заменяет biquads из config thread.
* `LoudnessCorrectionProcessor.cpp:328–499` одновременно читает/изменяет те же объекты на audio thread.
* Атомарны только `volumeDb` и `coeffsDirty`; `enabled`, tuning, mode, `neutral`, biquad coefficients/state и subsonic fields не защищены.
* В root C-варианте `peq_loudness.c:691–870` аналогичные поля также не имеют общего lifecycle/config lock, хотя PEQ рядом уже использует `cfgLock`.

**Действия**

1. Разделить immutable coefficients/config и mutable per-channel filter state.
2. Собирать новую конфигурацию вне audio thread и публиковать её атомарно/под коротким блоковым lock.
3. Не перезаписывать biquad state, пока его использует аудиоблок; определить политику сохранения/сброса state и click-free перехода.
4. Сделать enable/disable потокобезопасным во всех движках.
5. Прогнать конфигурационные stress-тесты под TSan на host или поддерживаемом arm64 target.

**Критерии приёмки**

* Нет torn coefficients, NaN/Inf и обращений к заменённым состояниям при непрерывном изменении volume/tuning/mode.
* Аудиоблок не ждёт тяжёлый config rebuild; lock, если остаётся, имеет строго ограниченную длительность.

***

### BUG-07 — исправить JNI callback Liveprog

**Доказательства**

* `JamesDspWrapper.cpp:88–94` сохраняет `JNIEnv*` в долгоживущем wrapper.
* `JamesDspWrapper.cpp:1167–1180` использует его в native stdout callback и создаёт Java string без удаления local reference.

`JNIEnv*` является thread-local; его нельзя безопасно переиспользовать на произвольном DSP/config thread.

**Действия**

1. Сохранять `JavaVM*`, полученный через `env->GetJavaVM()`.
2. В callback получать корректный `JNIEnv*` для текущего потока через attach/getEnv и отсоединять только самостоятельно присоединённый поток.
3. Удалять local refs и проверять pending Java exception.
4. Синхронизировать callback с закрытием wrapper, чтобы исключить вызов после `DeleteGlobalRef()`.

**Тесты**

Liveprog output из разных потоков, длинная серия сообщений и одновременный close — без crash и роста local-reference table.

***

### BUG-08 — исправить расчёт задержки Voice Isolation

**Доказательство**

`VoiceIsolationProcessor.kt:247–251` вычисляет:

`(detectedDelayHops - 1) * 1000 / modelSampleRate`

`detectedDelayHops` измеряется в hops, поэтому отсутствует множитель `backend.hopSize`. Например, один дополнительный hop из 256 samples при 16 kHz равен 16 ms, а не 0,0625 ms.

**Действия**

1. Использовать `(detectedDelayHops - 1) * hopSize * 1000f / modelSampleRate`.
2. Вынести расчёт в тестируемую функцию и синхронизировать комментарий с `HopPipeline.latencyMs`.
3. Синхронизировать чтение/сброс `outputPeak`, если telemetry будет использоваться для автоматических решений.

**Тесты**

16/32/48 kHz model rates, hops 0/1/2 и измеренный impulse delay сравниваются с отображаемым значением.

***

### BUG-09 — сделать Loudness/PEQ math единой во всех путях

**Доказательства**

* C++ `LoudnessCorrectionProcessor.cpp:161` использует константу `0.175` для high shelf выше reference и игнорирует пользовательский `hsRatio`; C-вариант использует `hsRatio * 0.778`.
* `LoudnessCurveCalculator.kt:289` жёстко задаёт 48 kHz независимо от реального sample rate.
* ISO-график интерполирует gains между центрами полос, а audio path применяет каскад biquads; это разные АЧХ.
* Peak каскада в C/C++ оценивается только на центрах и geometric means, поэтому возможен промах между точками.
* Rootless PEQ расширяет cutoff в Butterworth cascade, тогда как C `peq_cascade_build()` создаёт один biquad на band.

**Действия**

1. Зафиксировать единую спецификацию коэффициентов и cutoff/slope behavior для root и rootless.
2. Использовать один reference test vector set для C, C++ и Kotlin.
3. Строить UI curve из тех же biquad responses, с реальным sample rate и preamp, а не из независимой интерполяции.
4. Оценивать true peak на плотной log-grid с локальным уточнением максимума; добавить небольшой документированный safety margin, если это требуется для предотвращения clipping.

**Критерии приёмки**

* При одинаковых параметрах АЧХ root/rootless/UI совпадает в пределах численного допуска.
* Плотная sweep-сетка не находит положительный итоговый peak после применения рассчитанного preamp.

***

### BUG-10 — закрыть Android 15 boot FIXME

**Доказательства**

* `BootCompletedReceiver.kt:53–64` содержит FIXME о запрете media FGS из `BOOT_COMPLETED`, но вызывает `startServiceEnhanced()`.
* Проект уже использует `targetSdk = 35`.

**Действия**

1. Воспроизвести root enhanced autostart на Android 15+.
2. Выбрать разрешённый сценарий: launcher activity при наличии overlay либо корректный FGS type/другой разрешённый entry point.
3. Обрабатывать `ForegroundServiceStartNotAllowedException` и показывать actionable notification вместо молчаливого сбоя.
4. Добавить device test для boot с включённым enhanced mode.

***

### BUG-11 — ограничить exported power control

**Доказательства**

* `AndroidManifest.xml:340–348`: `PowerStateReceiver` exported без permission.
* `PowerStateReceiver.kt:9–17`: не проверяет `intent.action` и по умолчанию трактует отсутствие extra как `false`.

Если публичное управление не является намеренным API, другие приложения могут переключать DSP. Если API нужен для автоматизации, его контракт следует закрепить явно.

**Действия**

1. Проверять точный action и отклонять неизвестные вызовы.
2. Для внутреннего использования сделать receiver non-exported; для публичного API рассмотреть signature permission или явно документированный capability.
3. Не использовать отсутствие extra как команду выключения.

***

### BUG-12 — исправить legacy packed PCM24 frame count

**Доказательство**

`JamesDspWrapper.cpp:461–478` передаёт `inputLength / 2` в `processInt24PackedMultiplexd()`. Для interleaved stereo PCM24 один frame занимает 6 байт, поэтому должно быть `inputLength / 6` с проверкой делимости. Соседний root engine передаёт `actualFrameCount`.

**Действия**

1. Исправить расчёт и проверку входного размера.
2. Проверить null после создания output array и получения JNI elements.
3. Добавить round-trip impulse test; даже если Kotlin API сейчас не используется, он не должен оставаться аварийным для будущих вызовов.

## 4. План оптимизации

### OPT-01 — убрать steady-state allocations и лишние копии из audio path

**Горячие места**

* `JamesDspWrapper.cpp:339,393`: `std::vector<float> tmp(frames * 2)` на каждый PCM16-блок при активном time-domain chain.
* `ParametricEqProcessor.cpp:128–155`: повторный `atomic_load` цепочки плюс deinterleave/reinterleave; фактическая обработка загружает chain повторно.
* `LoudnessCorrectionProcessor.cpp:345–366`: отдельные deinterleave/reinterleave и потенциальный `resize()` на audio thread.
* `ParametricEqProcessor.cpp:214–216`: denormal cleanup выполняется для каждой полосы на каждый sample, хотя достаточно один раз после блока.

**Действия**

1. Хранить reusable scratch buffer в wrapper с capacity, выделенным при создании/изменении размера блока вне RT-пути.
2. Объединить преобразование PCM16 → Float, post-chain и обратное преобразование без промежуточных vector allocations.
3. Обрабатывать interleaved stereo напрямую там, где deinterleaving не даёт доказанного выигрыша.
4. Передавать один загруженный chain в реализацию обработки; убрать двойной atomic load.
5. Вынести denormal cleanup на границу блока.
6. Не оптимизировать вслепую: сравнить allocation count, DSP ms и underruns до/после.

**Критерии приёмки**

* В steady-state `processInt16()`/`processFloat()` нет новых heap allocations от собственного wrapper.
* Нет регресса bit-exactness или заданного численного допуска на общем PCM test suite.

***

### OPT-02 — перенести тяжёлый Loudness rebuild вне RT и сократить каскад

**Проблема**

`recomputeCoefficients()` рассчитывает ISO gains, biquads и peak с множеством `pow/sin/cos/log10` на audio thread. При auto-volume или непрерывном seek bar это создаёт периодические всплески. Затем обрабатываются все 29 полос, даже сконфигурированные как passthrough.

**Действия**

1. После BUG-06 рассчитывать новые coefficients на config worker и публиковать готовый snapshot.
2. Дедуплицировать/ограничивать частоту volume updates; применять последнее значение вместо очереди всех промежуточных.
3. Хранить список только активных ISO-полос и пропускать остальные.
4. Кэшировать неизменные ISO table-derived значения и использовать существующие coefficients для peak evaluation вместо повторного пересчёта biquad math на каждой тестовой частоте.
5. Собрать benchmark для Classic/ISO, 48/96 kHz, разных block sizes и volume update rates.

***

### OPT-03 — снизить CPU и память Voice Isolation

**Доказательства**

* `VoiceIsolationProcessor.kt:202–209`: `worker.submit()` и `Future.get()` на каждый блок, включая блоки без полного enhancer hop.
* `VoiceIsolationProcessor.kt:175–177`: создаются два `Channel`; каждый вызывает `model.create()` и владеет отдельными ONNX sessions/state.
* `VoiceIsolationProcessor.kt:146–167`: последний набор model bytes дополнительно удерживается в Java heap.

**Действия**

1. Заменить per-block `Future` на постоянный worker с reusable task/ring handshake и корректным cancellation.
2. Не ставить задачу, если на текущем блоке нет полного hop; измерить выигрыш отдельно от inference time.
3. Измерить Java/native memory для GTCRN, DeepFilterNet3 и DpdfNet перед изменением модели владения.
4. Рассмотреть общие immutable ONNX sessions с раздельным recurrent state только после проверки thread-safety конкретных graphs; иначе выбрать последовательную обработку каналов на слабых устройствах.
5. Освобождать cached bytes после построения sessions либо ограничить cache явной memory-budget политикой.

**Критерии приёмки**

* Нет per-block `Future` allocations; realtime factor и underruns не ухудшаются.
* Memory profile задокументирован по каждой модели; переключение моделей не накапливает sessions/byte arrays.

***

### OPT-04 — разделить release/debug native flags

**Доказательство**

`app/src/main/cpp/CMakeLists.txt:8–9` глобально добавляет `-Ofast -ftree-vectorize -DDEBUG` для C и C++ во всех build types.

**Риски**

* `-DDEBUG` включает отладочные ветки в release.
* `-Ofast` может включать fast-math допущения, нежелательные для NaN/Inf guards, denormals и численной стабильности DSP.

**Действия**

1. Использовать generator expressions для Debug/Release.
2. Release: `-O2` как исходная безопасная точка; `-O3` принимать только по benchmark и numerical tests.
3. Убрать `-DDEBUG` из release, оставить нужные diagnostics через явные feature flags.
4. Проверять отсутствие нежелательных fast-math flags в compile commands всех ABI.

## 5. Тестовая и CI-стратегия

### TEST-01 — сначала регрессии, затем исправления

Для каждого BUG-пункта сначала добавить failing test или воспроизводимый device scenario. Минимальный набор:

1. **JVM unit tests:** QueueController units, write-result conversion, Voice Isolation latency, TAR validation, Loudness response/order math.
2. **Native host tests:** C/C++ biquad coefficients, subsonic neutral path, PCM24 frame count, repeated configure/process.
3. **Sanitizers:** ASan/UBSan для buffer/lifecycle paths; TSan для Loudness configure/process и engine close.
4. **Instrumented tests:** PCM16/Float/24/32 output, 100 restart cycles, Liveprog callbacks, boot на Android 15+.
5. **Audio golden tests:** impulse и sine sweep для PEQ/Loudness/Subsonic; проверка NaN/Inf, peak и АЧХ.

### Обязательные команды локальной проверки

```bash
./gradlew testRootlessFullDebugUnitTest
./gradlew testRootFullDebugUnitTest
./gradlew lintRootlessFullDebug lintRootFullDebug
./gradlew assembleRootlessFullDebug assembleRootFullDebug
```

После стабилизации release-пути добавить соответствующие Release-сборки и native tests.

### Изменения CI

Сейчас `.github/workflows/build.yml:61–67` запускает только `assemble…`.

1. Добавить отдельный verification job с JDK 17 и unit tests для Rootless/Root.
2. Запускать lint как blocking check; постепенно вернуть `abortOnError = true` и `checkReleaseBuilds = true` вместо текущего отключения в `app/build.gradle.kts:153–157`.
3. Публиковать JUnit/lint reports как artifacts.
4. Не блокировать первый PR на полный device farm: сначала host/JVM tests, затем выделенный scheduled device workflow.

## 6. Рекомендуемый порядок реализации

### Этап 1 — защита от потери данных и основных audio regressions

1. Восстановить JDK 17 и зафиксировать baseline tests/benchmarks.
2. BUG-01, BUG-04, BUG-05.
3. TEST-01: regression tests и обязательный JVM/CI verification.

### Этап 2 — жизненный цикл и потокобезопасность

1. BUG-02 и BUG-03 одним согласованным изменением lifecycle.
2. BUG-06, затем BUG-07.
3. Stress/soak tests с повторными restart, model switch, volume/tuning updates и close.

### Этап 3 — корректность отдельных режимов

1. BUG-08, BUG-09, BUG-12.
2. BUG-10 на Android 15+, BUG-11 после решения о публичном automation API.

### Этап 4 — измеримая оптимизация

1. OPT-01 и OPT-02 после появления golden tests.
2. OPT-03 после memory/CPU профилирования моделей.
3. OPT-04 с численной и performance-проверкой всех ABI.

## 6.1 Прогресс (обновлено 2026-11-02)

Реализовано в рамках этапа 3 (BUG-08/09/12):

- **BUG-08** — `VoiceIsolationProcessor`: `extraLatencyMs` теперь умножает hops на `hopSize`
  (тестируемая функция `mixerExtraLatencyMs`), тест `VoiceIsolationLatencyTest` (4 кейса).
- **BUG-12** — `JamesDspWrapper.cpp processInt24Packed`: frame count исправлен на
  `inputLength / 6` с проверкой делимости на 6, null-checks для output array и JNI elements.
- **BUG-09** —
  - единая спецификация treble-cut: `hsRatio * 0.778` в C++ (`LoudnessCorrectionProcessor`)
    и Kotlin (`LoudnessCurveCalculator`); C-вариант уже использовал её;
  - UI-кривая ISO строится из отклика реального каскада biquad (а не интерполяции ISO gains —
    устранено расхождение графика со звуком до ~8 дБ), sample rate проброшен из
    `AudioSampleRateDetector` в `LoudnessCurveCalculator.compute(sampleRate)`;
  - оценка cascade peak на плотной сетке (центр + средние геометрические с обоими соседями)
    синхронно в C++ и Kotlin;
  - тест `LoudnessCurveSpecTest` (3 кейса: hsRatio влияет на cut, дефолт = историческое
    поведение, кривая зависит от sample rate).
- Проверка: `testRootlessFullDebugUnitTest` — 285 тестов, 0 падений; синтаксическая
  проверка изменённых C++-файлов clang++ 18 (NDK 27) пройдена.
- Осталось по BUG-09 (требует решения о спецификации, не сделано): унификация поведения
  cutoff/slope rootless Butterworth-cascade vs одного biquad на полосу в C; reference test
  vector set, общий для C/C++/Kotlin; true-peak sweep-сетка как gate в CI.

## 7. Definition of Done

* Все P1-пункты имеют regression tests и закрыты без подавления предупреждений.
* Unit tests, lint и Debug/Release сборки Rootless/Root проходят в CI.
* Нет одновременных capture loops и освобождения native handle с активными вызовами.
* Backup traversal/oversized inputs отклоняются; неудачный restore не удаляет прежние данные.
* PCM16/Float/24/32 используют согласованные samples/frames/bytes; tracer и queue counters совпадают с фактической записью.
* Subsonic работает при neutral loudness; order 1–8 одинаков в C, C++ и на графике.
* Steady-state audio path не выполняет собственных heap allocations; DSP time и underruns не хуже baseline.
* Результаты оптимизации подтверждены замерами CPU, памяти, latency и underruns на минимум одном слабом и одном современном устройстве.

