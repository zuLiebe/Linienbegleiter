package com.example.linienbegleiter.data.recording

import android.os.SystemClock
import com.example.linienbegleiter.data.location.LocationProvider
import com.example.linienbegleiter.data.location.LocationProviderStatus
import com.example.linienbegleiter.data.location.LocationRequestConfig
import com.example.linienbegleiter.data.location.LocationSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class RecordingRuntimeState(
    val session: RecordingSession? = null,
    val locationStatus: LocationProviderStatus = LocationProviderStatus.WaitingForSignal,
    val pointCount: Int = 0,
    val stopCount: Int = 0,
    val lastSample: LocationSample? = null,
    val totalDistanceMeters: Double = 0.0,
    val durationSeconds: Long = 0,
    val errorMessage: String? = null,
    val drivingStats: DrivingAnalysisStats? = null
)

class NoFreshLocationException :
    IllegalStateException("Нет свежей и достаточно точной GPS-координаты. Подождите фикса GPS и попробуйте снова.")

class RecordingManager(
    private val repository: RecordingRepository,
    private val locationProvider: LocationProvider,
    private val scope: CoroutineScope,
    private val locationConfig: LocationRequestConfig = LocationRequestConfig()
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(RecordingRuntimeState())
    private val drivingAnalyzer = DrivingBehaviorAnalyzer()

    val recordingState: StateFlow<RecordingRuntimeState> = mutableState.asStateFlow()

    private var activeSession: RecordingSession? = null
    private var previousGoodPoint: RecordingPoint? = null
    private var accumulatedDurationMillis: Long = 0
    private var activeSegmentStartedAtElapsedMillis: Long? = null

    fun getRecordingState(): RecordingRuntimeState =
        mutableState.value.copy(durationSeconds = currentDurationSeconds())

    suspend fun getActiveRecording(): RecordingSession? =
        repository.getActiveSession()

    fun refreshElapsedDuration() {
        if (activeSession?.status == RecordingStatus.RECORDING) {
            mutableState.update { it.copy(durationSeconds = currentDurationSeconds()) }
        }
    }

    suspend fun checkpointDuration() {
        mutex.withLock {
            val session = activeSession ?: return
            if (session.status != RecordingStatus.RECORDING) return
            val duration = currentDurationSeconds()
            repository.updateRecordingStatus(
                recordingId = session.id,
                status = RecordingStatus.RECORDING,
                durationSeconds = duration,
                totalDistanceMeters = mutableState.value.totalDistanceMeters
            )
            activeSession = repository.getSession(session.id)
            mutableState.update {
                it.copy(session = activeSession, durationSeconds = duration)
            }
        }
    }

    suspend fun startRecording(recordingId: String) {
        mutex.withLock {
            val session = requireNotNull(repository.getSession(recordingId)) {
                "Запись не найдена."
            }
            check(session.status == RecordingStatus.IDLE) {
                "Эта запись уже была начата."
            }
            repository.startRecording(recordingId, System.currentTimeMillis())
            activeSession = requireNotNull(repository.getSession(recordingId))
            accumulatedDurationMillis = 0
            activeSegmentStartedAtElapsedMillis = SystemClock.elapsedRealtime()
            previousGoodPoint = null
            mutableState.value = RecordingRuntimeState(
                session = activeSession,
                locationStatus = LocationProviderStatus.WaitingForSignal
            )
        }
        startLocationUpdates()
    }

    suspend fun pauseRecording() {
        locationProvider.stop()
        mutex.withLock {
            val session = requireActiveSession()
            if (session.status == RecordingStatus.PAUSED) return
            check(session.status == RecordingStatus.RECORDING) {
                "Запись нельзя поставить на паузу в текущем состоянии."
            }

            val duration = currentDurationSeconds()
            repository.updateRecordingStatus(
                recordingId = session.id,
                status = RecordingStatus.PAUSED,
                durationSeconds = duration,
                totalDistanceMeters = mutableState.value.totalDistanceMeters
            )
            accumulatedDurationMillis = duration * 1_000L
            activeSegmentStartedAtElapsedMillis = null
            activeSession = requireNotNull(repository.getSession(session.id))
            mutableState.update {
                it.copy(session = activeSession, durationSeconds = duration)
            }
        }
    }

    suspend fun resumeRecording() {
        mutex.withLock {
            val session = requireActiveSession()
            check(session.status == RecordingStatus.PAUSED) {
                "Продолжить можно только запись на паузе."
            }
            repository.updateRecordingStatus(
                recordingId = session.id,
                status = RecordingStatus.RECORDING,
                durationSeconds = session.durationSeconds,
                totalDistanceMeters = session.totalDistanceMeters
            )
            activeSession = requireNotNull(repository.getSession(session.id))
            accumulatedDurationMillis = session.durationSeconds * 1_000L
            activeSegmentStartedAtElapsedMillis = SystemClock.elapsedRealtime()
            mutableState.update {
                it.copy(
                    session = activeSession,
                    locationStatus = LocationProviderStatus.WaitingForSignal
                )
            }
        }
        startLocationUpdates()
    }

    suspend fun stopRecording(): RecordingSession {
        locationProvider.stop()
        return mutex.withLock {
            val session = requireActiveSession()
            val finishedAt = System.currentTimeMillis()
            val duration = currentDurationSeconds()
            repository.finishRecording(
                recordingId = session.id,
                finishedAtEpochMillis = finishedAt,
                durationSeconds = duration,
                totalDistanceMeters = mutableState.value.totalDistanceMeters
            )
            activeSegmentStartedAtElapsedMillis = null
            activeSession = requireNotNull(repository.getSession(session.id))
            mutableState.update {
                it.copy(
                    session = activeSession,
                    durationSeconds = duration,
                    locationStatus = LocationProviderStatus.WaitingForSignal
                )
            }
            activeSession!!
        }
    }

    suspend fun addLocationPoint(sample: LocationSample) {
        mutex.withLock {
            val session = activeSession ?: repository.getActiveSession()?.also {
                activeSession = it
            } ?: return
            if (session.status != RecordingStatus.RECORDING) return

            val saved = repository.appendPoint(
                session.id,
                RecordingPoint(
                    latitude = sample.latitude,
                    longitude = sample.longitude,
                    timestampEpochMillis = sample.timestampEpochMillis,
                    accuracyMeters = sample.accuracyMeters,
                    speedMetersPerSecond = sample.speedMetersPerSecond,
                    bearingDegrees = sample.bearingDegrees,
                    elapsedRealtimeNanos = sample.elapsedRealtimeNanos
                )
            )

            val previous = previousGoodPoint
            val isGoodForDistance = !sample.isStale &&
                sample.accuracyMeters != null &&
                sample.accuracyMeters <= locationConfig.stopMaximumAccuracyMeters
            var distance = mutableState.value.totalDistanceMeters
            if (isGoodForDistance && previous != null) {
                val timeDelta = saved.timestampEpochMillis - previous.timestampEpochMillis
                if (timeDelta in 1..MAX_CONNECTED_GAP_MILLIS) {
                    val segmentDistance = distanceBetweenMeters(
                        previous.latitude,
                        previous.longitude,
                        saved.latitude,
                        saved.longitude
                    )
                    val speed = segmentDistance / (timeDelta / 1_000.0)
                    if (speed <= MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND) {
                        distance += segmentDistance
                    }
                }
            }
            previousGoodPoint = if (isGoodForDistance) saved else null

            drivingAnalyzer.updateSample(sample.speedMetersPerSecond, sample.timestampEpochMillis)
            val stats = drivingAnalyzer.getStats()

            val duration = currentDurationSeconds()
            repository.updateRecordingStatus(
                recordingId = session.id,
                status = RecordingStatus.RECORDING,
                durationSeconds = duration,
                totalDistanceMeters = distance
            )
            activeSession = repository.getSession(session.id)
            mutableState.update {
                it.copy(
                    session = activeSession,
                    pointCount = it.pointCount + 1,
                    lastSample = sample,
                    totalDistanceMeters = distance,
                    durationSeconds = duration,
                    drivingStats = stats
                )
            }
        }
    }

    suspend fun addStop(name: String): RecordingStop = mutex.withLock {
        val session = requireActiveSession()
        check(session.status == RecordingStatus.RECORDING) {
            "Добавлять остановки можно только во время записи."
        }
        val sample = locationProvider.latestSample(locationConfig.stopMaximumAgeMillis)
            ?: throw NoFreshLocationException()
        val stop = repository.appendStop(
            session.id,
            RecordingStop(
                name = name,
                latitude = sample.latitude,
                longitude = sample.longitude,
                timestampEpochMillis = sample.timestampEpochMillis
            )
        )
        mutableState.update { it.copy(stopCount = it.stopCount + 1) }
        stop
    }

    suspend fun restoreActiveRecording(): RecordingSession? {
        val session = repository.getActiveSession()
        if (session == null) {
            activeSession = null
            mutableState.value = RecordingRuntimeState()
            return null
        }

        val details = repository.getDetails(session.id)
        mutex.withLock {
            activeSession = session
            accumulatedDurationMillis = session.durationSeconds * 1_000L
            activeSegmentStartedAtElapsedMillis =
                if (session.status == RecordingStatus.RECORDING) {
                    SystemClock.elapsedRealtime()
                } else {
                    null
                }
            previousGoodPoint = details?.points?.lastOrNull()?.takeIf {
                it.accuracyMeters != null &&
                    it.accuracyMeters <= locationConfig.stopMaximumAccuracyMeters
            }
            mutableState.value = RecordingRuntimeState(
                session = session,
                locationStatus = LocationProviderStatus.WaitingForSignal,
                pointCount = details?.points?.size ?: 0,
                stopCount = details?.stops?.size ?: 0,
                totalDistanceMeters = session.totalDistanceMeters,
                durationSeconds = session.durationSeconds
            )
        }

        if (session.status == RecordingStatus.RECORDING) {
            startLocationUpdates()
        }
        return session
    }

    fun release() {
        locationProvider.stop()
    }

    private fun startLocationUpdates() {
        locationProvider.start(
            config = locationConfig,
            onSample = { sample ->
                mutableState.update {
                    it.copy(
                        locationStatus = LocationProviderStatus.ProviderAvailable(
                            sample.sourceProvider
                        ),
                        lastSample = sample
                    )
                }
                scope.launch(Dispatchers.IO) {
                    runCatching { addLocationPoint(sample) }
                        .onFailure { error ->
                            mutableState.update { it.copy(errorMessage = error.message) }
                        }
                }
            },
            onStatusChanged = { status ->
                mutableState.update { it.copy(locationStatus = status) }
            }
        )
    }

    private suspend fun requireActiveSession(): RecordingSession =
        activeSession ?: repository.getActiveSession()?.also { activeSession = it }
        ?: error("Активная запись не найдена.")

    private fun currentDurationSeconds(): Long {
        val currentSegment = activeSegmentStartedAtElapsedMillis?.let { startedAt ->
            (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0)
        } ?: 0L
        return (accumulatedDurationMillis + currentSegment) / 1_000L
    }

    private fun distanceBetweenMeters(
        latitudeA: Double,
        longitudeA: Double,
        latitudeB: Double,
        longitudeB: Double
    ): Double {
        val earthRadiusMeters = 6_371_000.0
        val latitudeDelta = Math.toRadians(latitudeB - latitudeA)
        val longitudeDelta = Math.toRadians(longitudeB - longitudeA)
        val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(Math.toRadians(latitudeA)) *
            cos(Math.toRadians(latitudeB)) *
            sin(longitudeDelta / 2) *
            sin(longitudeDelta / 2)
        return 2 * earthRadiusMeters * atan2(sqrt(a), sqrt(1 - a))
    }

    private companion object {
        const val MAX_CONNECTED_GAP_MILLIS = 60_000L
        const val MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND = 45.0
    }
}
