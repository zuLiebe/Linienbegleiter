package com.example.linienbegleiter.data.routes

enum class EditableRouteKind {
    MANUAL,
    RECORDING_EDIT,
    VARIANT,
    FILE_IMPORT,
    GTFS_IMPORT
}

data class EditableRoute(
    val id: String,
    val title: String,
    val createdAtEpochMillis: Long,
    val sourceOriginalTrackId: String?,
    val distanceMeters: Double,
    val routeKind: EditableRouteKind = EditableRouteKind.MANUAL,
    val parentRouteId: String? = null,
    val routeColorArgb: Int = 0,
    val routeLineWidth: Float = 5f,
    val routeMetadataJson: String? = null
)

data class EditableRoutePoint(
    val sequence: Int,
    val latitude: Double,
    val longitude: Double,
    val id: Long = 0L
)

data class EditableRouteStop(
    val sequence: Int,
    val routePointSequence: Int?,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val id: Long = 0L
)

data class EditableRouteDirection(
    val id: String,
    val routeId: String,
    val name: String,
    val startStopId: Long,
    val endStopId: Long,
    val initialBearingDegrees: Double,
    val isGeometryReversed: Boolean,
    val stopIdsInOrder: List<Long>
)

enum class RouteZoneAnnotationType {
    PLATFORM,
    APPROACH_LINE,
    ARROW,
    LABEL
}

data class RouteZoneAnnotation(
    val id: Long = 0L,
    val zoneId: String,
    val annotationType: RouteZoneAnnotationType,
    val label: String? = null,
    val geometryJson: String
)

data class RouteZone(
    val id: String,
    val routeId: String,
    val title: String,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val radiusMeters: Double,
    val targetZoom: Float = 18.0f,
    val annotations: List<RouteZoneAnnotation> = emptyList(),
    val metadataJson: String? = null
)

data class EditableRouteDetails(
    val route: EditableRoute,
    val points: List<EditableRoutePoint>,
    val stops: List<EditableRouteStop>,
    val directions: List<EditableRouteDirection> = emptyList(),
    val zones: List<RouteZone> = emptyList()
)

data class RouteDraftStop(
    val routePointSequence: Int?,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val id: Long = 0L
)
