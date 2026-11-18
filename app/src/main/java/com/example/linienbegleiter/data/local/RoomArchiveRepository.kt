package com.example.linienbegleiter.data.local

import androidx.room.withTransaction
import com.example.linienbegleiter.data.interchange.JsonCodec
import com.example.linienbegleiter.data.interchange.LinienbegleiterArchiveCodec
import com.example.linienbegleiter.data.interchange.jsonArray
import com.example.linienbegleiter.data.interchange.jsonObject
import com.example.linienbegleiter.data.recording.RecordingStatus
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Читает и добавляет полный локальный набор данных резервной копии Room.
 *
 * Идентификаторы при импорте пересоздаются, чтобы импорт никогда не перезаписывал
 * существующие маршруты и записи.
 */
class RoomArchiveRepository(private val database: RecordingDatabase) {
    private val recordingDao = database.recordingDao()
    private val trackDao = database.originalTrackDao()
    private val recordingPointDao = database.recordingPointDao()
    private val recordingStopDao = database.recordingStopDao()
    private val routeDao = database.editableRouteDao()

    suspend fun export(output: OutputStream) {
        val payload = database.withTransaction {
            mapOf(
                "recordings" to recordingDao.getAllForBackup().map(RecordingEntity::toBackupMap),
                "originalTracks" to trackDao.getAllForBackup().map(OriginalTrackEntity::toBackupMap),
                "recordingPoints" to recordingPointDao.getAllForBackup().map(RecordingPointEntity::toBackupMap),
                "recordingStops" to recordingStopDao.getAllForBackup().map(RecordingStopEntity::toBackupMap),
                "editableRoutes" to routeDao.getAllForBackup().map(EditableRouteEntity::toBackupMap),
                "editableRoutePoints" to routeDao.getAllPointsForBackup().map(EditableRoutePointEntity::toBackupMap),
                "editableRouteStops" to routeDao.getAllStopsForBackup().map(EditableRouteStopEntity::toBackupMap),
                "directions" to routeDao.getAllDirectionsForBackup().map(EditableRouteDirectionEntity::toBackupMap),
                "directionStops" to routeDao.getAllDirectionStopsForBackup()
                    .map(EditableRouteDirectionStopEntity::toBackupMap)
            )
        }
        LinienbegleiterArchiveCodec.write(payload, output)
    }

    /**
     * Проверяет целостность архива и сообщает его состав до импорта.
     */
    fun inspect(bundle: LinienbegleiterArchiveCodec.Bundle): ArchivePreview {
        val payload = JsonCodec.parse(bundle.payloadJson).jsonObject()
            ?: error("Данные архива не являются JSON-объектом.")
        val recordings = payload.rows("recordings")
        return ArchivePreview(
            recordings = recordings.size,
            routes = payload.rows("editableRoutes").size,
            points = payload.rows("recordingPoints").size +
                payload.rows("editableRoutePoints").size,
            stops = payload.rows("recordingStops").size +
                payload.rows("editableRouteStops").size,
            unfinishedRecordings = recordings.count {
                it.string("status") == RecordingStatus.RECORDING.name ||
                    it.string("status") == RecordingStatus.PAUSED.name
            }
        )
    }

    /**
     * Импортирует архив одной транзакцией и добавляет его данные с новыми идентификаторами.
     *
     * Активная запись из архива восстанавливается в состоянии PAUSED и не запускает
     * Foreground Service автоматически.
     */
    suspend fun import(input: InputStream): ArchiveImportSummary =
        import(LinienbegleiterArchiveCodec.read(input))

