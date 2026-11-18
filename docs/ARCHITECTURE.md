# Application Architecture — Linienbegleiter

This document details the architectural layout, subsystem interactions, state management, and data flow patterns of **Linienbegleiter**.

---

## 1. Architectural Overview

Linienbegleiter uses a **Single-Activity Architecture** built on Jetpack Compose (Material 3), Room Database, MapLibre SDK, and Android Foreground Services.

### Layer Diagram

```mermaid
graph TD
    subgraph UI ["UI Layer (Jetpack Compose)"]
        MA[MainActivity] --> MS[MapScreen]
        MA --> RS[RecordingScreen]
        MA --> FS[RouteFollowingScreen]
        MA --> ES[RouteEditorScreen]
        MA --> SS[SavedRoutesScreen]
        MA --> IS[ImportExportScreen]
    end

    subgraph Service ["Service & Management Layer"]
        RRS[RouteRecordingService] --> RM[RecordingManager]
        RFS[RouteFollowingService] --> RFM[RouteFollowingManager]
        DBA[DrivingBehaviorAnalyzer]
        RM --> DBA
    end

    subgraph Repo ["Repository Layer"]
        RecRepo[RoomRecordingRepository]
        EditRepo[RoomEditableRouteRepository]
        ArchRepo[RoomArchiveRepository]
    end

    subgraph Storage ["Data Layer (Room & GIS Codecs)"]
        RoomDB[(RecordingDatabase v5)]
        RecRepo --> RoomDB
        EditRepo --> RoomDB
        ArchRepo --> RoomDB
        Codecs[GPX / GeoJSON / GTFS / ZIP Codecs]
        ArchRepo --> Codecs
    end

    MS --> RecRepo
    MS --> EditRepo
    RS --> RRS
    FS --> RFS
    ES --> EditRepo
    SS --> RecRepo
    SS --> EditRepo
    IS --> ArchRepo
```

---

## 2. Core Subsystems & Responsibilities

### 2.1. UI Layer (`com.example.linienbegleiter`)
- **[MainActivity.kt](../app/src/main/java/com/example/linienbegleiter/MainActivity.kt):** Main entry point, screen navigation state machine (`AppScreen`), bottom navigation bar (`MainNavigationBar`), global theme switching (`LinienbegleiterTheme`), and Settings.
- **[MapScreen.kt](../app/src/main/java/com/example/linienbegleiter/MapScreen.kt):** MapLibre `MapView` container rendering vector map layers, stop overlay items, direction arrows, current position GPS cursor with **dynamic motion vector and hardware compass sensor rotation (`Sensor.TYPE_ROTATION_VECTOR`)**, auto route framing (`LatLngBounds`), and top floating action toolbar (`🚀 Drive`, `🔴 GPS Record`, zoom controls).
- **[RecordingScreen.kt](../app/src/main/java/com/example/linienbegleiter/RecordingScreen.kt):** Dashboard for active GPS recording, tracking duration, distance, points, stops, and real-time Eco-Drive telemetry.
- **[RouteFollowingScreen.kt](../app/src/main/java/com/example/linienbegleiter/RouteFollowingScreen.kt):** Route navigation screen displaying progress, next stop, arrival-departure suggestions, and back-button confirmation dialog.
- **[RouteEditorScreen.kt](../app/src/main/java/com/example/linienbegleiter/RouteEditorScreen.kt):** Route geometry editor with multi-segment merged selection, framed stop cards, and route appearance controls.

### 2.2. Service & Foreground Layer (`com.example.linienbegleiter.service`)
- **RouteRecordingService:** Android Foreground Service (`location` type) executing background GPS logging and periodic checkpointing every 10 seconds.
- **RouteFollowingService:** Android Foreground Service (`location` type) driving route navigation, updating progress notifications, and providing quick action buttons for next-trip startup.

### 2.3. Data & Repository Layer (`com.example.linienbegleiter.data`)
- **[RecordingManager.kt](../app/src/main/java/com/example/linienbegleiter/data/recording/RecordingManager.kt):** Manages active GPS recording state, filtering GPS noise (`MAX_PLAUSIBLE_SPEED`), and integrating `DrivingBehaviorAnalyzer`.
- **[RouteFollowingManager.kt](../app/src/main/java/com/example/linienbegleiter/data/routes/RouteFollowingManager.kt):** Calculates progress along route polyline (`RouteProgressCalculator`), calculates deviation, and builds arrival-departure candidate suggestions (`buildNextRouteSuggestions`).
- **[RouteSectionEditor.kt](../app/src/main/java/com/example/linienbegleiter/data/routes/RouteSectionEditor.kt):** Spatial route section editor providing distance-based window selections, multi-segment selection merging (`mergeRouteSections`), smooth falloff transformation (`smoothInfluence`), and segment deletion.

---

## 3. Data Flow Diagrams

### 3.1. GPS Recording Data Flow

```mermaid
sequenceDiagram
    autonumber
    participant Sensor as Android Location API
    participant Service as RouteRecordingService
    participant Manager as RecordingManager
    participant Analyzer as DrivingBehaviorAnalyzer
    participant Repo as RoomRecordingRepository
    participant DB as Room Database (v5)

    Service->>Manager: startRecording(recordingId)
    Sensor->>Service: LocationSample (lat, lon, speed, accuracy)
    Service->>Manager: addLocationPoint(sample)
    Manager->>Analyzer: updateSample(speed, timestamp)
    Analyzer-->>Manager: DrivingAnalysisStats (score, harsh braking)
    Manager->>Repo: appendPoint(recordingId, point)
    Repo->>DB: INSERT INTO recording_points
    Manager->>Service: updateNotification(state)
```

---

### 3.2. Route Geometry Editing & Saving Flow

```mermaid
sequenceDiagram
    autonumber
    participant User as User (Map Gesture)
    participant Editor as RouteEditorScreen
    participant Section as RouteSectionEditor
    participant Repo as RoomEditableRouteRepository
    participant DB as Room Database (v5)

    User->>Editor: Tap Route Segment
    Editor->>Section: selectRouteSection(points, sequence, fraction)
    User->>Editor: Tap Second Segment
    Editor->>Section: mergeRouteSections(existing, newSelection)
    Section-->>Editor: Merged RouteSectionSelection (startDist..endDist)
    User->>Editor: Drag Handle / Delete Section
    Editor->>Section: moveRouteSection() / deleteRouteSection()
    Section-->>Editor: Updated List<EditableRoutePoint>
    User->>Editor: Click "Save Route"
    Editor->>Repo: updateRoute(routeId, title, points, stops)
    Repo->>DB: UPDATE editable_routes & REPLACE points/stops
```

---

### 3.3. Import & Export Flow

```mermaid
sequenceDiagram
    autonumber
    participant UI as ImportExportScreen
    participant ArchRepo as RoomArchiveRepository
    participant Codecs as Interchange Codecs (GPX / GeoJSON / GTFS / ZIP)
    participant DB as Room Database (v5)

    alt Import File
        UI->>ArchRepo: importFile(inputStream, format)
        ArchRepo->>Codecs: decode(inputStream)
        Codecs-->>ArchRepo: InterchangeRoute (points, stops)
        ArchRepo->>DB: insertEditableRoute(route, points, stops)
    else Export File
        UI->>ArchRepo: exportFile(routeId, outputStream, format)
        ArchRepo->>DB: getDetails(routeId)
        DB-->>ArchRepo: EditableRouteDetails
        ArchRepo->>Codecs: encode(InterchangeRoute, outputStream)
    end
```
