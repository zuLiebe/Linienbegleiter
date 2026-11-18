# Project Structure — Linienbegleiter

This document provides a annotated map of the **Linienbegleiter** project repository directory tree and file responsibilities.

---

## Repository Tree Overview

```text
Linienbegleiter/
├── .github/                        # GitHub infrastructure templates and CI workflows
│   ├── ISSUE_TEMPLATE/             # Bug report and Feature request issue templates
│   │   ├── bug_report.md
│   │   └── feature_request.md
│   ├── PULL_REQUEST_TEMPLATE.md    # Pull Request description and checklist template
│   └── workflows/
│       └── ci.yml                  # GitHub Actions CI workflow (Build & Unit Tests)
├── docs/                           # Comprehensive technical & user documentation
│   ├── ARCHITECTURE.md             # Subsystem map, state management, and Mermaid diagrams
│   ├── BUILD.md                    # Environment setup, Gradle commands, and build guide
│   ├── DATA_FORMATS.md             # Room schema v5, GPX, GeoJSON, GTFS, and ZIP specs
│   ├── DEVELOPMENT.md              # Developer guidelines, code style, and safety rules
│   ├── FEATURES.md                 # Complete breakdown of all implemented capabilities
│   ├── PROJECT_STRUCTURE.md        # Annotated project tree and file map (this document)
│   └── TESTING.md                  # Unit testing, instrumentation, and QA procedures
├── app/                            # Android Application Module (:app)
│   ├── build.gradle.kts            # Application Gradle build script (SDK 37, dependencies)
│   ├── schemas/                    # Exported Room database JSON schemas (v5)
│   └── src/
│       ├── androidTest/            # Android Instrumented Integration Tests
│       │   └── java/com/example/linienbegleiter/
│       │       ├── data/local/RecordingDatabaseMigrationTest.kt  # Room DB Migration v1->v5 tests
│       │       ├── data/local/RoomArchiveRepositoryTest.kt      # Archive Repository tests
│       │       ├── data/recording/RecordingRepositoryTest.kt    # Recording Repository tests
│       │       └── data/routes/EditableRouteRepositoryTest.kt   # Route Repository tests
│       ├── main/
│       │   ├── AndroidManifest.xml # App Manifest (Permissions, Services, MainActivity)
│       │   ├── java/com/example/linienbegleiter/
│       │   │   ├── AppPreferences.kt                # SharedPreferences manager (Theme, Eco-Drive)
│       │   │   ├── EditableRouteCreationScreen.kt   # Manual route creation wizard
│       │   │   ├── ImportExportScreen.kt            # Storage Access Framework import/export UI
│       │   │   ├── MainActivity.kt                  # Activity entry point, navigation, SettingsScreen
│       │   │   ├── MapScreen.kt                     # MapLibre map UI, stop overlay, compass arrow
│       │   │   ├── RecordingScreen.kt               # GPS Recording dashboard & Eco-Drive stats
│       │   │   ├── RouteAppearanceControls.kt       # Line color palette and width picker
│       │   │   ├── RouteEditorScreen.kt             # Route geometry editor UI & stop cards
│       │   │   ├── RouteFollowingScreen.kt          # Route navigation UI & Arrival-Departure panel
│       │   │   ├── SavedRoutesScreen.kt             # Saved routes catalog UI
│       │   │   ├── data/
│       │   │   │   ├── interchange/                 # Multi-format GIS data codecs
│       │   │   │   │   ├── GeoJsonCodec.kt          # GeoJSON FeatureCollection encoder/decoder
│       │   │   │   │   ├── GpxCodec.kt              # GPX XML parser and generator
│       │   │   │   │   ├── GtfsCodec.kt             # GTFS Static ZIP feed encoder/decoder
│       │   │   │   │   ├── GtfsCsvCodec.kt          # GTFS shapes & stops CSV parser
│       │   │   │   │   ├── InterchangeModels.kt     # Neutral InterchangeRoute models
│       │   │   │   │   ├── JsonCodec.kt             # Lightweight JSON parser/serializer
│       │   │   │   │   └── LinienbegleiterArchiveCodec.kt # Secure ZIP backup codec with SHA-256
│       │   │   │   ├── local/                       # Room Local Database
│       │   │   │   │   ├── EditableRouteDao.kt      # DAOs for editable routes & directions
│       │   │   │   │   ├── EditableRouteDirectionEntities.kt # Direction entity classes
│       │   │   │   │   ├── EditableRouteEntities.kt # Route, point, stop entities
│       │   │   │   │   ├── RecordingDaos.kt         # Recording DAOs
│       │   │   │   │   ├── RecordingDatabase.kt     # Room Database v5 with migrations v1->v5
│       │   │   │   │   ├── RecordingEntities.kt     # Recording session, track, point entities
│       │   │   │   │   └── RoomArchiveRepository.kt # Backup & restore repository
│       │   │   │   ├── location/                    # Location Provider
│       │   │   │   │   ├── AndroidLocationProvider.kt # Fused/LocationManager provider implementation
│       │   │   │   │   ├── LocationProvider.kt      # LocationProvider interface & status
│       │   │   │   │   └── LocationSample.kt        # LocationSample data model
│       │   │   │   ├── recording/                   # Recording Domain Logic
│       │   │   │   │   ├── DrivingBehaviorAnalyzer.kt # Eco-Drive telemetry & scoring
│       │   │   │   │   ├── RecordingManager.kt      # Active recording manager
│       │   │   │   │   ├── RecordingModels.kt       # Session domain models
│       │   │   │   │   └── RecordingRepository.kt   # RoomRecordingRepository implementation
│       │   │   │   └── routes/                      # Route Editing & Following Logic
│       │   │   │       ├── EditableRouteModels.kt   # Editable route domain models
│       │   │   │       ├── EditableRouteRepository.kt # RoomEditableRouteRepository implementation
│       │   │   │       ├── RouteColorPalette.kt     # Hamburg Transit color palette definitions
│       │   │   │       ├── RouteDirectionUtils.kt   # Direction & bearing utilities
│       │   │   │       ├── RouteFollowingManager.kt # Progress calculator & arrival-departure engine
│       │   │   │       ├── RouteFollowingModels.kt  # Following state & suggestion models
│       │   │   │       ├── RouteSectionEditor.kt    # Spatial section editor & merge algorithm
│       │   │   │       └── RouteTitlePolicy.kt      # Unique title generator
│       │   │   ├── service/                         # Android Foreground Services
│       │   │   │   ├── RouteFollowingService.kt     # Navigation Foreground Service
│       │   │   │   └── RouteRecordingService.kt     # GPS Recording Foreground Service
│       │   │   └── ui/theme/                        # Material 3 Theming
│       │   │       ├── Color.kt                     # Hamburg Transit color definitions
│       │   │       ├── Theme.kt                     # LinienbegleiterTheme (Light/Dark/Auto)
│       │   │       └── Type.kt                      # Typography definitions
│       │   └── res/                                 # App Resources (Drawables, Values, Strings)
│       │       ├── drawable/                        # Vector icons (ic_gps_bus, ic_route_direction)
│       │       └── values/                          # App strings, styles, and themes
│       └── test/                                    # Unit Tests (JVM)
│           └── java/com/example/linienbegleiter/
│               ├── MapStopOverlayTest.kt            # Overlay item mapping tests
│               ├── data/interchange/                # Codec unit tests (GTFS, GPX, Archive)
│               └── data/routes/                      # Route palette & section editor unit tests
├── .gitignore                      # Git exclusion rules
├── .gitlab-ci.yml                  # GitLab CI pipeline definition
├── AI_README.md                    # Technical log & invariants for AI development
├── CHANGELOG.md                    # Project release history & SemVer changelog
├── CODE_OF_CONDUCT.md              # Contributor Covenant Code of Conduct
├── CONTRIBUTING.md                 # Contribution guidelines
├── README.md                       # Main Russian README
├── README_DE.md                    # German README
├── README_EN.md                    # English README
├── SECURITY.md                     # Security & vulnerability disclosure policy
├── SUPPORT.md                      # Support resources & issue guidelines
├── build.gradle.kts                # Root Gradle build file
├── gradle/                         # Gradle Wrapper files & Version Catalog
│   └── libs.versions.toml          # Centralized dependency versions (AGP, Room, MapLibre, Kotlin)
├── gradlew                         # Linux/macOS Gradle wrapper script
├── gradlew.bat                     # Windows Gradle wrapper script
└── settings.gradle.kts             # Gradle settings script
```