    suspend fun import(bundle: LinienbegleiterArchiveCodec.Bundle): ArchiveImportSummary {
        val payload = JsonCodec.parse(bundle.payloadJson).jsonObject()
            ?: error("Данные архива не являются JSON-объектом.")
        val recordingRows = payload.rows("recordings")
        val trackRows = payload.rows("originalTracks")
        val pointRows = payload.rows("recordingPoints")
        val recordingStopRows = payload.rows("recordingStops")
        val routeRows = payload.rows("editableRoutes")
        val routePointRows = payload.rows("editableRoutePoints")
        val routeStopRows = payload.rows("editableRouteStops")
        val directionRows = payload.rows("directions")
        val directionStopRows = payload.rows("directionStops")

        val recordingIds = recordingRows.associate { it.string("id") to UUID.randomUUID().toString() }
        val trackIds = trackRows.associate { it.string("id") to UUID.randomUUID().toString() }
        val routeIds = routeRows.associate { it.string("id") to UUID.randomUUID().toString() }
        val directionIds = directionRows.associate { it.string("id") to UUID.randomUUID().toString() }

        val recordings = recordingRows.map { row ->
            val oldTrackId = row.string("originalTrackId")
            val oldStatus = RecordingStatus.valueOf(row.string("status"))
            RecordingEntity(
                id = requireNotNull(recordingIds[row.string("id")]),
                title = row.string("title"),
                createdAtEpochMillis = row.long("createdAtEpochMillis"),
                startedAtEpochMillis = row.optionalLong("startedAtEpochMillis"),
                finishedAtEpochMillis = row.optionalLong("finishedAtEpochMillis"),
                status = if (oldStatus == RecordingStatus.RECORDING) RecordingStatus.PAUSED else oldStatus,
                totalDistanceMeters = row.double("totalDistanceMeters"),
                durationSeconds = row.long("durationSeconds"),
                originalTrackId = requireNotNull(trackIds[oldTrackId]) {
                    "GPS-запись архива не связана с исходным треком."
                },
                routeColorArgb = row.int("routeColorArgb"),
                routeLineWidth = row.float("routeLineWidth")
            )
        }
        val importedActiveCount = recordings.count {
            it.status == RecordingStatus.RECORDING || it.status == RecordingStatus.PAUSED
        }
        require(importedActiveCount <= 1) {
            "Архив содержит несколько незавершённых GPS-записей; импорт отменён."
        }

        val tracks = trackRows.map { row ->
            OriginalTrackEntity(
                id = requireNotNull(trackIds[row.string("id")]),
                recordingId = requireNotNull(recordingIds[row.string("recordingId")]),
                createdAtEpochMillis = row.long("createdAtEpochMillis"),
                finalizedAtEpochMillis = row.optionalLong("finalizedAtEpochMillis")
            )
        }
        val recordingPoints = pointRows.map { row ->
            RecordingPointEntity(
                recordingId = requireNotNull(recordingIds[row.string("recordingId")]),
                sequence = row.int("sequence"),
                latitude = row.double("latitude"),
                longitude = row.double("longitude"),
                timestampEpochMillis = row.long("timestampEpochMillis"),
                accuracyMeters = row.optionalFloat("accuracyMeters"),
                speedMetersPerSecond = row.optionalFloat("speedMetersPerSecond"),
                bearingDegrees = row.optionalFloat("bearingDegrees"),
                elapsedRealtimeNanos = row.optionalLong("elapsedRealtimeNanos")
            )
        }
        val recordingStops = recordingStopRows.map { row ->
            RecordingStopEntity(
                recordingId = requireNotNull(recordingIds[row.string("recordingId")]),
                sequence = row.int("sequence"),
                name = row.string("name"),
                latitude = row.double("latitude"),
                longitude = row.double("longitude"),
                timestampEpochMillis = row.long("timestampEpochMillis")
            )
        }
        val routes = routeRows.map { row ->
            EditableRouteEntity(
                id = requireNotNull(routeIds[row.string("id")]),
                title = row.string("title"),
                createdAtEpochMillis = row.long("createdAtEpochMillis"),
                sourceOriginalTrackId = row.optionalString("sourceOriginalTrackId")?.let {
                    requireNotNull(trackIds[it]) { "Маршрут ссылается на отсутствующий исходный трек." }
                },
                routeKind = row.string("routeKind"),
                parentRouteId = row.optionalString("parentRouteId")?.let {
                    requireNotNull(routeIds[it]) { "Маршрут ссылается на отсутствующий родительский маршрут." }
                },
                distanceMeters = row.double("distanceMeters"),
                routeColorArgb = row.int("routeColorArgb"),
                routeLineWidth = row.float("routeLineWidth"),
                routeMetadataJson = row.optionalString("routeMetadataJson")
            )
        }
        val routePoints = routePointRows.map { row ->
            EditableRoutePointEntity(
                routeId = requireNotNull(routeIds[row.string("routeId")]),
                sequence = row.int("sequence"),
                latitude = row.double("latitude"),
                longitude = row.double("longitude")
            )
        }

        return database.withTransaction {
            check(recordingDao.getActive() == null || importedActiveCount == 0) {
                "Сначала завершите текущую GPS-запись, затем импортируйте архив."
            }

            val usedNames = (
                recordingDao.getAllForBackup().map { it.title } +
                    routeDao.getAllForBackup().map { it.title }
                ).mapTo(mutableSetOf()) { it.trim().lowercase() }
            fun uniqueArchivedTitle(rawTitle: String): String {
                val base = rawTitle.trim().ifEmpty { "Маршрут" }
                var candidate = base
                var suffix = 2
                while (!usedNames.add(candidate.lowercase())) {
                    candidate = "$base $suffix"
                    suffix++
                }
                return candidate
            }

            val independentRecordings = recordings.map {
                it.copy(title = uniqueArchivedTitle(it.title))
            }
            val independentRoutes = routes.map {
                it.copy(
                    title = uniqueArchivedTitle(it.title),
                    sourceOriginalTrackId = null,
                    routeKind = if (it.routeKind == "VARIANT" || it.routeKind == "RECORDING_EDIT") {
                        "MANUAL"
                    } else {
                        it.routeKind
                    },
                    parentRouteId = null
                )
            }

            if (independentRecordings.isNotEmpty()) recordingDao.insertAll(independentRecordings)
            if (tracks.isNotEmpty()) trackDao.insertAll(tracks)
            if (recordingPoints.isNotEmpty()) recordingPointDao.insertAll(recordingPoints)
            if (recordingStops.isNotEmpty()) recordingStopDao.insertAll(recordingStops)
            if (independentRoutes.isNotEmpty()) routeDao.insertRoutes(independentRoutes)
            if (routePoints.isNotEmpty()) routeDao.insertPoints(routePoints)

            val stopIdMappings = mutableMapOf<Pair<String, Long>, Long>()
            routeStopRows.groupBy { it.string("routeId") }.forEach { (oldRouteId, rows) ->
                val newRouteId = requireNotNull(routeIds[oldRouteId])
                val orderedRows = rows.sortedBy { it.int("sequence") }
                val insertedIds = routeDao.insertStops(
                    orderedRows.map { row ->
                        EditableRouteStopEntity(
                            routeId = newRouteId,
                            sequence = row.int("sequence"),
                            routePointSequence = row.optionalInt("routePointSequence"),
                            name = row.string("name"),
                            latitude = row.double("latitude"),
                            longitude = row.double("longitude")
                        )
                    }
                )
                orderedRows.forEachIndexed { index, row ->
                    stopIdMappings[oldRouteId to row.long("id")] = insertedIds[index]
                }
            }

            val mappedDirections = directionRows.map { row ->
                val oldRouteId = row.string("routeId")
                EditableRouteDirectionEntity(
                    id = requireNotNull(directionIds[row.string("id")]),
                    routeId = requireNotNull(routeIds[oldRouteId]),
                    name = row.string("name"),
                    startStopId = requireNotNull(stopIdMappings[oldRouteId to row.long("startStopId")]),
                    endStopId = requireNotNull(stopIdMappings[oldRouteId to row.long("endStopId")]),
                    initialBearingDegrees = row.double("initialBearingDegrees"),
                    isGeometryReversed = row.boolean("isGeometryReversed")
                )
            }
            if (mappedDirections.isNotEmpty()) routeDao.insertDirections(mappedDirections)
            val directionRouteIds = directionRows.associate { row ->
                row.string("id") to row.string("routeId")
            }
            val mappedDirectionStops = directionStopRows.map { row ->
                val oldDirectionId = row.string("directionId")
                val oldRouteId = requireNotNull(directionRouteIds[oldDirectionId])
                EditableRouteDirectionStopEntity(
                    directionId = requireNotNull(directionIds[oldDirectionId]),
                    stopId = requireNotNull(stopIdMappings[oldRouteId to row.long("stopId")]),
                    sequence = row.int("sequence")
                )
            }
            if (mappedDirectionStops.isNotEmpty()) {
                routeDao.insertDirectionStops(mappedDirectionStops)
            }
            ArchiveImportSummary(
                recordingsAdded = recordings.size,
                routesAdded = routes.size,
                pointsAdded = recordingPoints.size + routePoints.size,
                stopsAdded = recordingStops.size + routeStopRows.size
            )
        }
    }
}

