# Magisk module: ainur_jamesdsp (PEQ+Loudness)

Root-mode звуковой движок JamesDSP в виде Magisk-модуля: нативная библиотека
`libjamesdsp.so` (soundfx, загружается AudioFlinger'ом/audio HAL). Работает
вместе с root-сборкой APK (`JamesDSP-v<app>-root-full-*.apk`, flavor `root`).

Начиная с v6.3.10, в zip не лежит зашитых `audio_effects.xml`/`.conf`:
установщик ищет живые конфиги устройства (включая Samsung `_sec` и Qualcomm
SKU-пути), патчит их, валидирует и кладёт в overlay, а движок раскладывает
в `libs/<abi>/` на 4 ABI и ставит во все пути soundfx (system/vendor/odm ×
lib/lib64). `post-fs-data.sh` валидирует конфиги при каждой загрузке и
карантинит битые; после двух незавершённых загрузок модуль самоотключается.

## Структура

```
magisk-module/
├── src/                    # шаблоны модуля (коммитится)
│   ├── module.prop.template    # плейсхолдеры {{MODULE_VERSION}} и т.д.
│   ├── update.json.template     # рендерится в build/update.json (ассет релиза, НЕ внутрь zip)
│   ├── customize.sh / service.sh / uninstall.sh / post-fs-data.sh
│   └── common/patch_audio_config.sh
├── build-module.sh         # локальная сборка zip
└── ainur_jamesdsp-peq-loudness-v*.zip   # выпущенные сборки
```

Библиотека берётся из Gradle-сборки приложения: CMake-цель `jamesdsp_effect`
(`app/src/main/cpp/CMakeLists.txt`) собирает soundfx-вариант движка
(`jamesdsp.c` + `peq_loudness.c` + все эффекты) из сабмодуля
`app/src/main/cpp/libjamesdsp` (форк JamesDSPManager, ветка `extensions`).
При упаковке нестрипнутые (с debug-инфой) `.so` из
`app/build/intermediates/cxx/*/obj/<abi>/` переименовываются в
`libs/<abi>/libjamesdsp.so`; если CMake-выхода нет (CI), берётся стрипнутая
копия из root universal APK.

## Локальная сборка

```bash
# собирает ровно v6.3.10-peq-loudness (versionCode 114), как в релизе
# v1.6.15-58; module.prop совпадает байт-в-байт при -D 2026-10-06
./build-module.sh -D 2026-10-06

# новая версия модуля
./build-module.sh -v v6.3.11-peq-loudness -c 115 -d "v6.3.11: что нового"

# собрать всё с нуля (APK соберётся сам, если универсального нет)
./build-module.sh -v v6.3.11-peq-loudness -c 115
```

Параметры:
- `-v` — версия модуля (по умолчанию v6.3.10-peq-loudness); тег релиза совпадает с ней
- `-c` — versionCode, целое, больше предыдущего (по умолчанию 114; v6.3.6 = 110)
- `-d` — заметки, добавляются после базовой строки описания; без `-d` для
  дефолтной версии подставляются заметки релиза v6.3.10
- `-a` — путь к готовому root universal APK (по умолчанию берётся последний собранный)
- `-D` — дата в module.prop (по умолчанию сегодня)
- `--updatejson` — заполнить `updateJson=` URL автообновления
  (по умолчанию пустой, как во всех релизах)

К каждому релизу модуля прикладывайте сгенерированный `build/update.json`
как ассет (CI делает это сам; при ручном релизе — файл из `magisk-module/build/`).

## CI

Workflow `.github/workflows/build.yml`:
- на push в `master` / PR — собирает rootless+root APK и модуль zip как артефакты;
- на тег `v*` — то же + публикует GitHub release с APK всех ABI и модулем,
  и коммитит обновлённый `update.json` в master (`[skip ci]`).

Тег для модуля: `v6.3.x-peq-loudness` (versionCode в CI вычисляется как
`MAJOR*10000 + MINOR*100 + PATCH`, например v6.3.7 → 60307; для ручных сборок
задавайте `-c` явно).
