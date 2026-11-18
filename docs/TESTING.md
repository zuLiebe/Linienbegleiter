# Testing & Quality Assurance Guide — Linienbegleiter

This document details the unit test suite, instrumented tests, and verification procedures for **Linienbegleiter**.

---

## 1. Automated Test Suite Overview

| Test Type | Location | Target Component | Command |
|---|---|---|---|
| **JVM Unit Tests** | `app/src/test/` | Section Merging, GTFS Codec, GPX Codec, Archive Integrity, Palette | `./gradlew :app:testDebugUnitTest` |
| **Instrumented Android Tests** | `app/src/androidTest/` | Room DB Migrations (v1..v5), DAOs, Repositories | `./gradlew :app:connectedDebugAndroidTest` |

---

## 2. Verified JVM Unit Tests (`app/src/test/`)

The following unit tests are actively maintained and executed:

1. **`RouteSectionEditorTest`:** Verifies multi-segment route selection merging (`mergeRouteSections`), distance window expansion, and segment deletion.
2. **`GtfsCodecTest`:** Verifies GTFS Static ZIP feed parsing, `shapes.txt`, and `stops.txt` serialization.
3. **`GpxCodecTest`:** Verifies GPX XML parsing, waypoint extraction, and track export.
4. **`RouteFileCodecTest`:** Verifies GeoJSON feature collection encoding and decoding.
5. **`LinienbegleiterArchiveCodecTest`:** Verifies `.linienbegleiter.zip` creation, SHA-256 manifest verification, and corruption rejection.
6. **`RouteColorPaletteTest`:** Verifies Hamburg Transit color definitions and MapLibre hex conversion.
7. **`MapStopOverlayTest`:** Verifies stop projection and map overlay item mapping.

---

## 3. Running Unit Tests

To run all unit tests from terminal:

```bash
# Linux / macOS
./gradlew :app:testDebugUnitTest

# Windows
gradlew.bat :app:testDebugUnitTest
```

---

## 4. Running Room DB Migration Tests (`androidTest`)

Room database migrations (v1 ➔ v2 ➔ v3 ➔ v4 ➔ v5) are validated using Android Instrumentation tests against Room Migration Test Helper.

To execute on a connected device or emulator:

```bash
./gradlew :app:connectedDebugAndroidTest
```

---

## 5. Manual QA Checklist Before Release

- [ ] **App Launch:** App opens on clean install without crash.
- [ ] **Map View:** MapLibre vector tiles load, zoom controls work, location cursor displays correctly.
- [ ] **Compass Sensor:** Rotating phone in hand turns blue location arrow smoothly when standing still.
- [ ] **GPS Recording:** Starting recording acquires GPS, logs points, and updates foreground service status notification.
- [ ] **Route Navigation:** Following a route calculates progress, remaining distance, and triggers `ArrivalDeparturePanel` upon reaching final stop.
- [ ] **Route Editor:** Selecting segments merges selection span, dragging handle deforms geometry, and stop renaming works.
- [ ] **Import/Export:** Exporting and re-importing a GPX, GeoJSON, and GTFS file preserves geometry and stop names.
- [ ] **Theme Switching:** App toggles cleanly between Light, Dark, and Hamburg Transit palettes.
