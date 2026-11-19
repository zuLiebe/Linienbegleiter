package com.example.linienbegleiter.data.routes

import com.example.linienbegleiter.data.location.LocationProviderStatus
import com.example.linienbegleiter.data.location.LocationSample

enum class RouteFollowSource {
    ORIGINAL_RECORDING,
    EDITABLE_ROUTE
}

data class FollowingRoutePoint(
    val sequence: Int,
    val latitude: Double,
    val longitude: Double,
    val timestampEpochMillis: Long? = null
)

data class FollowingRouteStop(
    val sequence: Int,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val routePointSequence: Int? = null,
    val id: Long? = null
)

data class RouteProgressSnapshot(
    val progressMeters: Double,
    val totalDistanceMeters: Double,
    val distanceToRouteMeters: Double?,
    val previousStop: FollowingRouteStop?,
    val distanceFromPreviousStopMeters: Double?,
    val nextStop: FollowingRouteStop?,
    val distanceToNextStopMeters: Double?,
    val remainingPoints: List<FollowingRoutePoint>
)

data class NextRouteSuggestion(
    val routeId: String,
    val source: RouteFollowSource,
    val directionId: String? = null,
    val title: String,
    val subtitle: String,
    val isReturnTrip: Boolean = false
)

data class RouteFollowingState(
    val routeId: String? = null,
    val source: RouteFollowSource? = null,
    val directionId: String? = null,
    val title: String = "",
    val isActive: Boolean = false,
    val locationStatus: LocationProviderStatus = LocationProviderStatus.WaitingForSignal,
    val locationSample: LocationSample? = null,
    val routePoints: List<FollowingRoutePoint> = emptyList(),
    val stops: List<FollowingRouteStop> = emptyList(),
    val zones: List<RouteZone> = emptyList(),
    val activeZone: RouteZone? = null,
    val activeZoneTargetZoom: Float? = null,
    val progress: RouteProgressSnapshot? = null,
    val errorMessage: String? = null,
    val isArrivedAtDestination: Boolean = false,
    val suggestedNextRoutes: List<NextRouteSuggestion> = emptyList()
)
