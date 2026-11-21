# Linienbegleiter 🚍

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?style=flat&logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![MapLibre](https://img.shields.io/badge/MapLibre-GL_Native-4285F4?style=flat)](https://maplibre.org)

**Linienbegleiter** is a modern Android application designed for public transit drivers, passengers, and transit navigation enthusiasts. Built with **Jetpack Compose** and **MapLibre GL**, it delivers high performance, buttery-smooth navigation, and an ergonomic PUBG-style tactical HUD interface.

---

## ✨ Key Features

### 1. 🗺 Smooth GPS Following & Zero Flickering
* Completely eliminated street name and marker flickering by separating static route rendering from dynamic location updates.
* Buttery-smooth frame-by-frame interpolation of GPS marker movement between locations.
* Graceful fallback preserving the last known position during temporary GPS signal loss.

### 2. 🎮 Tactical Gaming HUD Interface (PUBG Style)
* Maximum map visibility: Clean, uncluttered screen space with minimalist floating overlays.
* Compact route title capsule in the top-left corner.
* Quick simulation controls in the top-right: Green play arrow (`▶ Start`) and red square stop (`⏹ Stop`).
* Ultra-compact bottom tactical HUD bar featuring next stop guidance, distance meters, and quick recenter controls.

### 3. 📐 3D Perspective & Speed-Based Dynamic Zoom
* **3D / 2D Toggle**: Instant toggle between top-down 2D view and 3D navigation perspective (45° tilt) right from the map HUD.
* **Speed-Based Auto-Zoom**: In route following mode, the map automatically and smoothly adjusts its zoom level based on your driving speed:
  - Stopped / Crawling (< 10 km/h) $\rightarrow$ Zoom **17.5** (maximum detail).
  - City driving (10–30 km/h) $\rightarrow$ Zoom **16.5**.
  - Medium speed (30–60 km/h) $\rightarrow$ Zoom **15.5**.
  - Fast driving (> 60 km/h) $\rightarrow$ Zoom **14.5** (wide view ahead).

### 4. ⚠️ Off-Route Detection & Alerts
* Real-time deviation monitoring comparing current GPS position against the recorded route track.
* Configurable off-route threshold in settings (10m to 100m, default 25m).
* Noise filtering (hysteresis over consecutive fixes) and instant red warning banners upon leaving the route.

### 5. ⚙️ Full HUD Customization & Multi-Language Support
* **HUD Toggles**: Easily enable or disable any map control buttons (Zoom buttons, 3D toggle, Compass, Speed badge) from the Settings menu.
* **Global Multi-Language Support**: Full localization for English, German, Russian, Ukrainian, Vietnamese, Chinese, Arabic, Persian, Swahili, Amharic, and Afrikaans, with a first-run language selection wizard.

### 6. 🔴 Route Recording & Editing
* Real-time GPS track and stop recording.
* Advanced route editor with support for multiple directions and sections.
* Interchange support (Import/Export).

---

## 🛠 Tech Stack

* **UI:** Jetpack Compose, Material 3, Custom HUD overlays.
* **Maps:** MapLibre GL Native Android SDK.
* **Architecture:** MVVM, Kotlin Coroutines & Flow.
* **Database:** Room (SQLite) for offline route persistence.

---

## 📦 Installation & Build

1. Clone the repository:
   ```bash
   git clone https://github.com/zuLiebe/Linienbegleiter.git
   ```
2. Open the project in **Android Studio**.
3. Build and run the app:
   ```bash
   ./gradlew assembleDebug
   ```

---

## 📄 License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
