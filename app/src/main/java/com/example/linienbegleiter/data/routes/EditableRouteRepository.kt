package com.example.linienbegleiter.data.routes

import androidx.room.withTransaction
import com.example.linienbegleiter.data.local.EditableRouteDao
import com.example.linienbegleiter.data.local.EditableRouteDirectionEntity
import com.example.linienbegleiter.data.local.EditableRouteDirectionStopEntity
import com.example.linienbegleiter.data.local.EditableRouteEntity
import com.example.linienbegleiter.data.local.EditableRoutePointEntity
import com.example.linienbegleiter.data.local.EditableRouteStopEntity
import com.example.linienbegleiter.data.local.RecordingDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID
import com.example.linienbegleiter.data.routes.requireUniqueRouteTitle
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

interface EditableRouteRepository {
    suspend fun createRoute(
        title: String,
        createdAtEpochMillis: Long,
        points: List<EditableRoutePoint>,
        stops: List<RouteDraftStop>,
        routeKind: EditableRouteKind = EditableRouteKind.MANUAL,
        routeColorArgb: Int? = null,
        routeLineWidth: Float = 5f,
        routeMetadataJson: String? = null
    ): EditableRouteDetails

    suspend fun updateRoute(
        routeId: String,
        title: String,
        points: List<EditableRoutePoint>,
        stops: List<RouteDraftStop>,
        directions: List<EditableRouteDirection>? = null,
        routeColorArgb: Int? = null,
        routeLineWidth: Float? = null,
        routeMetadataJson: String? = null
    ): EditableRouteDetails

    suspend fun updateAppearance(routeId: String, colorArgb: Int, lineWidth: Float): EditableRouteDetails
    suspend fun saveDirections(
        routeId: String,
        directions: List<EditableRouteDirection>
    ): EditableRouteDetails

    fun observeRoutes(): Flow<List<EditableRoute>>
    suspend fun getRoutes(): List<EditableRoute>
    fun observeDetails(routeId: String): Flow<EditableRouteDetails?>
    suspend fun getDetails(routeId: String): EditableRouteDetails?
    suspend fun deleteRoute(routeId: String)
}

