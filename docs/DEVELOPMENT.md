# Developer Guidelines & Workflow — Linienbegleiter

This document provides development standards, coding conventions, architectural practices, and safety rules for contributing to **Linienbegleiter**.

---

## 1. Core Engineering Principles

1. **Maintain Architectural Consistency:**
   Follow established patterns. Use Room repositories (`RoomRecordingRepository`, `RoomEditableRouteRepository`), Compose screens, and Foreground Services. Do not create duplicate repositories or secondary map screens.
2. **Preserve Room Database Integrity:**
   Never modify Room Entity classes in `data/local/` without incrementing database version in `RecordingDatabase.kt` and adding explicit migration code.
3. **MapLibre Layer Management:**
   All map layers and sources are added dynamically in `MapScreen.kt`. When adding new map overlays, create dedicated `GeoJsonSource` and `Layer` IDs in `MapScreen.kt` constants and clean them up when dismounting.
4. **Foreground Services & Location:**
   Location updates are managed by `AndroidLocationProvider.kt` and dispatched to `RouteRecordingService` or `RouteFollowingService`. Do not instantiate duplicate location listeners in composables.

---

## 2. Kotlin & Compose Style Guidelines

- **Idiomatic Kotlin:** Use `val` by default, immutable data classes, extension functions, and Coroutines `Flow` for reactive streams.
- **Compose State Management:** Keep composables stateless where possible. Pass state down as parameters and events up as lambdas.
- **State Hoisting:**
  ```kotlin
  @Composable
  fun RouteCard(
      title: String,
      onEditClicked: () -> Unit
  ) { ... }
  ```
- **Unidirectional Data Flow:** Use `collectAsState()` to observe flows exposed by repositories or managers.

---

## 3. Safe Workflow for Adding Features

### Adding a New Route Property
1. Update domain models in `data/routes/EditableRouteModels.kt`.
2. Update Room Entity in `data/local/EditableRouteEntities.kt`.
3. Increment `version = 6` in `RecordingDatabase.kt`.
4. Add Migration script (`MIGRATION_5_6`) executing `ALTER TABLE editable_routes ADD COLUMN ...`.
5. Update `EditableRouteDao.kt` and `RoomEditableRouteRepository.kt`.
6. Update UI in `RouteEditorScreen.kt`.
7. Add Unit Test verifying entity mapping and migration.

### Testing Map & GPS Features Safely
- Use Android Emulator location mock tools or custom GPX tracks to test GPS recording and route following without physical movement.
- Verify that `MapScreen.kt` camera bounds calculations handle edge cases (e.g. empty points or single-point routes).
