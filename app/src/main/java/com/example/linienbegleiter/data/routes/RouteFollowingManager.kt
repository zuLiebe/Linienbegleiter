package com.example.linienbegleiter.data.routes

import com.example.linienbegleiter.data.location.LocationProvider
import com.example.linienbegleiter.data.location.LocationProviderStatus
import com.example.linienbegleiter.data.location.LocationRequestConfig
import com.example.linienbegleiter.data.location.LocationSample
import com.example.linienbegleiter.data.recording.RecordingRepository
import com.example.linienbegleiter.data.recording.RecordingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class RouteFollowingManager(
    private val recordingRepository: RecordingRepository,
    private val editableRouteRepository: EditableRouteRepository,
    private val locationProvider: LocationProvider,
    private val scope: CoroutineScope,
    private val locationConfig: LocationRequestConfig = LocationRequestConfig()
) {
    private val mutableState = MutableStateFlow(RouteFollowingState())
    val state: StateFlow<RouteFollowingState> = mutableState.asStateFlow()

    private val mutex = Mutex()
    private var route: FollowingRoute? = null
    private var lastProgressMeters = 0.0

    suspend fun start(
        routeId: String,
        source: RouteFollowSource,
        directionId: String? = null
    ) = withContext(Dispatchers.Default) {
        mutex.withLock {
            val currentState = mutableState.value
            require(!currentState.isActive || (currentState.routeId == routeId && currentState.source == source && currentState.directionId == directionId)) {
                "Сначала завершите текущее следование по маршруту."
            }
            val loadedRoute = loadRoute(routeId, source, directionId)
            require(loadedRoute.points.size >= 2) {
                "Для следования нужно минимум две точки пути."
            }
            route = loadedRoute
            lastProgressMeters = 0.0
            mutableState.value = RouteFollowingState(
                routeId = loadedRoute.id,
                source = source,
                directionId = loadedRoute.directionId,
                title = loadedRoute.title,
                isActive = true,
                locationStatus = LocationProviderStatus.WaitingForSignal,
                routePoints = loadedRoute.points,
                stops = loadedRoute.stops,
                zones = loadedRoute.zones,
                progress = RouteProgressCalculator.calculate(
                    loadedRoute.points,
                    loadedRoute.stops,
                    latitude = loadedRoute.points.first().latitude,
                    longitude = loadedRoute.points.first().longitude,
                    previousProgressMeters = 0.0
                )
            )
        }

        locationProvider.start(
            config = locationConfig,
            onSample = ::onLocationSample,
            onStatusChanged = ::onLocationStatusChanged
        )
    }

    suspend fun stop() = withContext(Dispatchers.Default) {
        locationProvider.stop()
        mutex.withLock {
            route = null
            lastProgressMeters = 0.0
            mutableState.value = RouteFollowingState()
        }
    }

    fun stopFollowing() {
        scope.launch(Dispatchers.Default) { stop() }
    }

    suspend fun startFollowing(
        routeId: String,
        source: RouteFollowSource,
        directionId: String? = null
    ) = start(routeId, source, directionId)

    fun reportError(message: String) {
        scope.launch(Dispatchers.Default) {
            mutex.withLock {
                mutableState.value = mutableState.value.copy(errorMessage = message, isActive = false)
            }
        }
    }

    private suspend fun loadRoute(
        routeId: String,
        source: RouteFollowSource,
        directionId: String?
    ): FollowingRoute =
        when (source) {
            RouteFollowSource.ORIGINAL_RECORDING -> {
                val details = requireNotNull(recordingRepository.getDetails(routeId)) {
                    "Исходная запись не найдена."
                }
                check(details.session.status == RecordingStatus.FINISHED) {
                    "Следование можно запустить только по завершённой записи."
                }
                FollowingRoute(
                    id = routeId,
                    directionId = null,
                    title = details.session.title,
                    points = details.points.mapIndexed { index, point ->
                        FollowingRoutePoint(
                            sequence = index,
                            latitude = point.latitude,
                            longitude = point.longitude,
                            timestampEpochMillis = point.timestampEpochMillis
                        )
                    },
                    stops = details.stops.mapIndexed { index, stop ->
                        FollowingRouteStop(
                            sequence = index,
                            name = stop.name,
                            latitude = stop.latitude,
                            longitude = stop.longitude,
                            routePointSequence = null,
                            id = null
                        )
                    },
                    zones = emptyList()
                )
            }

            RouteFollowSource.EDITABLE_ROUTE -> {
                val details = requireNotNull(editableRouteRepository.getDetails(routeId)) {
                    "Маршрут не найден."
                }
                val direction = directionId?.let { selectedId ->
                    requireNotNull(details.directions.firstOrNull { it.id == selectedId }) {
                        "Направление маршрута не найдено."
                    }
                } ?: details.directions.firstOrNull()

                val sourcePoints = details.points.map {
                    FollowingRoutePoint(
                        sequence = it.sequence,
                        latitude = it.latitude,
                        longitude = it.longitude
                    )
                }
                val directedPoints = if (direction?.isGeometryReversed == true) {
                    sourcePoints.asReversed().mapIndexed { index, point ->
                        point.copy(sequence = index)
                    }
                } else {
                    sourcePoints
                }
                val stopsById = details.stops.associateBy { it.id }
                val directedStops = if (direction == null) {
                    details.stops.map {
                        FollowingRouteStop(
                            sequence = it.sequence,
                            name = it.name,
                            latitude = it.latitude,
                            longitude = it.longitude,
                            routePointSequence = it.routePointSequence,
                            id = it.id
                        )
                    }
                } else {
                    direction.stopIdsInOrder.mapIndexedNotNull { sequence, stopId ->
                        stopsById[stopId]?.let { stop ->
                            FollowingRouteStop(
                                sequence = sequence,
                                name = stop.name,
                                latitude = stop.latitude,
                                longitude = stop.longitude,
                                routePointSequence = stop.routePointSequence,
                                id = stop.id
                            )
                        }
                    }
                }
                FollowingRoute(
                    id = routeId,
                    directionId = direction?.id,
                    title = details.route.title,
                    points = directedPoints,
                    stops = directedStops,
                    zones = details.zones
                )
            }
        }

    private fun onLocationSample(sample: LocationSample) {
        scope.launch(Dispatchers.Default) {
            mutex.withLock {
                val loadedRoute = route ?: return@withLock
                val oldState = mutableState.value
                if (!oldState.isActive) return@withLock

                val snapshot = if (
                    sample.isStale ||
                    (sample.accuracyMeters != null && sample.accuracyMeters > MAX_MATCH_ACCURACY_METERS)
                ) {
                    oldState.progress
                } else {
                    RouteProgressCalculator.calculate(
                        points = loadedRoute.points,
                        stops = loadedRoute.stops,
                        latitude = sample.latitude,
                        longitude = sample.longitude,
                        previousProgressMeters = lastProgressMeters
                    ).also { lastProgressMeters = it.progressMeters }
                }

                val currentLat = sample.latitude
                val currentLon = sample.longitude
                val existingActiveZone = oldState.activeZone

                val evaluatedActiveZone = if (existingActiveZone != null) {
                    val distToExisting = calculateDistanceMeters(currentLat, currentLon, existingActiveZone.centerLatitude, existingActiveZone.centerLongitude)
                    if (distToExisting <= existingActiveZone.radiusMeters + 25.0) { // 25m hysteresis buffer
                        existingActiveZone
                    } else {
                        loadedRoute.zones.firstOrNull { zone ->
                            calculateDistanceMeters(currentLat, currentLon, zone.centerLatitude, zone.centerLongitude) <= zone.radiusMeters
                        }
                    }
                } else {
                    loadedRoute.zones.firstOrNull { zone ->
                        calculateDistanceMeters(currentLat, currentLon, zone.centerLatitude, zone.centerLongitude) <= zone.radiusMeters
                    }
                }

                val totalDist = snapshot?.totalDistanceMeters ?: 0.0
                val progDist = snapshot?.progressMeters ?: 0.0
                val isArrived = totalDist > 0.0 &&
                    (totalDist - progDist <= 50.0 || (snapshot?.nextStop == null && snapshot?.previousStop != null))

                val suggestions = if (isArrived && !oldState.isArrivedAtDestination) {
                    buildNextRouteSuggestions(loadedRoute, oldState.source)
                } else {
                    oldState.suggestedNextRoutes
                }

                mutableState.value = oldState.copy(
                    locationStatus = LocationProviderStatus.ProviderAvailable(sample.sourceProvider),
                    locationSample = sample,
                    progress = snapshot,
                    activeZone = evaluatedActiveZone,
                    activeZoneTargetZoom = evaluatedActiveZone?.targetZoom,
                    isArrivedAtDestination = isArrived || oldState.isArrivedAtDestination,
                    suggestedNextRoutes = suggestions
                )
            }
        }
    }

    private suspend fun buildNextRouteSuggestions(
        currentRoute: FollowingRoute,
        source: RouteFollowSource?
    ): List<NextRouteSuggestion> {
        val result = mutableListOf<NextRouteSuggestion>()
        if (source == RouteFollowSource.EDITABLE_ROUTE && currentRoute.directionId != null) {
            val details = editableRouteRepository.getDetails(currentRoute.id)
            if (details != null && details.directions.isNotEmpty()) {
                val otherDirection = details.directions.firstOrNull { it.id != currentRoute.directionId }
                if (otherDirection != null) {
                    result.add(
                        NextRouteSuggestion(
                            routeId = currentRoute.id,
                            source = RouteFollowSource.EDITABLE_ROUTE,
                            directionId = otherDirection.id,
                            title = "Обратный рейс: " + otherDirection.name,
                            subtitle = "Обратный рейс · " + directionDescription(otherDirection.initialBearingDegrees),
                            isReturnTrip = true
                        )
                    )
                }
            }
        }

        val allRoutes = editableRouteRepository.getRoutes()
        for (routeItem in allRoutes) {
            if (routeItem.id == currentRoute.id && currentRoute.directionId == null) continue
            val details = editableRouteRepository.getDetails(routeItem.id) ?: continue
            val lastStop = currentRoute.stops.lastOrNull()
            if (lastStop != null) {
                if (details.directions.isNotEmpty()) {
                    val bestDir = details.directions.firstOrNull()
                    result.add(
                        NextRouteSuggestion(
                            routeId = routeItem.id,
                            source = RouteFollowSource.EDITABLE_ROUTE,
                            directionId = bestDir?.id,
                            title = routeItem.title,
                            subtitle = "Маршрут от этой станции",
                            isReturnTrip = false
                        )
                    )
                } else {
                    result.add(
                        NextRouteSuggestion(
                            routeId = routeItem.id,
                            source = RouteFollowSource.EDITABLE_ROUTE,
                            directionId = null,
                            title = routeItem.title,
                            subtitle = "Сохранённый маршрут",
                            isReturnTrip = false
                        )
                    )
                }
            }
        }
        return result.distinctBy { Pair(it.routeId, it.directionId) }
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun onLocationStatusChanged(status: LocationProviderStatus) {
        scope.launch(Dispatchers.Default) {
            mutex.withLock {
                val oldState = mutableState.value
                if (!oldState.isActive) return@withLock
                mutableState.value = oldState.copy(locationStatus = status)
            }
        }
    }

    private data class FollowingRoute(
        val id: String,
        val directionId: String?,
        val title: String,
        val points: List<FollowingRoutePoint>,
        val stops: List<FollowingRouteStop>,
        val zones: List<RouteZone> = emptyList()
    )

    private companion object {
        const val MAX_MATCH_ACCURACY_METERS = 100f
    }
}

private object RouteProgressCalculator {
    private data class Segment(
        val startIndex: Int,
        val endIndex: Int,
        val lengthMeters: Double,
        val cumulativeStartMeters: Double
    )

    private data class Projection(
        val segment: Segment,
        val fraction: Double,
        val routeMeters: Double,
        val deviationMeters: Double,
        val latitude: Double,
        val longitude: Double
    )

    fun calculate(
        points: List<FollowingRoutePoint>,
        stops: List<FollowingRouteStop>,
        latitude: Double,
        longitude: Double,
        previousProgressMeters: Double
    ): RouteProgressSnapshot {
        if (points.size < 2) {
            return RouteProgressSnapshot(
                progressMeters = 0.0,
                totalDistanceMeters = 0.0,
                distanceToRouteMeters = null,
                previousStop = stops.firstOrNull(),
                distanceFromPreviousStopMeters = null,
                nextStop = stops.getOrNull(1),
                distanceToNextStopMeters = null,
                remainingPoints = points
            )
        }

        var cumulative = 0.0
        val segments = points.zipWithNext().mapIndexed { index, (first, second) ->
            val length = distanceMeters(
                first.latitude,
                first.longitude,
                second.latitude,
                second.longitude
            )
            val segment = Segment(index, index + 1, length, cumulative)
            cumulative += length
            segment
        }
        val totalDistance = cumulative

        val projections = segments.map { segment ->
            val first = points[segment.startIndex]
            val second = points[segment.endIndex]
            projectPointOnSegment(
                pointLat = latitude,
                pointLon = longitude,
                segmentLat1 = first.latitude,
                segmentLon1 = first.longitude,
                segmentLat2 = second.latitude,
                segmentLon2 = second.longitude,
                segment = segment
            )
        }

        val bestProjection = projections
            .minByOrNull { projection ->
                val progressDifference = Math.abs(projection.routeMeters - previousProgressMeters)
                projection.deviationMeters + progressDifference * 0.12
            }
            ?: projections.minByOrNull { it.deviationMeters }
            ?: Projection(segments.first(), 0.0, 0.0, 0.0, latitude, longitude)

        val boundedProgress = bestProjection.routeMeters.coerceIn(
            previousProgressMeters,
            totalDistance
        )

        val remainingPoints = mutableListOf<FollowingRoutePoint>()
        remainingPoints.add(
            FollowingRoutePoint(
                sequence = bestProjection.segment.startIndex,
                latitude = bestProjection.latitude,
                longitude = bestProjection.longitude
            )
        )
        remainingPoints.addAll(points.drop(bestProjection.segment.endIndex))

        val stopProjections = stops.map { stop ->
            val stopProjection = projections.minByOrNull { projection ->
                distanceMeters(
                    stop.latitude,
                    stop.longitude,
                    projection.latitude,
                    projection.longitude
                )
            }
            val routeMeters = stopProjection?.routeMeters ?: 0.0
            stop to routeMeters
        }

        val previousStopEntry = stopProjections
            .filter { it.second <= boundedProgress + 1.0 }
            .maxByOrNull { it.second }
        val nextStopEntry = stopProjections
            .filter { it.second > boundedProgress + 1.0 }
            .minByOrNull { it.second }

        val previousStop = previousStopEntry?.first ?: stops.firstOrNull()
        val nextStop = nextStopEntry?.first

        val distanceFromPreviousStop = previousStopEntry?.let {
            (boundedProgress - it.second).coerceAtLeast(0.0)
        }
        val distanceToNextStop = nextStopEntry?.let {
            (it.second - boundedProgress).coerceAtLeast(0.0)
        }

        return RouteProgressSnapshot(
            progressMeters = boundedProgress,
            totalDistanceMeters = totalDistance,
            distanceToRouteMeters = bestProjection.deviationMeters,
            previousStop = previousStop,
            distanceFromPreviousStopMeters = distanceFromPreviousStop,
            nextStop = nextStop,
            distanceToNextStopMeters = distanceToNextStop,
            remainingPoints = remainingPoints
        )
    }

    private fun projectPointOnSegment(
        pointLat: Double,
        pointLon: Double,
        segmentLat1: Double,
        segmentLon1: Double,
        segmentLat2: Double,
        segmentLon2: Double,
        segment: Segment
    ): Projection {
        val deltaLat = segmentLat2 - segmentLat1
        val deltaLon = segmentLon2 - segmentLon1
        val segmentLengthSq = deltaLat * deltaLat + deltaLon * deltaLon

        if (segmentLengthSq < 1e-12) {
            val deviation = distanceMeters(pointLat, pointLon, segmentLat1, segmentLon1)
            return Projection(
                segment = segment,
                fraction = 0.0,
                routeMeters = segment.cumulativeStartMeters,
                deviationMeters = deviation,
                latitude = segmentLat1,
                longitude = segmentLon1
            )
        }

        val fraction = (
            (pointLat - segmentLat1) * deltaLat +
                (pointLon - segmentLon1) * deltaLon
            ) / segmentLengthSq
        val clampedFraction = fraction.coerceIn(0.0, 1.0)

        val projectedLat = segmentLat1 + clampedFraction * deltaLat
        val projectedLon = segmentLon1 + clampedFraction * deltaLon

        val routeMeters = segment.cumulativeStartMeters + clampedFraction * segment.lengthMeters
        val deviationMeters = distanceMeters(pointLat, pointLon, projectedLat, projectedLon)

        return Projection(
            segment = segment,
            fraction = clampedFraction,
            routeMeters = routeMeters,
            deviationMeters = deviationMeters,
            latitude = projectedLat,
            longitude = projectedLon
        )
    }

    private fun distanceMeters(
        latitude1: Double,
        longitude1: Double,
        latitude2: Double,
        longitude2: Double
    ): Double {
        val earthRadiusMeters = 6_371_000.0
        val latitudeDelta = Math.toRadians(latitude2 - latitude1)
        val longitudeDelta = Math.toRadians(longitude2 - longitude1)
        val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(Math.toRadians(latitude1)) *
            cos(Math.toRadians(latitude2)) *
            sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return 2 * earthRadiusMeters * atan2(sqrt(a), sqrt(1 - a))
    }
}