class RoomEditableRouteRepository(
    private val database: RecordingDatabase
) : EditableRouteRepository {
    private val routeDao: EditableRouteDao = database.editableRouteDao()

    override suspend fun createRoute(
        title: String,
        createdAtEpochMillis: Long,
        points: List<EditableRoutePoint>,
        stops: List<RouteDraftStop>,
        routeKind: EditableRouteKind,
        routeColorArgb: Int?,
        routeLineWidth: Float,
        routeMetadataJson: String?
    ): EditableRouteDetails {
        val cleanTitle = title.trim()
        val independentRouteKind = when (routeKind) {
            EditableRouteKind.RECORDING_EDIT, EditableRouteKind.VARIANT ->
                EditableRouteKind.MANUAL
            else -> routeKind
        }
        validate(
            cleanTitle,
            points,
            stops,
            allowEmptyGeometry = independentRouteKind == EditableRouteKind.GTFS_IMPORT && points.isEmpty()
        )
        require(routeLineWidth in 2f..14f) { "Толщина линии должна быть от 2 до 14." }

        val routeId = UUID.randomUUID().toString()
        val savedPoints = points.mapIndexed { index, point -> point.copy(sequence = index, id = 0L) }
        val route = EditableRoute(
            id = routeId,
            title = cleanTitle,
            createdAtEpochMillis = createdAtEpochMillis,
            sourceOriginalTrackId = null,
            distanceMeters = approximateDistance(savedPoints),
            routeKind = independentRouteKind,
            parentRouteId = null,
            routeColorArgb = routeColorArgb ?: RouteColorPalette.automaticFor(routeId),
            routeLineWidth = routeLineWidth,
            routeMetadataJson = routeMetadataJson
        )
        val savedStops = stops.mapIndexed { index, stop ->
            EditableRouteStop(
                sequence = index,
                routePointSequence = stop.routePointSequence,
                name = stop.name.trim(),
                latitude = stop.latitude,
                longitude = stop.longitude,
                id = 0L
            )
        }

        val insertedIds = database.withTransaction {
            database.requireUniqueRouteTitle(cleanTitle)
            routeDao.insertRoute(route.toEntity())
            val pointIds = if (savedPoints.isEmpty()) emptyList() else {
                routeDao.insertPoints(savedPoints.map { it.toEntity(routeId) })
            }
            val stopIds = if (savedStops.isEmpty()) emptyList() else {
                routeDao.insertStops(savedStops.map { it.toEntity(routeId) })
            }
            pointIds to stopIds
        }
        val pointsWithIds = savedPoints.mapIndexed { index, point ->
            if (point.id == 0L) point.copy(id = insertedIds.first[index]) else point
        }
        val stopsWithIds = savedStops.mapIndexed { index, stop ->
            if (stop.id == 0L) stop.copy(id = insertedIds.second[index]) else stop
        }
        return EditableRouteDetails(route, pointsWithIds, stopsWithIds)
    }

    override suspend fun updateRoute(
        routeId: String,
        title: String,
        points: List<EditableRoutePoint>,
        stops: List<RouteDraftStop>,
        directions: List<EditableRouteDirection>?,
        routeColorArgb: Int?,
        routeLineWidth: Float?,
        routeMetadataJson: String?
    ): EditableRouteDetails {
        val cleanTitle = title.trim()
        val existing = requireNotNull(routeDao.getById(routeId)) {
            "Редактируемый маршрут не найден."
        }
        validate(
            cleanTitle,
            points,
            stops,
            allowEmptyGeometry =
                runCatching { EditableRouteKind.valueOf(existing.routeKind) }
                    .getOrDefault(EditableRouteKind.MANUAL) == EditableRouteKind.GTFS_IMPORT &&
                    points.isEmpty()
        )
        routeLineWidth?.let { require(it in 2f..14f) { "Толщина линии должна быть от 2 до 14." } }
        val previousDirections = loadDirections(routeId)
        val savedPoints = points.mapIndexed { index, point -> point.copy(sequence = index) }
        val savedStops = stops.mapIndexed { index, stop ->
            EditableRouteStop(
                sequence = index,
                routePointSequence = stop.routePointSequence,
                name = stop.name.trim(),
                latitude = stop.latitude,
                longitude = stop.longitude,
                id = stop.id
            )
        }
        val updatedRoute = existing.copy(
            title = cleanTitle,
            distanceMeters = approximateDistance(savedPoints),
            routeColorArgb = routeColorArgb ?: existing.routeColorArgb,
            routeLineWidth = routeLineWidth ?: existing.routeLineWidth,
            routeMetadataJson = routeMetadataJson ?: existing.routeMetadataJson
        )

        val persistedData = database.withTransaction {
            database.requireUniqueRouteTitle(
                cleanTitle,
                exceptEditableRouteId = routeId
            )
            routeDao.updateRoute(updatedRoute)
            routeDao.deleteDirections(routeId)
            routeDao.deletePoints(routeId)
            routeDao.deleteStops(routeId)
            val pointIds = if (savedPoints.isEmpty()) emptyList() else {
                routeDao.insertPoints(savedPoints.map { it.toEntity(routeId) })
            }
            val stopIds = if (savedStops.isEmpty()) emptyList() else {
                routeDao.insertStops(savedStops.map { it.toEntity(routeId) })
            }
            val pointsWithIds = savedPoints.mapIndexed { index, point ->
                if (point.id == 0L) point.copy(id = pointIds[index]) else point
            }
            val stopsWithIds = savedStops.mapIndexed { index, stop ->
                if (stop.id == 0L) stop.copy(id = stopIds[index]) else stop
            }
            val persistedStopIds = stopsWithIds.map { it.id }.toSet()
            val directionsToKeep = (directions ?: previousDirections).filter { direction ->
                direction.startStopId in persistedStopIds &&
                    direction.endStopId in persistedStopIds &&
                    direction.stopIdsInOrder.toSet() == persistedStopIds
            }
            insertDirections(routeId, directionsToKeep)
            Triple(pointsWithIds, stopsWithIds, directionsToKeep)
        }

        return EditableRouteDetails(
            route = updatedRoute.toDomain(),
            points = persistedData.first,
            stops = persistedData.second,
            directions = persistedData.third
        )
    }

    override suspend fun updateAppearance(
        routeId: String,
        colorArgb: Int,
        lineWidth: Float
    ): EditableRouteDetails {
        require(lineWidth in 2f..14f) { "Толщина линии должна быть от 2 до 14." }
        require(routeDao.getById(routeId) != null) { "Маршрут не найден." }
        routeDao.updateAppearance(routeId, colorArgb, lineWidth)
        return requireNotNull(getDetails(routeId))
    }

    override suspend fun saveDirections(
        routeId: String,
        directions: List<EditableRouteDirection>
    ): EditableRouteDetails {
        require(routeDao.getById(routeId) != null) { "Маршрут не найден." }
        replaceDirections(routeId, directions)
        return requireNotNull(getDetails(routeId))
    }

    override fun observeRoutes(): Flow<List<EditableRoute>> =
        routeDao.observeAll().map { routes -> routes.map(EditableRouteEntity::toDomain) }

    override suspend fun getRoutes(): List<EditableRoute> =
        routeDao.getAll().map(EditableRouteEntity::toDomain)

    override fun observeDetails(routeId: String): Flow<EditableRouteDetails?> =
        combine(
            routeDao.observeById(routeId),
            routeDao.observePoints(routeId),
            routeDao.observeStops(routeId),
            routeDao.observeDirections(routeId),
            routeDao.observeDirectionStops(routeId)
        ) { route, points, stops, directionEntities, directionStops ->
            route?.let {
                EditableRouteDetails(
                    route = it.toDomain(),
                    points = points.map(EditableRoutePointEntity::toDomain),
                    stops = stops.map(EditableRouteStopEntity::toDomain),
                    directions = directionEntities.map { direction ->
                        direction.toDomain(
                            directionStops.filter { row -> row.directionId == direction.id }
                                .map(EditableRouteDirectionStopEntity::stopId)
                        )
                    }
                )
            }
        }

    override suspend fun deleteRoute(routeId: String) {
        database.withTransaction {
            require(routeDao.deleteRoute(routeId) == 1) { "Маршрут не найден." }
        }
    }

    override suspend fun getDetails(routeId: String): EditableRouteDetails? {
        val route = routeDao.getById(routeId)?.toDomain() ?: return null
        return EditableRouteDetails(
            route = route,
            points = routeDao.getPoints(routeId).map(EditableRoutePointEntity::toDomain),
            stops = routeDao.getStops(routeId).map(EditableRouteStopEntity::toDomain),
            directions = loadDirections(routeId)
        )
    }

    private suspend fun loadDirections(routeId: String): List<EditableRouteDirection> {
        val directionStops = routeDao.getDirections(routeId)
        return directionStops.map { direction ->
            direction.toDomain(
                routeDao.getDirectionStops(direction.id)
                    .map(EditableRouteDirectionStopEntity::stopId)
            )
        }
    }

    private suspend fun replaceDirections(
        routeId: String,
        directions: List<EditableRouteDirection>
    ) = database.withTransaction {
        routeDao.deleteDirections(routeId)
        insertDirections(routeId, directions)
    }

    private suspend fun insertDirections(
        routeId: String,
        directions: List<EditableRouteDirection>
    ) {
        val currentStopIds = routeDao.getStops(routeId).map { it.id }.toSet()
        directions.forEach { direction ->
            require(direction.startStopId in currentStopIds) { "Начальная остановка не найдена." }
            require(direction.endStopId in currentStopIds) { "Конечная остановка не найдена." }
            require(
                direction.stopIdsInOrder.size == currentStopIds.size &&
                    direction.stopIdsInOrder.toSet() == currentStopIds
            ) { "Порядок направления должен содержать все остановки маршрута." }
            require(direction.initialBearingDegrees.isFinite())
        }

        directions.forEach { direction ->
            routeDao.insertDirection(
                direction.copy(routeId = routeId).toEntity(routeId)
            )
            if (direction.stopIdsInOrder.isNotEmpty()) {
                routeDao.insertDirectionStops(
                    direction.stopIdsInOrder.mapIndexed { index, stopId ->
                        EditableRouteDirectionStopEntity(
                            directionId = direction.id,
                            stopId = stopId,
                            sequence = index
                        )
                    }
                )
            }
        }
    }

    private fun validate(
        title: String,
        points: List<EditableRoutePoint>,
        stops: List<RouteDraftStop>,
        allowEmptyGeometry: Boolean = false
    ) {
        require(title.isNotEmpty()) { "Введите название маршрута." }
        require(allowEmptyGeometry && points.isEmpty() || points.size >= 2) {
            "Добавьте минимум две точки пути."
        }
        points.forEach { requireValidCoordinates(it.latitude, it.longitude) }
        stops.forEach { stop ->
            require(stop.routePointSequence == null || stop.routePointSequence in points.indices) {
                "Неверная привязка остановки."
            }
            require(stop.name.isNotBlank()) { "Введите название остановки." }
            requireValidCoordinates(stop.latitude, stop.longitude)
        }
    }

    private fun requireValidCoordinates(latitude: Double, longitude: Double) {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }

    private fun approximateDistance(points: List<EditableRoutePoint>): Double =
        points.zipWithNext().sumOf { (first, second) ->
            val earthRadiusMeters = 6_371_000.0
            val latitudeDelta = Math.toRadians(second.latitude - first.latitude)
            val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
            val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
                cos(Math.toRadians(first.latitude)) *
                cos(Math.toRadians(second.latitude)) *
                sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
            2 * earthRadiusMeters * atan2(sqrt(a), sqrt(1 - a))
        }
}

