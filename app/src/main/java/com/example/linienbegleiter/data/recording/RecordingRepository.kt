package com.example.linienbegleiter.data.recording

import com.example.linienbegleiter.data.local.OriginalTrackEntity
import com.example.linienbegleiter.data.local.RecordingDatabase
import com.example.linienbegleiter.data.local.RecordingEntity
import com.example.linienbegleiter.data.routes.requireUniqueRouteTitle
import com.example.linienbegleiter.data.local.RecordingPointEntity
import com.example.linienbegleiter.data.local.RecordingStopEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import androidx.room.withTransaction
import java.util.UUID

interface RecordingRepository {
    suspend fun createSession(title: String, createdAtEpochMillis: Long): RecordingSession
    suspend fun startRecording(recordingId: String, startedAtEpochMillis: Long)
    suspend fun updateAppearance(recordingId: String, colorArgb: Int, lineWidth: Float)
    suspend fun updateRecordingStatus(
        recordingId: String,
        status: RecordingStatus,
        durationSeconds: Long,
        totalDistanceMeters: Double
    )
    suspend fun finishRecording(
        recordingId: String,
        finishedAtEpochMillis: Long,
        durationSeconds: Long,
        totalDistanceMeters: Double
    )
    suspend fun appendPoint(recordingId: String, point: RecordingPoint): RecordingPoint
    suspend fun appendStop(recordingId: String, stop: RecordingStop): RecordingStop
    fun observeSession(recordingId: String): Flow<RecordingSession?>
    fun observeActiveSession(): Flow<RecordingSession?>
    fun observeSessions(): Flow<List<RecordingSession>>
    fun observePoints(recordingId: String): Flow<List<RecordingPoint>>
    fun observeStops(recordingId: String): Flow<List<RecordingStop>>
    fun observeDetails(recordingId: String): Flow<RecordingDetails?>
    suspend fun getSession(recordingId: String): RecordingSession?
    suspend fun getActiveSession(): RecordingSession?
    suspend fun getDetails(recordingId: String): RecordingDetails?
    suspend fun getDetailsByOriginalTrackId(originalTrackId: String): RecordingDetails?
    suspend fun deleteSession(recordingId: String)
}

