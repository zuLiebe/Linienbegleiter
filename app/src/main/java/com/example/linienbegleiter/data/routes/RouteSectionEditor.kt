package com.example.linienbegleiter.data.routes

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A short, local editing window centered on a point along the route.
 *
 * The selection is stored by distance along the route rather than by point index. This keeps the
 * selected place stable when sparse geometry is temporarily densified to make it editable.
 */
data class RouteSectionSelection(
    val startDistanceMeters: Double,
    val endDistanceMeters: Double
) {
    val centerDistanceMeters: Double get() = (startDistanceMeters + endDistanceMeters) / 2.0
    val radiusMeters: Double get() = (endDistanceMeters - startDistanceMeters) / 2.0
    val lengthMeters: Double get() = (endDistanceMeters - startDistanceMeters).coerceAtLeast(0.0)

    companion object {
        fun fromCenterAndRadius(
            centerDistanceMeters: Double,
            radiusMeters: Double = DEFAULT_ROUTE_SECTION_RADIUS_METERS
        ): RouteSectionSelection = RouteSectionSelection(
            startDistanceMeters = (centerDistanceMeters - radiusMeters).coerceAtLeast(0.0),
            endDistanceMeters = centerDistanceMeters + radiusMeters
        )
    }
}

data class RouteCoordinate(
    val latitude: Double,
    val longitude: Double
)

const val DEFAULT_ROUTE_SECTION_RADIUS_METERS = 90.0
private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val ROUTE_SAMPLE_SPACING_METERS = 18.0
private const val DISTANCE_EPSILON_METERS = 0.25

/**
 * Selects a local window around a tapped segment. The range is measured along the route, so a
 * dense GPS track and a hand-drawn two-point line receive the same editing behavior.
 */
fun selectRouteSection(
    points: List<EditableRoutePoint>,
    segmentSequence: Int,
    segmentFraction: Double,
    radiusMeters: Double = DEFAULT_ROUTE_SECTION_RADIUS_METERS
): RouteSectionSelection? {
    if (points.size < 2 || segmentSequence !in 0 until points.lastIndex) return null
    if (!radiusMeters.isFinite() || radiusMeters <= 0.0) return null

    val fraction = segmentFraction.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: return null
    val cumulative = cumulativeRouteDistances(points)
    val totalDistance = cumulative.last()
    val segmentLength = cumulative[segmentSequence + 1] - cumulative[segmentSequence]
    val centerDistance = cumulative[segmentSequence] + segmentLength * fraction
    val startDistance = (centerDistance - radiusMeters).coerceAtLeast(0.0)
    val endDistance = (centerDistance + radiusMeters).coerceAtMost(totalDistance)
    return RouteSectionSelection(
        startDistanceMeters = startDistance,
        endDistanceMeters = endDistance
    )
}

/**
 * Merges a newly selected segment into an existing section selection, expanding the selection
 * to continuously cover all selected segments.
 */
fun mergeRouteSections(
    existing: RouteSectionSelection?,
    newSelection: RouteSectionSelection,
    totalDistanceMeters: Double
): RouteSectionSelection {
    if (existing == null) return newSelection
    val minStart = minOf(existing.startDistanceMeters, newSelection.startDistanceMeters)
        .coerceIn(0.0, totalDistanceMeters)
    val maxEnd = maxOf(existing.endDistanceMeters, newSelection.endDistanceMeters)
        .coerceIn(0.0, totalDistanceMeters)
    return RouteSectionSelection(
        startDistanceMeters = minStart,
        endDistanceMeters = maxEnd
    )
}

/** Returns the total cumulative length of a route in meters. */
fun routeTotalDistanceMeters(points: List<EditableRoutePoint>): Double =
    cumulativeRouteDistances(points).lastOrNull() ?: 0.0

/** Returns the geographic position at a distance along the route. */
fun routeCoordinateAtDistance(
    points: List<EditableRoutePoint>,
    distanceMeters: Double
): RouteCoordinate? {
    if (points.isEmpty() || !distanceMeters.isFinite()) return null
    val cumulative = cumulativeRouteDistances(points)
    return coordinateAtDistance(points, cumulative, distanceMeters.coerceIn(0.0, cumulative.last()))
        ?.let { RouteCoordinate(it.latitude, it.longitude) }
}

/** Returns only the selected section, including interpolated boundaries and edit samples. */
fun routeSectionPoints(
    points: List<EditableRoutePoint>,
    selection: RouteSectionSelection
): List<EditableRoutePoint> =
    createRouteSectionEditSession(points, selection)?.originalSectionPoints.orEmpty()

/**
 * Precomputed edit window. The drag preview only transforms this local section; the full route is
 * copied and committed once when the gesture ends.
 */