private fun EditableRoute.toEntity() = EditableRouteEntity(
    id = id,
    title = title,
    createdAtEpochMillis = createdAtEpochMillis,
    sourceOriginalTrackId = sourceOriginalTrackId,
    routeKind = routeKind.name,
    parentRouteId = parentRouteId,
    distanceMeters = distanceMeters,
    routeColorArgb = routeColorArgb,
    routeLineWidth = routeLineWidth,
    routeMetadataJson = routeMetadataJson
)

private fun EditableRouteEntity.toDomain() = EditableRoute(
    id = id,
    title = title,
    createdAtEpochMillis = createdAtEpochMillis,
    sourceOriginalTrackId = sourceOriginalTrackId,
    distanceMeters = distanceMeters,
    routeKind = runCatching { EditableRouteKind.valueOf(routeKind) }
        .getOrDefault(EditableRouteKind.MANUAL),
    parentRouteId = parentRouteId,
    routeColorArgb = routeColorArgb.takeIf { it != 0 } ?: RouteColorPalette.automaticFor(id),
    routeLineWidth = routeLineWidth.coerceIn(2f, 14f),
    routeMetadataJson = routeMetadataJson
)

private fun EditableRoutePoint.toEntity(routeId: String) = EditableRoutePointEntity(
    id = id,
    routeId = routeId,
    sequence = sequence,
    latitude = latitude,
    longitude = longitude
)