data class ArchivePreview(
    val recordings: Int,
    val routes: Int,
    val points: Int,
    val stops: Int,
    val unfinishedRecordings: Int
)

data class ArchiveImportSummary(
    val recordingsAdded: Int,
    val routesAdded: Int,
    val pointsAdded: Int,
    val stopsAdded: Int
)

private fun RecordingEntity.toBackupMap() = mapOf(
    "id" to id,
    "title" to title,
    "createdAtEpochMillis" to createdAtEpochMillis,
    "startedAtEpochMillis" to startedAtEpochMillis,
    "finishedAtEpochMillis" to finishedAtEpochMillis,
    "status" to status.name,
    "totalDistanceMeters" to totalDistanceMeters,
    "durationSeconds" to durationSeconds,
    "originalTrackId" to originalTrackId,
    "routeColorArgb" to routeColorArgb,
    "routeLineWidth" to routeLineWidth
)

private fun OriginalTrackEntity.toBackupMap() = mapOf(
    "id" to id,
    "recordingId" to recordingId,
    "createdAtEpochMillis" to createdAtEpochMillis,
    "finalizedAtEpochMillis" to finalizedAtEpochMillis
)

private fun RecordingPointEntity.toBackupMap() = mapOf(
    "id" to id,
    "recordingId" to recordingId,
    "sequence" to sequence,
    "latitude" to latitude,
    "longitude" to longitude,
    "timestampEpochMillis" to timestampEpochMillis,
    "accuracyMeters" to accuracyMeters,
    "speedMetersPerSecond" to speedMetersPerSecond,
    "bearingDegrees" to bearingDegrees,
    "elapsedRealtimeNanos" to elapsedRealtimeNanos
)

