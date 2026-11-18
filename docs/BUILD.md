# Environment Setup & Build Guide — Linienbegleiter

This document provides a comprehensive, step-by-step guide for developers to set up their environment, compile, run unit tests, and troubleshoot build issues for **Linienbegleiter**.

---

## 1. Environment & Tools Requirements

| Requirement | Verified Version | Notes |
|---|---|---|
| **Operating System** | Linux, macOS, or Windows 10/11 | 64-bit OS required |
| **JDK** | Java 17 or Java 21 | OpenJDK / Eclipse Temurin recommended |
| **Android Studio** | Ladybug / Jellyfish (2024.2.1+) | Android Studio with Kotlin 2.2.10 support |
| **Android SDK API** | Compile SDK 37, Min SDK 26 | Installed via Android Studio SDK Manager |
| **Android Gradle Plugin (AGP)** | 9.5.1 | Defined in `gradle/libs.versions.toml` |
| **Gradle** | 9.6.0 | Included via Gradle Wrapper (`gradlew`) |
| **Kotlin** | 2.2.10 | Kotlin KSP 2.3.10 |
| **Room Database** | 2.8.5 | Room DB schema v5 |

---

## 2. Opening & Configuring the Project in Android Studio

1. **Clone or Download the Repository:**
   ```bash
   git clone https://github.com/your-username/Linienbegleiter.git
   cd Linienbegleiter
   ```
2. **Open in Android Studio:**
   - Launch Android Studio.
   - Select **Open** and choose the `/media/user/329/Linienbegleiter` folder (or your cloned repository directory).
3. **Configure JDK 17:**
   - Go to `Settings / Preferences` ➔ `Build, Execution, Deployment` ➔ `Build Tools` ➔ `Gradle`.
   - Set **Gradle JDK** to Java 17 (Embedded JDK or Temurin 17).
4. **Gradle Sync:**
   - Click **Sync Project with Gradle Files** in the top toolbar.
   - Android Studio will automatically download AGP, Room KSP processors, MapLibre SDK, and Compose dependencies.

---

## 3. Command Line Build Commands

### Linux / macOS (bash / zsh)

```bash
# Ensure execution permissions for gradlew
chmod +x gradlew

# 1. Clean Build Directory
./gradlew clean

# 2. Assemble Debug APK
./gradlew :app:assembleDebug

# 3. Run Unit Tests
./gradlew :app:testDebugUnitTest

# 4. Compile Instrumented Tests
./gradlew :app:compileDebugAndroidTestKotlin

# 5. Run Instrumentation Tests (requires connected Android Device / Emulator)
./gradlew :app:connectedDebugAndroidTest
```

### Windows (Command Prompt / PowerShell)

```cmd
:: 1. Clean Build Directory
gradlew.bat clean

:: 2. Assemble Debug APK
gradlew.bat :app:assembleDebug

:: 3. Run Unit Tests
gradlew.bat :app:testDebugUnitTest

:: 4. Compile Instrumented Tests
gradlew.bat :app:compileDebugAndroidTestKotlin

:: 5. Run Instrumentation Tests (requires connected Android Device / Emulator)
gradlew.bat :app:connectedDebugAndroidTest
```

---

## 4. Output Artifacts Location

- **Debug APK:**
  `app/build/outputs/apk/debug/app-debug.apk`
- **Unit Test Reports (HTML):**
  `app/build/reports/tests/testDebugUnitTest/index.html`
- **Room Database Schemas (JSON):**
  `app/schemas/com.example.linienbegleiter.data.local.RecordingDatabase/5.json`

---

## 5. Installing APK on Device / Emulator

Using Android Debug Bridge (`adb`):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or click **Run 'app'** (`Shift + F10`) in Android Studio with your phone connected via USB debugging or an active Android Virtual Device (AVD).

---

## 6. Common Build Issues & Troubleshooting

### Issue 1: `Unsupported Java Version`
- **Symptom:** Gradle fails complaining about Java version mismatch.
- **Solution:** Ensure `JAVA_HOME` points to JDK 17 or JDK 21. Android Studio embedded JDK 17 is located in `android-studio/jbr`.

### Issue 2: `KSP Schema Export Directory Error`
- **Symptom:** Room compiler throws an error regarding schema location.
- **Solution:** `app/build.gradle.kts` sets `room.schemaLocation` to `$projectDir/schemas`. Ensure `app/schemas/` directory exists.

### Issue 3: `SDK Location Not Found`
- **Symptom:** `./gradlew` fails with "SDK location not found".
- **Solution:** Create a `local.properties` file in project root containing:
  ```properties
  sdk.dir=/home/username/Android/Sdk
  ```
  *(On Windows: `sdk.dir=C\:\\Users\\Username\\AppData\\Local\\Android\\Sdk`)*

### Issue 4: `Configuration Cache Stale`
- **Symptom:** Build behavior is inconsistent after modifying Gradle plugins.
- **Solution:** Run `./gradlew assembleDebug --rerun-tasks --no-daemon`.