private fun EditableRoutePointEntity.toDomain() = EditableRoutePoint(
    sequence = sequence,
    latitude = latitude,
    longitude = longitude,
    id = id
)

private fun EditableRouteStop.toEntity(routeId: String) = EditableRouteStopEntity(
    id = id,
    routeId = routeId,
    sequence = sequence,
    routePointSequence = routePointSequence,
    name = name,
    latitude = latitude,
    longitude = longitude
)

private fun EditableRouteStopEntity.toDomain() = EditableRouteStop(
    sequence = sequence,
    routePointSequence = routePointSequence,
    name = name,
    latitude = latitude,
    longitude = longitude,
    id = id
)

private fun EditableRouteDirection.toEntity(routeId: String) = EditableRouteDirectionEntity(
    id = id,
    routeId = routeId,
    name = name,
    startStopId = startStopId,
    endStopId = endStopId,
    initialBearingDegrees = initialBearingDegrees,
    isGeometryReversed = isGeometryReversed
)

private fun EditableRouteDirectionEntity.toDomain(stopIds: List<Long>) =
    EditableRouteDirection(
        id = id,
        routeId = routeId,
        name = name,
        startStopId = startStopId,
        endStopId = endStopId,
        initialBearingDegrees = initialBearingDegrees,
        isGeometryReversed = isGeometryReversed,
        stopIdsInOrder = stopIds
    )

