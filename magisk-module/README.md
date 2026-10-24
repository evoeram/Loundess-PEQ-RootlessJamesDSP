# Magisk module: ainur_jamesdsp (PEQ+Loudness)

Root-mode звуковой движок JamesDSP в виде Magisk-модуля: нативная библиотека
`libjamesdsp.so` (system soundfx, загружается AudioFlinger'ом) плюс
`audio_effects.xml`/`.conf` конфиги. Работает вместе с root-сборкой APK
(`JamesDSP-v<app>-root-full-*.apk`, flavor `root`).

## Структура

```
magisk-module/
├── src/                    # шаблоны модуля (коммитится)
│   ├── module.prop.template    # плейсхолдеры {{MODULE_VERSION}} и т.д.
│   ├── update.json.template
│   ├── customize.sh / service.sh / uninstall.sh / changelog.md
│   └── system/             # audio_effects конфиги (etc, odm, vendor)
├── build-module.sh         # локальная сборка zip
└── ainur_jamesdsp-peq-loudness-v*.zip   # выпущенные сборки
```

Библиотека берётся из Gradle-сборки приложения: CMake-цель `jamesdsp_effect`
(`app/src/main/cpp/CMakeLists.txt`) собирает soundfx-вариант движка
(`jamesdsp.c` + `peq_loudness.c` + все эффекты) из сабмодуля
`app/src/main/cpp/libjamesdsp` (форк JamesDSPManager, ветка `extensions`).
При упаковке она переименовывается в `libjamesdsp.so` — тот же процесс, что и
в ручных релизах до v6.3.6.

## Локальная сборка

```bash
# APK уже собран (app/build/outputs/apk/rootFull/release/*universal-release.apk)
./build-module.sh -v v6.3.7-peq-loudness -c 111 -d "что нового"

# или собрать всё с нуля (APK соберётся сам)
./build-module.sh -v v6.3.7-peq-loudness -c 111 -a ""
```

Параметры:
- `-v` — версия модуля (обязательно), тег релиза совпадает с ней
- `-c` — versionCode, целое, больше предыдущего (v6.3.6 = 110)
- `-d` — заметки, добавляются в описание модуля
- `-a` — путь к готовому root universal APK (по умолчанию берётся последний собранный)
- `--no-updatejson` — не включать автообновление (`updateJson=` пустой)

Автообновление: `module.prop` ссылается на стабильный URL
`releases/latest/download/update.json`, поэтому к каждому релизу модуля
нужно прикладывать сгенерированный `build/update.json` (CI делает это сам;
при ручном релизе — приложите файл из `magisk-module/build/`).

## CI

Workflow `.github/workflows/build.yml`:
- на push в `master` / PR — собирает rootless+root APK и модуль zip как артефакты;
- на тег `v*` — то же + публикует GitHub release с APK всех ABI и модулем,
  и коммитит обновлённый `update.json` в master (`[skip ci]`).

Тег для модуля: `v6.3.x-peq-loudness` (versionCode в CI вычисляется как
`MAJOR*10000 + MINOR*100 + PATCH`, например v6.3.7 → 60307; для ручных сборок
задавайте `-c` явно).