class RouteSectionEditSession internal constructor(
    private val originalPoints: List<EditableRoutePoint>,
    private val selection: RouteSectionSelection,
    private val center: EditableRoutePoint,
    private val totalDistanceMeters: Double,
    private val sampleDistancesMeters: List<Double>,
    private val pointsBeforeSection: List<EditableRoutePoint>,
    private val originalSection: List<EditableRoutePoint>,
    private val pointsAfterSection: List<EditableRoutePoint>
) {
    val originalSectionPoints: List<EditableRoutePoint> =
        originalSection.mapIndexed { index, point -> point.copy(sequence = index) }

    fun preview(target: RouteCoordinate): List<EditableRoutePoint> {
        if (!target.isValid()) return originalSectionPoints
        val deltaLatitude = target.latitude - center.latitude
        val rawLongitudeDelta = target.longitude - center.longitude
        val deltaLongitude = ((rawLongitudeDelta + 540.0) % 360.0) - 180.0
        if (abs(deltaLatitude) < 1e-12 && abs(deltaLongitude) < 1e-12) {
            return originalSectionPoints
        }

        val centerDistance = selection.centerDistanceMeters
            .coerceIn(0.0, totalDistanceMeters)
        return originalSection.mapIndexed { index, point ->
            val distance = sampleDistancesMeters[index]
            val weight = smoothInfluence(
                distanceFromCenter = abs(distance - centerDistance),
                radiusMeters = selection.radiusMeters
            )
            val isRouteEndpoint = distance <= DISTANCE_EPSILON_METERS ||
                totalDistanceMeters - distance <= DISTANCE_EPSILON_METERS
            val appliedWeight = if (isRouteEndpoint) 0.0 else weight
            point.copy(
                sequence = index,
                latitude = (point.latitude + deltaLatitude * appliedWeight).coerceIn(-90.0, 90.0),
                longitude = normalizeLongitude(point.longitude + deltaLongitude * appliedWeight)
            )
        }
    }

    fun commit(target: RouteCoordinate): List<EditableRoutePoint> {
        if (!target.isValid()) return originalPoints
        val movedSection = preview(target)
        if (movedSection == originalSectionPoints) return originalPoints
        return (pointsBeforeSection + movedSection + pointsAfterSection)
            .mapIndexed { index, point -> point.copy(sequence = index) }
    }

    /**
     * Removes the selected span and joins the remaining route at its cut boundaries.
     * Returns null when the deletion would leave fewer than two distinct route points.
     */
    fun delete(): List<EditableRoutePoint>? {
        val startDistance = selection.startDistanceMeters.coerceAtLeast(0.0)
        val endDistance = selection.endDistanceMeters.coerceAtMost(totalDistanceMeters)
        val remaining = buildList {
            addAll(pointsBeforeSection)
            if (startDistance > DISTANCE_EPSILON_METERS) {
                originalSection.firstOrNull()?.let(::add)
            }
            if (totalDistanceMeters - endDistance > DISTANCE_EPSILON_METERS) {
                originalSection.lastOrNull()?.let(::add)
            }
            addAll(pointsAfterSection)
        }.mapIndexed { index, point -> point.copy(sequence = index) }

        if (remaining.size < 2) return null
        if (remaining.zipWithNext().all { (first, second) ->
                distanceMeters(first, second) <= DISTANCE_EPSILON_METERS
            }
        ) {
            return null
        }
        return remaining
    }
}

/** Builds a reusable section editor so drag previews do not rescan the full route each frame. */
fun createRouteSectionEditSession(
    points: List<EditableRoutePoint>,
    selection: RouteSectionSelection
): RouteSectionEditSession? {
    if (points.size < 2 || !selection.isValid()) return null
    val cumulative = cumulativeRouteDistances(points)
    val totalDistance = cumulative.last()
    if (totalDistance <= DISTANCE_EPSILON_METERS) return null

    val startDistance = selection.startDistanceMeters.coerceIn(0.0, totalDistance)
    val endDistance = selection.endDistanceMeters.coerceIn(0.0, totalDistance)
    if (endDistance - startDistance <= DISTANCE_EPSILON_METERS) return null

    val centerDistance = ((startDistance + endDistance) / 2.0).coerceIn(0.0, totalDistance)
    val center = coordinateAtDistance(points, cumulative, centerDistance) ?: return null

    val sampleDistances = sectionSampleDistances(cumulative, startDistance, endDistance)
    val section = sampleDistances.mapNotNull { distance ->
        coordinateAtDistance(points, cumulative, distance)
    }
    if (section.size < 2) return null

    val before = points.indices
        .filter { cumulative[it] < startDistance - DISTANCE_EPSILON_METERS }
        .map { points[it] }
    val after = points.indices
        .filter { cumulative[it] > endDistance + DISTANCE_EPSILON_METERS }
        .map { points[it] }

    return RouteSectionEditSession(
        originalPoints = points,
        selection = RouteSectionSelection(
            startDistanceMeters = startDistance,
            endDistanceMeters = endDistance
        ),
        center = center,
        totalDistanceMeters = totalDistance,
        sampleDistancesMeters = sampleDistances,
        pointsBeforeSection = before,
        originalSection = section,
        pointsAfterSection = after
    )
}

