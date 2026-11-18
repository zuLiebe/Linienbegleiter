package com.example.linienbegleiter.data.routes

import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

data class CompassDirection(
    val code: String,
    val russianName: String,
    val germanName: String
)

private val COMPASS_DIRECTIONS = listOf(
    CompassDirection("N", "Север", "Nord"),
    CompassDirection("NE", "Северо-восток", "Nordost"),
    CompassDirection("E", "Восток", "Ost"),
    CompassDirection("SE", "Юго-восток", "Südost"),
    CompassDirection("S", "Юг", "Süd"),
    CompassDirection("SW", "Юго-запад", "Südwest"),
    CompassDirection("W", "Запад", "West"),
    CompassDirection("NW", "Северо-запад", "Nordwest")
)

fun initialBearingDegrees(
    startLatitude: Double,
    startLongitude: Double,
    endLatitude: Double,
    endLongitude: Double
): Double {
    val startLat = Math.toRadians(startLatitude)
    val endLat = Math.toRadians(endLatitude)
    val longitudeDelta = Math.toRadians(endLongitude - startLongitude)
    val y = sin(longitudeDelta) * cos(endLat)
    val x = cos(startLat) * sin(endLat) -
        sin(startLat) * cos(endLat) * cos(longitudeDelta)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

fun compassDirection(bearingDegrees: Double): CompassDirection {
    val normalized = ((bearingDegrees % 360.0) + 360.0) % 360.0
    val index = floor((normalized + 22.5) / 45.0).toInt() % COMPASS_DIRECTIONS.size
    return COMPASS_DIRECTIONS[index]
}

fun directionDescription(bearingDegrees: Double): String {
    val direction = compassDirection(bearingDegrees)
    return "Направление: " + direction.russianName + " (" + direction.germanName + ")"
}

fun createRouteDirections(
    routeId: String,
    stops: List<EditableRouteStop>,
    startStopIndex: Int,
    includeReverse: Boolean
): List<EditableRouteDirection> {
    if (stops.size < 2) return emptyList()
    require(startStopIndex in stops.indices)

    val orderedStops = stops.drop(startStopIndex) + stops.take(startStopIndex)
    val start = orderedStops.first()
    val end = orderedStops.last()
    val forwardIds = orderedStops.map { it.id }
    val geometryReversed = startStopIndex == stops.lastIndex
    val primary = EditableRouteDirection(
        id = UUID.randomUUID().toString(),
        routeId = routeId,
        name = start.name + " → " + end.name,
        startStopId = start.id,
        endStopId = end.id,
        initialBearingDegrees = initialBearingDegrees(
            start.latitude,
            start.longitude,
            end.latitude,
            end.longitude
        ),
        isGeometryReversed = geometryReversed,
        stopIdsInOrder = forwardIds
    )
    if (!includeReverse) return listOf(primary)

    return listOf(
        primary,
        EditableRouteDirection(
            id = UUID.randomUUID().toString(),
            routeId = routeId,
            name = end.name + " → " + start.name,
            startStopId = end.id,
            endStopId = start.id,
            initialBearingDegrees = initialBearingDegrees(
                end.latitude,
                end.longitude,
                start.latitude,
                start.longitude
            ),
            isGeometryReversed = !geometryReversed,
            stopIdsInOrder = forwardIds.reversed()
        )
    )
}
