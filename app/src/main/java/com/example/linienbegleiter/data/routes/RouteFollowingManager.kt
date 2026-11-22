package com.example.linienbegleiter.data.routes

import com.example.linienbegleiter.AppPreferences
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
    private val locationConfig: LocationRequestConfig = LocationRequestConfig(),
    private val offRouteThresholdProvider: () -> Float = { AppPreferences.DEFAULT_OFF_ROUTE_THRESHOLD_METERS },
    private val streetResolver: (suspend (Double, Double) -> String?)? = null
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(RouteFollowingState())
    private var route: FollowingRoute? = null
    private var lastProgressMeters = 0.0

    private var consecutiveOffRouteCount = 0
    private var consecutiveOnRouteCount = 0
    private var isCurrentlyOffRoute = false
    private val streetTracker = StreetTracker(confirmationDurationMillis = 60_000L)

    val state: StateFlow<RouteFollowingState> = mutableState.asStateFlow()

    suspend fun startFollowing(
        routeId: String,
        source: RouteFollowSource,
        directionId: String? = null
    ) {
        mutex.withLock {
            if (mutableState.value.isActive &&
                mutableState.value.routeId == routeId &&
                mutableState.value.source == source &&
                mutableState.value.directionId == directionId
            ) return
            check(!mutableState.value.isActive) {
                "Сначала завершите текущее следование по маршруту."
            }
            val loadedRoute = loadRoute(routeId, source, directionId)
            require(loadedRoute.points.size >= 2) {
                "Для следования нужно минимум две точки пути."
            }
            route = loadedRoute
            lastProgressMeters = 0.0
            consecutiveOffRouteCount = 0
            consecutiveOnRouteCount = 0
            isCurrentlyOffRoute = false
            streetTracker.reset()
            mutableState.value = RouteFollowingState(
                routeId = loadedRoute.id,
                source = source,
                directionId = loadedRoute.directionId,
                title = loadedRoute.title,
                isActive = true,
                locationStatus = LocationProviderStatus.WaitingForSignal,
                routePoints = loadedRoute.points,
                stops = loadedRoute.stops,
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

    fun stopFollowing() {
        locationProvider.stop()
        route = null
        lastProgressMeters = 0.0
        consecutiveOffRouteCount = 0
        consecutiveOnRouteCount = 0
        isCurrentlyOffRoute = false
        streetTracker.reset()
        mutableState.value = RouteFollowingState()
    }

    fun reportError(message: String) {
        locationProvider.stop()
        route = null
        lastProgressMeters = 0.0
        consecutiveOffRouteCount = 0
        consecutiveOnRouteCount = 0
        isCurrentlyOffRoute = false
        streetTracker.reset()
        mutableState.value = mutableState.value.copy(
            isActive = false,
            errorMessage = message
        )
    }

    private suspend fun loadRoute(
        routeId: String,
        source: RouteFollowSource,
        directionId: String?
    ): FollowingRoute =
        when (source) {
            RouteFollowSource.ORIGINAL_RECORDING -> {
                val details = requireNotNull(recordingRepository.getDetails(routeId)) {
                    "Исходный маршрут не найден."
                }
                check(details.session.status == RecordingStatus.FINISHED) {
                    "Следовать можно только по завершённой GPS-записи."
                }
                FollowingRoute(
                    id = routeId,
                    directionId = null,
                    title = details.session.title,
                    points = details.points.map {
                        FollowingRoutePoint(
                            sequence = it.sequence,
                            latitude = it.latitude,
                            longitude = it.longitude,
                            timestampEpochMillis = it.timestampEpochMillis
                        )
                    },
                    stops = details.stops.mapIndexed { index, stop ->
                        FollowingRouteStop(
                            sequence = index,
                            name = stop.name,
                            latitude = stop.latitude,
                            longitude = stop.longitude,
                            id = null
                        )
                    }
                )
            }

            RouteFollowSource.EDITABLE_ROUTE -> {
                val details = requireNotNull(editableRouteRepository.getDetails(routeId)) {
                    "Маршрут не найден."
                }
                val direction = directionId?.let { selectedId ->
                    requireNotNull(details.directions.firstOrNull { it.id == selectedId }) {
                        "Выбранное направление не найдено."
                    }
                }
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
                    stops = directedStops
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

                val totalDist = snapshot?.totalDistanceMeters ?: 0.0
                val progDist = snapshot?.progressMeters ?: 0.0
                val isArrived = totalDist > 0.0 &&
                    (totalDist - progDist <= 50.0 || (snapshot?.nextStop == null && snapshot?.previousStop != null))

                val suggestions = if (isArrived && !oldState.isArrivedAtDestination) {
                    buildNextRouteSuggestions(loadedRoute, oldState.source)
                } else {
                    oldState.suggestedNextRoutes
                }

                val deviation = snapshot?.distanceToRouteMeters
                val threshold = offRouteThresholdProvider().toDouble()
                if (deviation != null && (sample.accuracyMeters == null || sample.accuracyMeters <= MAX_MATCH_ACCURACY_METERS)) {
                    if (deviation > threshold) {
                        consecutiveOffRouteCount++
                        consecutiveOnRouteCount = 0
                        if (consecutiveOffRouteCount >= 2) {
                            isCurrentlyOffRoute = true
                        }
                    } else {
                        consecutiveOnRouteCount++
                        consecutiveOffRouteCount = 0
                        if (consecutiveOnRouteCount >= 2) {
                            isCurrentlyOffRoute = false
                        }
                    }
                }

                val resolvedStreet = streetResolver?.invoke(sample.latitude, sample.longitude)
                val trackerResult = streetTracker.onLocationUpdate(
                    rawStreetName = resolvedStreet,
                    timestampEpochMillis = sample.timestampEpochMillis
                )

                mutableState.value = oldState.copy(
                    locationStatus = LocationProviderStatus.ProviderAvailable(sample.sourceProvider),
                    locationSample = sample,
                    progress = snapshot,
                    isOffRoute = isCurrentlyOffRoute,
                    offRouteDistanceMeters = deviation,
                    currentConfirmedStreet = trackerResult.confirmedStreet,
                    latestStreetAnnouncement = trackerResult.newAnnouncement ?: oldState.latestStreetAnnouncement,
                    isArrivedAtDestination = isArrived || oldState.isArrivedAtDestination,
                    suggestedNextRoutes = suggestions
                )
            }
        }
    }

    private suspend fun buildNextRouteSuggestions(
        currentRoute: FollowingRoute,
        source: RouteFollowSource?
    ): List<NextRouteSuggestion> = withContext(Dispatchers.IO) {
        val result = mutableListOf<NextRouteSuggestion>()
        val currentTerminalStop = currentRoute.stops.lastOrNull()?.name?.trim()
        val currentFirstStop = currentRoute.stops.firstOrNull()?.name?.trim()
        val currentTitle = currentRoute.title.trim()

        if (source == RouteFollowSource.EDITABLE_ROUTE) {
            val details = editableRouteRepository.getDetails(currentRoute.id)
            if (details != null && details.directions.isNotEmpty()) {
                val otherDirection = details.directions.firstOrNull { it.id != currentRoute.directionId }
                if (otherDirection != null) {
                    result += NextRouteSuggestion(
                        routeId = currentRoute.id,
                        source = RouteFollowSource.EDITABLE_ROUTE,
                        directionId = otherDirection.id,
                        title = currentRoute.title,
                        subtitle = "Обратный рейс · " + directionDescription(otherDirection.initialBearingDegrees),
                        isReturnTrip = true
                    )
                }
            }
        }

        val editableRoutes = editableRouteRepository.getRoutes()
        for (routeItem in editableRoutes) {
            if (routeItem.id == currentRoute.id && currentRoute.directionId == null) continue
            val details = editableRouteRepository.getDetails(routeItem.id) ?: continue
            val firstStopName = details.stops.firstOrNull()?.name?.trim()
            val lastStopName = details.stops.lastOrNull()?.name?.trim()

            val startsAtTerminal = !currentTerminalStop.isNullOrBlank() &&
                firstStopName?.equals(currentTerminalStop, ignoreCase = true) == true
            val endsAtFirstStop = !currentFirstStop.isNullOrBlank() &&
                lastStopName?.equals(currentFirstStop, ignoreCase = true) == true
            val isSimilarTitle = routeItem.title.equals(currentTitle, ignoreCase = true) ||
                (currentTitle.length >= 2 && routeItem.title.startsWith(currentTitle.take(2), ignoreCase = true))

            if (startsAtTerminal || endsAtFirstStop || isSimilarTitle) {
                val isReturn = (startsAtTerminal && endsAtFirstStop) || endsAtFirstStop
                val subtitle = when {
                    isReturn -> "Обратное направление"
                    startsAtTerminal -> "Отправление с остановки " + currentTerminalStop
                    else -> "Похожий маршрут"
                }
                if (details.directions.isNotEmpty()) {
                    val bestDir = details.directions.firstOrNull()
                    result += NextRouteSuggestion(
                        routeId = routeItem.id,
                        source = RouteFollowSource.EDITABLE_ROUTE,
                        directionId = bestDir?.id,
                        title = routeItem.title,
                        subtitle = subtitle,
                        isReturnTrip = isReturn
                    )
                } else {
                    result += NextRouteSuggestion(
                        routeId = routeItem.id,
                        source = RouteFollowSource.EDITABLE_ROUTE,
                        directionId = null,
                        title = routeItem.title,
                        subtitle = subtitle,
                        isReturnTrip = isReturn
                    )
                }
            }
        }

        result.distinctBy { Pair(it.routeId, it.directionId) }
            .sortedByDescending { it.isReturnTrip }
            .take(5)
    }

    private fun onLocationStatusChanged(status: LocationProviderStatus) {
        mutableState.value = mutableState.value.copy(locationStatus = status)
    }

    private data class FollowingRoute(
        val id: String,
        val directionId: String?,
        val title: String,
        val points: List<FollowingRoutePoint>,
        val stops: List<FollowingRouteStop>
    )

    private companion object {
        const val MAX_MATCH_ACCURACY_METERS = 100f
    }
}

internal object RouteProgressCalculator {
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
        val segments = buildSegments(points)
        val totalDistance = segments.lastOrNull()?.let {
            it.cumulativeStartMeters + it.lengthMeters
        } ?: 0.0
        val projections = segments.mapNotNull { project(latitude, longitude, points, it) }
        val nearbyCandidates = projections.filter {
            it.routeMeters >= (previousProgressMeters - BACKTRACK_WINDOW_METERS).coerceAtLeast(0.0) &&
                it.routeMeters <= previousProgressMeters + LOOKAHEAD_WINDOW_METERS
        }
        val closest = (nearbyCandidates.ifEmpty { projections })
            .minByOrNull(Projection::deviationMeters)
        val acceptedProgress = closest?.takeIf {
            it.deviationMeters <= MAX_ADVANCE_DISTANCE_METERS
        }?.routeMeters
        val progress = if (acceptedProgress == null) {
            previousProgressMeters.coerceIn(0.0, totalDistance)
        } else {
            maxOf(previousProgressMeters, acceptedProgress).coerceIn(0.0, totalDistance)
        }
        val displayProjection = projectionAtDistance(points, segments, progress)
        val orderedStops = stops.map { stop ->
            stop to stopDistanceMeters(stop, points, segments)
        }.filter { it.second != null }
            .sortedWith(compareBy<Pair<FollowingRouteStop, Double?>> { it.second }.thenBy { it.first.sequence })
        val previous = orderedStops.lastOrNull { (_, distance) ->
            requireNotNull(distance) < progress - STOP_PASSING_TOLERANCE_METERS
        }
        val next = orderedStops.firstOrNull { (_, distance) ->
            requireNotNull(distance) >= progress - STOP_PASSING_TOLERANCE_METERS
        }
        val distanceFromPreviousStop = previous?.second?.let { (progress - it).coerceAtLeast(0.0) }
        val distanceToNextStop = next?.second?.let { (it - progress).coerceAtLeast(0.0) }

        return RouteProgressSnapshot(
            progressMeters = progress,
            totalDistanceMeters = totalDistance,
            distanceToRouteMeters = closest?.deviationMeters,
            previousStop = previous?.first,
            distanceFromPreviousStopMeters = distanceFromPreviousStop,
            nextStop = next?.first,
            distanceToNextStopMeters = distanceToNextStop,
            remainingPoints = remainingRoute(points, segments, displayProjection)
        )
    }

    private fun buildSegments(points: List<FollowingRoutePoint>): List<Segment> {
        var cumulativeDistance = 0.0
        val result = mutableListOf<Segment>()
        for (index in 0 until points.lastIndex) {
            val first = points[index]
            val second = points[index + 1]
            if (!isContinuous(first, second)) continue
            val length = distanceMeters(
                first.latitude,
                first.longitude,
                second.latitude,
                second.longitude
            )
            if (length <= MIN_SEGMENT_LENGTH_METERS) continue
            result += Segment(index, index + 1, length, cumulativeDistance)
            cumulativeDistance += length
        }
        return result
    }

    private fun isContinuous(
        first: FollowingRoutePoint,
        second: FollowingRoutePoint
    ): Boolean {
        val firstTime = first.timestampEpochMillis ?: return true
        val secondTime = second.timestampEpochMillis ?: return true
        val timeDelta = secondTime - firstTime
        if (timeDelta <= 0L || timeDelta > MAX_CONNECTED_GAP_MILLIS) return false
        val distance = distanceMeters(
            first.latitude,
            first.longitude,
            second.latitude,
            second.longitude
        )
        return distance / (timeDelta / 1_000.0) <= MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND
    }

    private fun project(
        latitude: Double,
        longitude: Double,
        points: List<FollowingRoutePoint>,
        segment: Segment
    ): Projection {
        val first = points[segment.startIndex]
        val second = points[segment.endIndex]
        val radiansPerDegree = Math.PI / 180.0
        val referenceLatitude = Math.toRadians((first.latitude + second.latitude) / 2.0)
        val scaleX = EARTH_RADIUS_METERS * cos(referenceLatitude)
        val scaleY = EARTH_RADIUS_METERS
        val pointX = (longitude - first.longitude) * radiansPerDegree * scaleX
        val pointY = (latitude - first.latitude) * radiansPerDegree * scaleY
        val segmentX = (second.longitude - first.longitude) * radiansPerDegree * scaleX
        val segmentY = (second.latitude - first.latitude) * radiansPerDegree * scaleY
        val lengthSquared = segmentX * segmentX + segmentY * segmentY
        val fraction = ((pointX * segmentX + pointY * segmentY) / lengthSquared)
            .coerceIn(0.0, 1.0)
        val projectedX = segmentX * fraction
        val projectedY = segmentY * fraction
        val projectedLatitude = first.latitude +
            Math.toDegrees(projectedY / scaleY)
        val projectedLongitude = first.longitude +
            Math.toDegrees(projectedX / scaleX)
        return Projection(
            segment = segment,
            fraction = fraction,
            routeMeters = segment.cumulativeStartMeters + segment.lengthMeters * fraction,
            deviationMeters = hypot(pointX - projectedX, pointY - projectedY),
            latitude = projectedLatitude,
            longitude = projectedLongitude
        )
    }

    private fun projectionAtDistance(
        points: List<FollowingRoutePoint>,
        segments: List<Segment>,
        routeMeters: Double
    ): Projection? {
        val segment = segments.firstOrNull {
            routeMeters <= it.cumulativeStartMeters + it.lengthMeters
        } ?: return null
        val fraction = (
            (routeMeters - segment.cumulativeStartMeters) / segment.lengthMeters
            ).coerceIn(0.0, 1.0)
        val first = points[segment.startIndex]
        val second = points[segment.endIndex]
        val latitude = first.latitude + (second.latitude - first.latitude) * fraction
        val longitude = first.longitude + (second.longitude - first.longitude) * fraction
        return Projection(
            segment = segment,
            fraction = fraction,
            routeMeters = routeMeters,
            deviationMeters = 0.0,
            latitude = latitude,
            longitude = longitude
        )
    }

    private fun remainingRoute(
        points: List<FollowingRoutePoint>,
        segments: List<Segment>,
        projection: Projection?
    ): List<FollowingRoutePoint> {
        if (projection == null) return points
        val start = points[projection.segment.startIndex]
        val projectedPoint = FollowingRoutePoint(
            sequence = start.sequence,
            latitude = projection.latitude,
            longitude = projection.longitude
        )
        return listOf(projectedPoint) + points.drop(projection.segment.endIndex)
    }

    private fun stopDistanceMeters(
        stop: FollowingRouteStop,
        points: List<FollowingRoutePoint>,
        segments: List<Segment>
    ): Double? =
        segments.mapNotNull { project(stop.latitude, stop.longitude, points, it) }
            .minByOrNull(Projection::deviationMeters)
            ?.routeMeters

    private fun distanceMeters(
        latitudeA: Double,
        longitudeA: Double,
        latitudeB: Double,
        longitudeB: Double
    ): Double {
        val latitudeDelta = Math.toRadians(latitudeB - latitudeA)
        val longitudeDelta = Math.toRadians(longitudeB - longitudeA)
        val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(Math.toRadians(latitudeA)) *
            cos(Math.toRadians(latitudeB)) *
            sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt(1 - a))
    }

    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val MAX_CONNECTED_GAP_MILLIS = 60_000L
    private const val MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND = 45.0
    private const val MAX_ADVANCE_DISTANCE_METERS = 80.0
    private const val BACKTRACK_WINDOW_METERS = 80.0
    private const val LOOKAHEAD_WINDOW_METERS = 250.0
    private const val STOP_PASSING_TOLERANCE_METERS = 20.0
    private const val MIN_SEGMENT_LENGTH_METERS = 0.5
}

