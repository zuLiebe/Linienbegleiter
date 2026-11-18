# Contributing to Linienbegleiter

Thank you for your interest in contributing to **Linienbegleiter**! We welcome bug fixes, documentation improvements, feature suggestions, and code contributions.

---

## Code of Conduct
This project and everyone participating in it is governed by the [Linienbegleiter Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

---

## How Can I Contribute?

### 1. Reporting Bugs
Before creating a bug report, please check existing issues to see if the problem has already been reported.
When creating a bug report, please include:
- A clear, descriptive title.
- Steps to reproduce the issue.
- Expected vs. actual behavior.
- Device model, Android version, and app version (`v0.25`).
- Relevant Logcat logs or screenshots.

### 2. Suggesting Enhancements
Feature requests are tracked as GitHub Issues. Provide a clear description of the feature, use cases, and why it would benefit users.

### 3. Submitting Pull Requests
- Fork the repository and create your branch from `main` or `master`.
- Keep pull requests focused on a single feature or bug fix.
- Ensure all Kotlin code adheres to the project's coding style and Kotlin conventions.
- Make sure existing unit tests pass (`./gradlew :app:testDebugUnitTest`).
- Update documentation in `docs/` if your changes affect features, APIs, or data models.

---

## Development Setup

1. **System Requirements:**
   - JDK 17 or JDK 21
   - Android Studio (Ladybug / Jellyfish or newer)
   - Android SDK API 37
2. **Build Commands:**
   ```bash
   ./gradlew :app:assembleDebug
   ./gradlew :app:testDebugUnitTest
   ```
3. Read [docs/BUILD.md](docs/BUILD.md) and [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for detailed guidelines.

---

## Coding Guidelines

- **Architecture:** Single-module `:app` architecture using Jetpack Compose (Material 3), Room Database, and MapLibre SDK.
- **State Management:** Unidirectional data flow using Kotlin `StateFlow` and Compose `remember` / `collectAsState`.
- **Database:** Do not modify Room entities in `data/local/` without incrementing database version and providing explicit migration scripts in `RecordingDatabase.kt`.
- **Map & Location:** Do not duplicate MapLibre layers or location providers. Use existing components in `MapScreen.kt` and `AndroidLocationProvider.kt`.

Thank you for helping make Linienbegleiter better!
