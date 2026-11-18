# Data Formats & Storage Specifications — Linienbegleiter

This document details the internal database schema, supported export/import formats, and backup archive specifications for **Linienbegleiter**.

---

## 1. Local Database Schema (Room v5)

Database File: `linienbegleiter.db` (Schema Version = 5).

### 1.1. Entities Overview

| Table Name | Primary Key | Description |
|---|---|---|
| `recordings` | `id` (String UUID) | GPS recording session metadata (title, status, duration, distance, colors). |
| `original_tracks` | `id` (String UUID) | Immutable original GPS track metadata (FK to `recordings`). |
| `recording_points` | `id` (Long autoincrement) | Raw recorded GPS coordinates (latitude, longitude, timestamp, accuracy, speed, bearing). |
| `recording_stops` | `id` (Long autoincrement) | Stops marked during live GPS recording. |
| `editable_routes` | `id` (String UUID) | User-edited or manually created routes. |
| `editable_route_points` | `id` (Long autoincrement) | Ordered polyline points of editable routes. |
| `editable_route_stops` | `id` (Long autoincrement) | Stops attached to editable routes. |
| `editable_route_directions` | `id` (String UUID) | Route directions (outbound/return, start/end stop IDs, initial bearing). |
| `editable_route_direction_stops` | `id` (Long autoincrement) | Ordered stop sequences within specific directions. |

---

## 2. Supported Import & Export Formats

### 2.1. GPX (GPS Exchange Format)
- **File extension:** `.gpx`
- **Specification:** Standard XML layout with `<trk>` / `<trkseg>` for route polylines and `<wpt>` for stops/stations.
- **Parser (`GpxCodec.kt`):** Best-effort XML parsing with entity resolution protection against external XML entities.

### 2.2. GeoJSON (GIS Format)
- **File extension:** `.geojson` / `.json`
- **Specification:** `FeatureCollection` containing:
  - `LineString` feature representing route geometry.
  - `Point` features representing stops with `"name"` properties.

### 2.3. GTFS Static & GTFS CSV
- **File extension:** `.zip` / `.csv`
- **Files parsed/generated:**
  - `shapes.txt` — Route shape polylines (`shape_id`, `shape_pt_lat`, `shape_pt_lon`, `shape_pt_sequence`).
  - `stops.txt` — Station list (`stop_id`, `stop_name`, `stop_lat`, `stop_lon`).
  - `routes.txt` & `trips.txt` — Basic route metadata and trip bindings.

### 2.4. Linienbegleiter ZIP Backup Archive (`LinienbegleiterArchiveCodec.kt`)
- **File extension:** `.linienbegleiter.zip` / `.zip`
- **Archive contents:**
  1. `manifest.json`:
     ```json
     {
       "format": "LINIENBEGLEITER_ARCHIVE",
       "format_version": 1,
       "payload_file": "data.json",
       "sha256": "4a7c8e9b..."
     }
     ```
  2. `data.json`: Complete JSON dump of all recordings, tracks, points, stops, editable routes, and directions.
- **Integrity Validation:** Calculates SHA-256 digest of `data.json` upon import and verifies against `manifest.json` before restoring. Limit: 128 MB max archive size.

---

## 3. Data Format Disclaimer & Compatibility

- **`.busway` files:** Unsupported. Do not claim compatibility with `.busway` formats.
- **Conversion Fidelity:** Exporting a route to GPX or GeoJSON preserves spatial geometry and stop names, but GTFS schedule fields (e.g. arrival times) are generated using nominal defaults as Linienbegleiter focuses on geometry and progress rather than timetable management.
