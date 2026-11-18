# Linienbegleiter

[![Android CI](https://github.com/your-username/Linienbegleiter/actions/workflows/ci.yml/badge.svg)](https.github.com/)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com/)
[![SDK](https://img.shields.io/badge/API-26%2B%20%28Android%208.0%2B%29-blue.svg)](https://developer.android.com/about/versions/oreo)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-purple.svg)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Compose-Material%203-brightgreen.svg)](https://developer.android.com/jetpack/compose)
[![MapLibre](https://img.shields.io/badge/MapLibre-13.7.1-orange.svg)](https://maplibre.org/)
[![Version](https://img.shields.io/badge/Version-0.25-informational.svg)](CHANGELOG.md)

**Linienbegleiter** ("Line Companion") is a local Android application designed for recording GPS public transit routes, manually drawing and editing route geometry and stops, navigating along saved lines, and exchanging route data across standard transit GIS formats (GPX, GeoJSON, GTFS, and backup archives).

The project UI is designed in the **Hamburg Transit** palette (*Hamburg Red, Elbe Blue, Harbor Navy, Brick, Sand*) with full dark, light, and ambient-sensor automatic theme switching.

---

## 📖 Languages / Sprachen / Языки

- [English (README_EN.md)](README_EN.md)
- [Deutsch (README_DE.md)](README_DE.md)
- [Русский (README.md)](README.md)

---

## ⭐ Key Features

1. **Map View & Navigation (`MapScreen`):**
   - High-performance vector map rendered with MapLibre Android SDK (OpenFreeMap Liberty & Dark styles).
   - Dynamic user location indicator with **motion vector orientation** during travel and **hardware compass sensor rotation** (`Sensor.TYPE_ROTATION_VECTOR`) when stationary.
   - Automatic route bounds framing (`LatLngBounds`) when opening or selecting any line.
   - Quick-access **"🚀 Drive"** floating dropdown list for Instant Route Selection directly over the map.

2. **GPS Route Recording (`RouteRecordingService`):**
   - Background location acquisition via Android Foreground Service (`location` type) with persistent status notifications.
   - Real-time tracking of distance, duration, elevation, speed, accuracy, and checkpointing every 10 seconds.
   - On-the-fly stop addition with location snapping.

3. **Route Navigation & Following (`RouteFollowingService`):**
   - Real-time progress calculator (`RouteProgressCalculator`) tracking remaining distance, current speed, deviation, and upcoming stops.
   - **Arrival–Departure Engine:** Upon reaching a terminal station, the app detects arrival and offers intelligent next-route suggestions (reverse trips, routes starting from the same station, or similar line numbers).
   - Notification actions allowing next-trip startup directly from the Android notification shade.

4. **Visual Route Geometry Editor (`RouteEditorScreen`):**
   - Interactive section editing (`RouteSectionEditor`) with smooth spatial deformation (`smoothInfluence`) and automatic multi-segment selection merging.
   - Full control over stop names, sequence ordering, point insertion, geometry reversal, and stop snapping.
   - Framed stop cards with one-tap rename, move on map, and delete actions.

5. **Multi-Format Import & Export:**
   - **GPX:** Standard GPS exchange format for tracks and waypoints.
   - **GeoJSON:** Geographic feature collections for line strings and stop features.
   - **GTFS & GTFS CSV:** Public transit schedule & shape specifications (`shapes.txt`, `stops.txt`, `routes.txt`, `trips.txt`).
   - **Linienbegleiter Archive ZIP:** Custom backup bundle with `manifest.json`, `data.json`, and SHA-256 integrity verification.

6. **Eco-Drive & Driving Behavior Analyzer (`DrivingBehaviorAnalyzer`):**
   - Global telemetry toggle in Settings monitoring acceleration and deceleration G-forces.
   - Real-time scoring (0–100 score and 5-star rating) tracking harsh braking, rapid acceleration, and speeding.

---

## 🛠 Tech Stack & Architecture

- **Language:** 100% Kotlin
- **UI Framework:** Jetpack Compose with Material 3 & Edge-to-Edge support
- **Map Engine:** MapLibre Android SDK 13.7.1
- **Database:** Room 2.8.5 (v5 schema with KSP code generation and migration scripts)
- **Concurrency:** Kotlin Coroutines & `StateFlow` / `Flow`
- **Background Work:** 2× Android Foreground Services (`location`)
- **Build System:** AGP 9.5.1, Gradle 9.6.0, JDK 17 / 21

---

## ⚡ Quick Start & Build Instructions

### Prerequisites
- JDK 17 or JDK 21
- Android Studio (Ladybug / Jellyfish or newer)
- Android SDK 37

### Build Commands (Linux / macOS)

```bash
# Assemble Debug APK
./gradlew :app:assembleDebug

# Run Unit Tests
./gradlew :app:testDebugUnitTest

# Compile Instrumented Test Suite
./gradlew :app:compileDebugAndroidTestKotlin
```

### Build Commands (Windows Command Prompt / PowerShell)

```cmd
:: Assemble Debug APK
gradlew.bat :app:assembleDebug

:: Run Unit Tests
gradlew.bat :app:testDebugUnitTest
```

The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

For detailed build and environment setup, read [docs/BUILD.md](docs/BUILD.md).

---

## 📚 Documentation Index

| Document | Description |
|---|---|
| [docs/BUILD.md](docs/BUILD.md) | Comprehensive build, environment, and troubleshooting guide |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Architecture map, data flows, and Mermaid diagrams |
| [docs/PROJECT_STRUCTURE.md](docs/PROJECT_STRUCTURE.md) | Complete directory tree and file responsibilities |
| [docs/FEATURES.md](docs/FEATURES.md) | Detailed breakdown of all implemented capabilities |
| [docs/DATA_FORMATS.md](docs/DATA_FORMATS.md) | Room schema (v5), GPX, GeoJSON, GTFS, and `.zip` archive specs |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | Developer workflow, code conventions, and safety rules |
| [docs/TESTING.md](docs/TESTING.md) | Unit testing, instrumentation, and QA verification procedures |
| [CHANGELOG.md](CHANGELOG.md) | Project release history and SemVer policy |

---

## 📜 Authorship, Attribution & Open Source Disclosure

**Authorship Disclosure:**
Linienbegleiter is an independent Open Source project initiated and developed by the author using AI engineering tooling during development.

**Attribution & Map Data:**
- Map rendering powered by **MapLibre Android SDK** under the BSD 2-Clause License.
- Vector map tiles provided by **OpenFreeMap** using **OpenStreetMap** data under Open Database License (ODbL).

---

## 📄 License

Linienbegleiter is prepared for Open Source distribution under the **Apache License 2.0** (or MIT License). See project licensing documentation for details.