class RoomRecordingRepository(
    private val database: RecordingDatabase
) : RecordingRepository {
    private val recordingDao = database.recordingDao()
    private val originalTrackDao = database.originalTrackDao()
    private val recordingPointDao = database.recordingPointDao()
    private val recordingStopDao = database.recordingStopDao()

    override suspend fun createSession(
        title: String,
        createdAtEpochMillis: Long
    ): RecordingSession {
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty()) { "Название маршрута не может быть пустым." }

        val recordingId = UUID.randomUUID().toString()
        val originalTrackId = UUID.randomUUID().toString()
        val entity = RecordingEntity(
            id = recordingId,
            title = cleanTitle,
            createdAtEpochMillis = createdAtEpochMillis,
            startedAtEpochMillis = null,
            finishedAtEpochMillis = null,
            status = RecordingStatus.IDLE,
            totalDistanceMeters = 0.0,
            durationSeconds = 0,
            originalTrackId = originalTrackId,
            routeColorArgb = com.example.linienbegleiter.data.routes.RouteColorPalette.automaticFor(recordingId)
        )
        val originalTrack = OriginalTrackEntity(
            id = originalTrackId,
            recordingId = recordingId,
            createdAtEpochMillis = createdAtEpochMillis,
            finalizedAtEpochMillis = null
        )

        database.withTransaction {
            check(recordingDao.getActive() == null) {
                "Сначала завершите уже активную запись."
            }
            database.requireUniqueRouteTitle(cleanTitle)
            recordingDao.insert(entity)
            originalTrackDao.insert(originalTrack)
        }
        return entity.toDomain()
    }

    override suspend fun updateAppearance(
        recordingId: String,
        colorArgb: Int,
        lineWidth: Float
    ) {
        require(lineWidth in 2f..14f) { "Толщина линии должна быть от 2 до 14." }
        require(recordingDao.getById(recordingId) != null) { "GPS-запись не найдена." }
        recordingDao.updateAppearance(recordingId, colorArgb, lineWidth)
    }

    override suspend fun startRecording(
        recordingId: String,
        startedAtEpochMillis: Long
    ) {
        database.withTransaction {
            val session = requireNotNull(recordingDao.getById(recordingId)) {
                "Запись не найдена."
            }
            check(session.status == RecordingStatus.IDLE) {
                "Начать можно только новую запись."
            }
            check(recordingDao.getActive() == null) {
                "Уже существует активная запись."
            }
            recordingDao.markStarted(recordingId, startedAtEpochMillis)
        }
    }

    override suspend fun updateRecordingStatus(
        recordingId: String,
        status: RecordingStatus,
        durationSeconds: Long,
        totalDistanceMeters: Double
    ) {
        require(status == RecordingStatus.RECORDING || status == RecordingStatus.PAUSED) {
            "Для завершения записи используйте finishRecording()."
        }
        require(durationSeconds >= 0)
        require(totalDistanceMeters.isFinite() && totalDistanceMeters >= 0.0)

        database.withTransaction {
            val session = requireNotNull(recordingDao.getById(recordingId)) {
                "Запись не найдена."
            }
            check(session.status == RecordingStatus.RECORDING ||
                session.status == RecordingStatus.PAUSED
            ) { "Запись уже не активна." }
            if (status == RecordingStatus.RECORDING) {
                val active = recordingDao.getActive()
                check(active == null || active.id == recordingId) {
                    "Уже существует другая активная запись."
                }
            }
            recordingDao.updateProgress(
                recordingId,
                status,
                durationSeconds,
                totalDistanceMeters
            )
        }
    }

    override suspend fun finishRecording(
        recordingId: String,
        finishedAtEpochMillis: Long,
        durationSeconds: Long,
        totalDistanceMeters: Double
    ) {
        require(durationSeconds >= 0)
        require(totalDistanceMeters.isFinite() && totalDistanceMeters >= 0.0)

        database.withTransaction {
            val session = requireNotNull(recordingDao.getById(recordingId)) {
                "Запись не найдена."
            }
            check(session.status == RecordingStatus.RECORDING ||
                session.status == RecordingStatus.PAUSED
            ) { "Запись уже завершена." }
            recordingDao.markFinished(
                recordingId,
                finishedAtEpochMillis,
                durationSeconds,
                totalDistanceMeters
            )
            originalTrackDao.finalize(
                session.originalTrackId,
                finishedAtEpochMillis
            )
        }
    }

    override suspend fun appendPoint(
        recordingId: String,
        point: RecordingPoint
    ): RecordingPoint = database.withTransaction {
        val session = requireRecording(recordingId)
        check(session.status == RecordingStatus.RECORDING) {
            "GPS-точки можно добавлять только во время записи."
        }
        requireValidCoordinates(point.latitude, point.longitude)
        require(point.timestampEpochMillis >= 0)

        val saved = point.copy(sequence = recordingPointDao.nextSequence(recordingId))
        recordingPointDao.insert(saved.toEntity(recordingId))
        saved
    }

    override suspend fun appendStop(
        recordingId: String,
        stop: RecordingStop
    ): RecordingStop = database.withTransaction {
        val session = requireRecording(recordingId)
        check(session.status == RecordingStatus.RECORDING) {
            "Остановку можно добавить только во время записи."
        }
        val cleanName = stop.name.trim()
        require(cleanName.isNotEmpty()) { "Введите название остановки." }
        requireValidCoordinates(stop.latitude, stop.longitude)
        require(stop.timestampEpochMillis >= 0)

        val saved = stop.copy(
            sequence = recordingStopDao.nextSequence(recordingId),
            name = cleanName
        )
        recordingStopDao.insert(saved.toEntity(recordingId))
        saved
    }

    override fun observeSession(recordingId: String): Flow<RecordingSession?> =
        recordingDao.observeById(recordingId).map { it?.toDomain() }

    override fun observeActiveSession(): Flow<RecordingSession?> =
        recordingDao.observeActive().map { it?.toDomain() }

    override fun observeSessions(): Flow<List<RecordingSession>> =
        recordingDao.observeAll().map { sessions -> sessions.map(RecordingEntity::toDomain) }

    override fun observePoints(recordingId: String): Flow<List<RecordingPoint>> =
        recordingPointDao.observeForRecording(recordingId)
            .map { points -> points.map(RecordingPointEntity::toDomain) }

    override fun observeStops(recordingId: String): Flow<List<RecordingStop>> =
        recordingStopDao.observeForRecording(recordingId)
            .map { stops -> stops.map(RecordingStopEntity::toDomain) }

    override fun observeDetails(recordingId: String): Flow<RecordingDetails?> =
        combine(
            recordingDao.observeById(recordingId),
            originalTrackDao.observeByRecordingId(recordingId),
            recordingPointDao.observeForRecording(recordingId),
            recordingStopDao.observeForRecording(recordingId)
        ) { session, originalTrack, points, stops ->
            if (session == null || originalTrack == null) {
                null
            } else {
                RecordingDetails(
                    session = session.toDomain(),
                    originalTrack = originalTrack.toDomain(),
                    points = points.map(RecordingPointEntity::toDomain),
                    stops = stops.map(RecordingStopEntity::toDomain)
                )
            }
        }

    override suspend fun getSession(recordingId: String): RecordingSession? =
        recordingDao.getById(recordingId)?.toDomain()

    override suspend fun getActiveSession(): RecordingSession? =
        recordingDao.getActive()?.toDomain()

    override suspend fun getDetails(recordingId: String): RecordingDetails? {
        val session = recordingDao.getById(recordingId) ?: return null
        val originalTrack = originalTrackDao.getByRecordingId(recordingId) ?: return null
        return RecordingDetails(
            session = session.toDomain(),
            originalTrack = originalTrack.toDomain(),
            points = recordingPointDao.getForRecording(recordingId)
                .map(RecordingPointEntity::toDomain),
            stops = recordingStopDao.getForRecording(recordingId)
                .map(RecordingStopEntity::toDomain)
        )
    }

    override suspend fun deleteSession(recordingId: String) {
        database.withTransaction {
            val session = requireNotNull(recordingDao.getById(recordingId)) {
                "GPS-запись не найдена."
            }
            check(session.status != RecordingStatus.RECORDING &&
                session.status != RecordingStatus.PAUSED
            ) { "Сначала безопасно завершите активную GPS-запись." }
            check(recordingDao.deleteById(recordingId) == 1) {
                "Не удалось удалить GPS-запись."
            }
        }
    }

    override suspend fun getDetailsByOriginalTrackId(
        originalTrackId: String
    ): RecordingDetails? {
        val track = originalTrackDao.getById(originalTrackId) ?: return null
        return getDetails(track.recordingId)
    }

    private suspend fun requireRecording(recordingId: String): RecordingEntity {
        val session = requireNotNull(recordingDao.getById(recordingId)) {
            "Запись не найдена."
        }
        check(session.status == RecordingStatus.RECORDING ||
            session.status == RecordingStatus.PAUSED
        ) { "Запись уже завершена." }
        return session
    }

    private fun requireValidCoordinates(latitude: Double, longitude: Double) {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

private fun RecordingEntity.toDomain() = RecordingSession(
    id = id,
    title = title,
    createdAtEpochMillis = createdAtEpochMillis,
    startedAtEpochMillis = startedAtEpochMillis,
    finishedAtEpochMillis = finishedAtEpochMillis,
    status = status,
    totalDistanceMeters = totalDistanceMeters,
    durationSeconds = durationSeconds,
    originalTrackId = originalTrackId,
    routeColorArgb = routeColorArgb.takeIf { it != 0 } ?:
        com.example.linienbegleiter.data.routes.RouteColorPalette.automaticFor(id),
    routeLineWidth = routeLineWidth.coerceIn(2f, 14f)
)

private fun OriginalTrackEntity.toDomain() = OriginalTrack(
    id = id,
    recordingId = recordingId,
    createdAtEpochMillis = createdAtEpochMillis,
    finalizedAtEpochMillis = finalizedAtEpochMillis
)

private fun RecordingPointEntity.toDomain() = RecordingPoint(
    sequence = sequence,
    latitude = latitude,
    longitude = longitude,
    timestampEpochMillis = timestampEpochMillis,
    accuracyMeters = accuracyMeters,
    speedMetersPerSecond = speedMetersPerSecond,
    bearingDegrees = bearingDegrees,
    elapsedRealtimeNanos = elapsedRealtimeNanos
)

private fun RecordingPoint.toEntity(recordingId: String) = RecordingPointEntity(
    recordingId = recordingId,
    sequence = sequence,
    latitude = latitude,
    longitude = longitude,
    timestampEpochMillis = timestampEpochMillis,
    accuracyMeters = accuracyMeters,
    speedMetersPerSecond = speedMetersPerSecond,
    bearingDegrees = bearingDegrees,
    elapsedRealtimeNanos = elapsedRealtimeNanos
)

private fun RecordingStopEntity.toDomain() = RecordingStop(
    sequence = sequence,
    name = name,
    latitude = latitude,
    longitude = longitude,
    timestampEpochMillis = timestampEpochMillis
)

private fun RecordingStop.toEntity(recordingId: String) = RecordingStopEntity(
    recordingId = recordingId,
    sequence = sequence,
    name = name,
    latitude = latitude,
    longitude = longitude,
    timestampEpochMillis = timestampEpochMillis
)
