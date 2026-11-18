# Linienbegleiter

[![Android CI](https://github.com/your-username/Linienbegleiter/actions/workflows/ci.yml/badge.svg)](https.github.com/)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com/)
[![SDK](https://img.shields.io/badge/API-26%2B%20%28Android%208.0%2B%29-blue.svg)](https://developer.android.com/about/versions/oreo)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-purple.svg)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Compose-Material%203-brightgreen.svg)](https://developer.android.com/jetpack/compose)
[![MapLibre](https://img.shields.io/badge/MapLibre-13.7.1-orange.svg)](https://maplibre.org/)
[![Version](https://img.shields.io/badge/Version-0.25-informational.svg)](CHANGELOG.md)

**Linienbegleiter** ist eine lokale Android-Anwendung zur Aufzeichnung von GPS-Routen des öffentlichen Personennahverkehrs (ÖPNV), zum manuellen Zeichnen und Bearbeiten von Liniengeometrie und Haltestellen, zur Navigation entlang gespeicherter Linien und zum Datenaustausch über ÖPNV-GIS-Standardformate (GPX, GeoJSON, GTFS und Sicherungsarchive).

Die Benutzeroberfläche ist in der Farbpalette des **Hamburger Verkehrsverbunds (HVV)** (*Hamburg Red, Elbe Blue, Harbor Navy, Brick, Sand*) gestaltet und unterstützt automatischen Hell-/Dunkelmodus über den Umgebungssensor.

---

## 📖 Sprachen / Languages / Языки

- [Deutsch (README_DE.md)](README_DE.md)
- [English (README_EN.md)](README_EN.md)
- [Русский (README.md)](README.md)

---

## ⭐ Hauptfunktionen

1. **Kartenansicht & Navigation (`MapScreen`):**
   - Vektorkarte mit MapLibre Android SDK (OpenFreeMap Liberty & Dark Styles).
   - Dynamische Positionsanzeige mit **Bewegungsvektor-Ausrichtung** während der Fahrt und **Kompass-Sensor-Rotation** (`Sensor.TYPE_ROTATION_VECTOR`) im Stand.
   - Automatische Routenbereichs-Zentrierung (`LatLngBounds`) beim Öffnen von Linien.
   - Schnellauswahl-Dropdown **"🚀 Los geht's"** direkt auf der Karte.

2. **GPS-Routenaufzeichnung (`RouteRecordingService`):**
   - Hintergrund-Standortaufzeichnung über Android Foreground Service mit Statusbenachrichtigungen.
   - Live-Erfassung von Distanz, Dauer, Höhe, Geschwindigkeit und Genauigkeit mit automatischer Speicherung alle 10 Sekunden.

3. **Routennavigation & Nachverfolgung (`RouteFollowingService`):**
   - Fortschrittsrechner (`RouteProgressCalculator`) für Reststrecke, Geschwindigkeit und nächste Haltestellen.
   - **Ankunft–Abfahrt-Engine:** Bei Erreichen einer Endhaltestelle werden automatisch nächste Fahrten vorgeschlagen (Rückfahrten, Linien ab derselben Haltestelle).

4. **Linien-Editor (`RouteEditorScreen`):**
   - Interaktive Geometriebearbeitung (`RouteSectionEditor`) mit verformbaren Abschnitten und automatischer Multi-Segment-Auswahl.
   - Haltestellen-Verwaltung mit Kartenverschiebung, Umbenennung und Reihenfolgeänderung.

5. **Multi-Format Import & Export:**
   - **GPX, GeoJSON, GTFS (ZIP/CSV)** und eigenes **Linienbegleiter-ZIP-Archiv** mit SHA-256-Prüfsummenvalidierung.

6. **Eco-Drive & Fahrstil-Analysator (`DrivingBehaviorAnalyzer`):**
   - Telemetrie in den Einstellungen zur Erfassung von Beschleunigungs- und Bremskräften sowie Live-Bewertung (0–100 Punkte / 5 Sterne).

---

## ⚡ Schnellstart & Build-Anweisungen

### Voraussetzungen
- JDK 17 oder JDK 21
- Android Studio (Ladybug / Jellyfish oder neuer)
- Android SDK 37

### Build-Befehle (Linux / macOS)

```bash
# Debug-APK erstellen
./gradlew :app:assembleDebug

# Unit-Tests ausführen
./gradlew :app:testDebugUnitTest
```

### Build-Befehle (Windows)

```cmd
gradlew.bat :app:assembleDebug
gradlew.bat :app:testDebugUnitTest
```

Die kompilierte APK befindet sich unter:
`app/build/outputs/apk/debug/app-debug.apk`

Ausführliche Anleitungen finden Sie unter [docs/BUILD.md](docs/BUILD.md).

---

## 📄 Lizenz & Autorenschaft

**Hinweis zur Autorenschaft:**
Linienbegleiter ist ein unabhängiges Open-Source-Projekt, das vom Autor unter Einsatz von KI-Entwicklungswerkzeugen entwickelt wurde.