internal class StreetTracker(
    private val confirmationDurationMillis: Long = 60_000L
) {
    private var currentConfirmedStreet: String? = null
    private var pendingStreet: String? = null
    private var pendingStartTime: Long = 0L
    private var lastAnnouncedStreet: String? = null

    fun reset() {
        currentConfirmedStreet = null
        pendingStreet = null
        pendingStartTime = 0L
        lastAnnouncedStreet = null
    }

    data class Result(
        val confirmedStreet: String?,
        val newAnnouncement: StreetAnnouncementEvent?
    )

    fun onLocationUpdate(
        rawStreetName: String?,
        timestampEpochMillis: Long
    ): Result {
        val cleanStreet = rawStreetName?.trim()?.takeIf { it.isNotBlank() }
            ?: return Result(currentConfirmedStreet, null)

        if (cleanStreet.equals(currentConfirmedStreet, ignoreCase = true)) {
            pendingStreet = null
            pendingStartTime = 0L
            return Result(currentConfirmedStreet, null)
        }

        if (!cleanStreet.equals(pendingStreet, ignoreCase = true)) {
            pendingStreet = cleanStreet
            pendingStartTime = timestampEpochMillis
            return Result(currentConfirmedStreet, null)
        }

        val elapsed = timestampEpochMillis - pendingStartTime
        if (elapsed >= confirmationDurationMillis) {
            val newlyConfirmed = cleanStreet
            currentConfirmedStreet = newlyConfirmed
            pendingStreet = null
            pendingStartTime = 0L

            var event: StreetAnnouncementEvent? = null
            if (!newlyConfirmed.equals(lastAnnouncedStreet, ignoreCase = true)) {
                lastAnnouncedStreet = newlyConfirmed
                event = StreetAnnouncementEvent(
                    streetName = newlyConfirmed,
                    timestampEpochMillis = timestampEpochMillis
                )
            }
            return Result(currentConfirmedStreet, event)
        }

        return Result(currentConfirmedStreet, null)
    }
}