/**
 * Moves a selected section with a smooth local falloff. Sparse geometry receives temporary control
 * samples inside the edit window, so the driver does not have to manipulate individual vertices.
 */
fun moveRouteSection(
    points: List<EditableRoutePoint>,
    selection: RouteSectionSelection,
    target: RouteCoordinate
): List<EditableRoutePoint> =
    createRouteSectionEditSession(points, selection)?.commit(target) ?: points

/**
 * Removes a selected route span, joining the geometry at its boundaries.
 * A null result means the selection would leave an invalid route.
 */
fun deleteRouteSection(
    points: List<EditableRoutePoint>,
    selection: RouteSectionSelection
): List<EditableRoutePoint>? =
    createRouteSectionEditSession(points, selection)?.delete()

private fun RouteSectionSelection.isValid(): Boolean =
    startDistanceMeters.isFinite() &&
        endDistanceMeters.isFinite() &&
        startDistanceMeters >= 0.0 &&
        endDistanceMeters > startDistanceMeters

private fun RouteCoordinate.isValid(): Boolean =
    latitude.isFinite() &&
        longitude.isFinite() &&
        latitude in -90.0..90.0 &&
        longitude in -180.0..180.0

private fun cumulativeRouteDistances(points: List<EditableRoutePoint>): List<Double> {
    if (points.isEmpty()) return emptyList()
    val cumulative = ArrayList<Double>(points.size)
    cumulative += 0.0
    for (index in 1 until points.size) {
        cumulative += cumulative.last() + distanceMeters(points[index - 1], points[index])
    }
    return cumulative
}

private fun distanceMeters(first: EditableRoutePoint, second: EditableRoutePoint): Double {
    val lat1 = Math.toRadians(first.latitude)
    val lat2 = Math.toRadians(second.latitude)
    val deltaLatitude = lat2 - lat1
    val deltaLongitude = Math.toRadians(second.longitude - first.longitude)
    val sinLatitude = sin(deltaLatitude / 2.0)
    val sinLongitude = sin(deltaLongitude / 2.0)
    val haversine = sinLatitude * sinLatitude +
        cos(lat1) * cos(lat2) * sinLongitude * sinLongitude
    return 2.0 * EARTH_RADIUS_METERS * kotlin.math.asin(sqrt(haversine.coerceIn(0.0, 1.0)))
}

private fun coordinateAtDistance(
    points: List<EditableRoutePoint>,
    cumulative: List<Double>,
    distanceMeters: Double
): EditableRoutePoint? {
    if (points.isEmpty()) return null
    val distance = distanceMeters.coerceIn(0.0, cumulative.last())
    val exactIndex = cumulative.indexOfFirst {
        abs(it - distance) <= DISTANCE_EPSILON_METERS
    }
    if (exactIndex >= 0) return points[exactIndex]

    val segmentIndex = (0 until points.lastIndex).firstOrNull { index ->
        cumulative[index] < distance && cumulative[index + 1] > distance
    } ?: return points.last()

    val segmentLength = cumulative[segmentIndex + 1] - cumulative[segmentIndex]
    if (segmentLength <= DISTANCE_EPSILON_METERS) return points[segmentIndex]
    val fraction = ((distance - cumulative[segmentIndex]) / segmentLength).coerceIn(0.0, 1.0)
    val first = points[segmentIndex]
    val second = points[segmentIndex + 1]
    return EditableRoutePoint(
        sequence = segmentIndex,
        latitude = first.latitude + (second.latitude - first.latitude) * fraction,
        longitude = first.longitude + (second.longitude - first.longitude) * fraction
    )
}

private fun sectionSampleDistances(
    cumulative: List<Double>,
    startDistance: Double,
    endDistance: Double
): List<Double> {
    if (cumulative.isEmpty()) return emptyList()
    val distances = buildList {
        add(startDistance)
        add(endDistance)
        add((startDistance + endDistance) / 2.0)
        cumulative.filterTo(this) {
            it > startDistance + DISTANCE_EPSILON_METERS &&
                it < endDistance - DISTANCE_EPSILON_METERS
        }
        var sample = startDistance + ROUTE_SAMPLE_SPACING_METERS
        while (sample < endDistance - DISTANCE_EPSILON_METERS) {
            add(sample)
            sample += ROUTE_SAMPLE_SPACING_METERS
        }
    }.sorted()

    val unique = ArrayList<Double>(distances.size)
    distances.forEach { distance ->
        if (unique.isEmpty() || distance - unique.last() > DISTANCE_EPSILON_METERS) {
            unique += distance
        }
    }
    return unique
}

private fun smoothInfluence(distanceFromCenter: Double, radiusMeters: Double): Double {
    val progress = (distanceFromCenter / radiusMeters).coerceIn(0.0, 1.0)
    return (1.0 + kotlin.math.cos(PI * progress)) / 2.0
}

private fun normalizeLongitude(longitude: Double): Double =
    ((longitude + 540.0) % 360.0) - 180.0
