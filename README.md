# Linienbegleiter

[![Android CI](https://github.com/your-username/Linienbegleiter/actions/workflows/ci.yml/badge.svg)](https.github.com/)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com/)
[![SDK](https://img.shields.io/badge/API-26%2B%20%28Android%208.0%2B%29-blue.svg)](https://developer.android.com/about/versions/oreo)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-purple.svg)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Compose-Material%203-brightgreen.svg)](https://developer.android.com/jetpack/compose)
[![MapLibre](https://img.shields.io/badge/MapLibre-13.7.1-orange.svg)](https://maplibre.org/)
[![Version](https://img.shields.io/badge/Version-0.25-informational.svg)](CHANGELOG.md)

**Linienbegleiter** («Маршрутный компаньон») — персональное локальное Android-приложение для записи GPS-маршрутов общественного транспорта, ручного создания и редактирования геометрии линий и остановок, следования по маршрутам и обмена данными в гео-форматах (GPX, GeoJSON, GTFS Static / CSV и резервный архив Room).

Язык UI: **русский**. Стек: **Kotlin + Jetpack Compose + Room + MapLibre + Foreground Services**. Цветовая система темы — **Hamburg Transit**: Hamburg Red, Elbe Blue, Harbor Navy, Brick и Sand с авто-переключением по датчику освещенности.

---

## 📖 Документация на разных языках / Languages

- [Русский (README.md)](README.md)
- [English (README_EN.md)](README_EN.md)
- [Deutsch (README_DE.md)](README_DE.md)

---

## ⭐ Ключевые возможности

1. **Интерактивная карта ([MapScreen.kt](app/src/main/java/com/example/linienbegleiter/MapScreen.kt)):**
   * Векторная карта MapLibre SDK (стили OpenFreeMap Liberty и Dark).
   * Синий указатель позиции с **вращением по вектору движения** во время поездки и **по гироскопу/компасу** (`Sensor.TYPE_ROTATION_VECTOR`) при вращении телефона в руках.
   * Выпадающая кнопка **«🚀 Поехали»** прямо поверх карты для мгновенного выбора сохранённых линий.
   * Автоматический расчёт границ маршрута (`LatLngBounds`) и фокус на весь маршрут при открытии.

2. **Запись GPS-треков ([RouteRecordingService.kt](app/src/main/java/com/example/linienbegleiter/service/RouteRecordingService.kt)):**
   * Фоновый сервис (`location` type) с уведомлением и регулярным сохранением контрольных точек каждые 10 секунд.
   * Запись дистанции, времени, скорости, высоты и точности с возможностью ставить остановки на ходу.

3. **Следование и навигация ([RouteFollowingService.kt](app/src/main/java/com/example/linienbegleiter/service/RouteFollowingService.kt)):**
   * Расчёт расстояния до следующей остановки, скорости и процента прохождения (`RouteProgressCalculator`).
   * **Функция «Прибытие — Отправление»:** при достижении конечной станции приложение автоматически предлагает обратный рейс (`🔄 Обратный рейс`) или маршруты от этой же станции.
   * Возможность запустить следующий рейс прямо из системного уведомления Android без разблокировки телефона.

4. **Редактор геометрии ([RouteEditorScreen.kt](app/src/main/java/com/example/linienbegleiter/RouteEditorScreen.kt)):**
   * Интерактивное редактирование участков линии с плавным сглаживанием (`smoothInfluence`) и автоматическим объединением выделяемых отрезков (`mergeRouteSections`).
   * Оформление остановок в виде удобных карточек со скруглённой рамочкой, переименованием, переносом по карте и изменением порядка.

5. **Импорт и Экспорт ([ImportExportScreen.kt](app/src/main/java/com/example/linienbegleiter/ImportExportScreen.kt)):**
   * Поддержка стандартов **GPX**, **GeoJSON**, **GTFS Static / CSV** и собственного архива `.zip` с SHA-256 контрольной суммой.

6. **Анализатор вождения (Eco-Drive):**
   * Глобальный переключатель в Настройках, отслеживающий резкие торможения, разгоны и скорость по GPS с выставлением итоговой оценки (0–100 баллов / 5 звёзд).

---

## 🛠 Технический паспорт

| Параметр | Значение |
|---|---|
| Версия приложения | `0.25` (`versionCode = 25`) |
| `applicationId` | `com.example.linienbegleiter` |
| `minSdk` | 26 (Android 8.0) |
| `targetSdk` / `compileSdk` | 37 (Android 15+) |
| AGP | 9.5.1 |
| Kotlin | 2.2.10 |
| Room БД | `linienbegleiter.db`, версия 5 |
| MapLibre SDK | 13.7.1 |

---

## ⚡ Быстрый старт и сборка

### Требования
- JDK 17 или JDK 21
- Android Studio (Ladybug / Jellyfish или новее)
- Android SDK 37

### Команды сборки (Linux / macOS)

```bash
# Сборка Debug APK
./gradlew :app:assembleDebug

# Запуск юнит-тестов
./gradlew :app:testDebugUnitTest

# Компиляция инструментальных тестов
./gradlew :app:compileDebugAndroidTestKotlin
```

### Команды сборки (Windows Command Prompt / PowerShell)

```cmd
:: Сборка Debug APK
gradlew.bat :app:assembleDebug

:: Запуск юнит-тестов
gradlew.bat :app:testDebugUnitTest
```

Подробные инструкции смотрите в [docs/BUILD.md](docs/BUILD.md).

---

## 📚 Индекс документации

| Документ | Назначение |
|---|---|
| [docs/BUILD.md](docs/BUILD.md) | Полная инструкция по настройке окружения, сборке и решению ошибок |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Карта архитектуры, потоки данных и Mermaid-диаграммы |
| [docs/PROJECT_STRUCTURE.md](docs/PROJECT_STRUCTURE.md) | Структура каталогов и назначение ключевых файлов |
| [docs/FEATURES.md](docs/FEATURES.md) | Подробное описание всех реализованных функций приложения |
| [docs/DATA_FORMATS.md](docs/DATA_FORMATS.md) | Схема Room v5, спецификации GPX, GeoJSON, GTFS и ZIP-архива |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | Руководство разработчика, стиль кода и правила доработки |
| [docs/TESTING.md](docs/TESTING.md) | Тестирование, запуск Unit и Instrumented тестов |
| [CHANGELOG.md](CHANGELOG.md) | История релизов и изменений |

---

## 📜 Авторство, лицензия и атрибуция

**Заявление об авторстве:**
Linienbegleiter — независимый Open Source-проект, инициированный и развиваемый автором с использованием ИИ-инструментов в процессе разработки.

**Атрибуция и данные карт:**
- Отрисовка карт выполняется с использованием **MapLibre Android SDK** (лицензия BSD 2-Clause).
- Векторные тайлы предоставляются сервисом **OpenFreeMap** на основе данных **OpenStreetMap** (лицензия ODbL).

**Лицензия:**
Проект подготовлен к Open Source публикации под лицензией **Apache License 2.0** (или MIT).