private fun RecordingStopEntity.toBackupMap() = mapOf(
    "id" to id,
    "recordingId" to recordingId,
    "sequence" to sequence,
    "name" to name,
    "latitude" to latitude,
    "longitude" to longitude,
    "timestampEpochMillis" to timestampEpochMillis
)

private fun EditableRouteEntity.toBackupMap() = mapOf(
    "id" to id,
    "title" to title,
    "createdAtEpochMillis" to createdAtEpochMillis,
    "sourceOriginalTrackId" to sourceOriginalTrackId,
    "routeKind" to routeKind,
    "parentRouteId" to parentRouteId,
    "distanceMeters" to distanceMeters,
    "routeColorArgb" to routeColorArgb,
    "routeLineWidth" to routeLineWidth,
    "routeMetadataJson" to routeMetadataJson
)

private fun EditableRoutePointEntity.toBackupMap() = mapOf(
    "id" to id,
    "routeId" to routeId,
    "sequence" to sequence,
    "latitude" to latitude,
    "longitude" to longitude
)

private fun EditableRouteStopEntity.toBackupMap() = mapOf(
    "id" to id,
    "routeId" to routeId,
    "sequence" to sequence,
    "routePointSequence" to routePointSequence,
    "name" to name,
    "latitude" to latitude,
    "longitude" to longitude
)

private fun EditableRouteDirectionEntity.toBackupMap() = mapOf(
    "id" to id,
    "routeId" to routeId,
    "name" to name,
    "startStopId" to startStopId,
    "endStopId" to endStopId,
    "initialBearingDegrees" to initialBearingDegrees,
    "isGeometryReversed" to isGeometryReversed
)

private fun EditableRouteDirectionStopEntity.toBackupMap() = mapOf(
    "id" to id,
    "directionId" to directionId,
    "stopId" to stopId,
    "sequence" to sequence
)

private fun Map<String, Any?>.rows(key: String): List<Map<String, Any?>> =
    this[key].jsonArray()?.map { it.jsonObject() ?: error("Некорректная строка архива.") }
        ?: error("В архиве отсутствует список " + key + ".")

private fun Map<String, Any?>.string(key: String): String =
    this[key] as? String ?: error("В архиве отсутствует строка " + key + ".")

private fun Map<String, Any?>.optionalString(key: String): String? =
    this[key] as? String

private fun Map<String, Any?>.long(key: String): Long =
    (this[key] as? Number)?.toLong() ?: error("В архиве отсутствует число " + key + ".")

private fun Map<String, Any?>.optionalLong(key: String): Long? =
    (this[key] as? Number)?.toLong()

private fun Map<String, Any?>.int(key: String): Int =
    (this[key] as? Number)?.toInt() ?: error("В архиве отсутствует число " + key + ".")

private fun Map<String, Any?>.optionalInt(key: String): Int? =
    (this[key] as? Number)?.toInt()

private fun Map<String, Any?>.double(key: String): Double =
    (this[key] as? Number)?.toDouble() ?: error("В архиве отсутствует число " + key + ".")

private fun Map<String, Any?>.float(key: String): Float =
    (this[key] as? Number)?.toFloat() ?: error("В архиве отсутствует число " + key + ".")

private fun Map<String, Any?>.optionalFloat(key: String): Float? =
    (this[key] as? Number)?.toFloat()

private fun Map<String, Any?>.boolean(key: String): Boolean =
    this[key] as? Boolean ?: error("В архиве отсутствует логическое поле " + key + ".")
