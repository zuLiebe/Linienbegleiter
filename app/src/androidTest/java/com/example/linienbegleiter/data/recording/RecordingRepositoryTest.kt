package com.example.linienbegleiter.data.recording

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.example.linienbegleiter.data.local.RecordingDatabase
import com.example.linienbegleiter.data.routes.EditableRouteKind
import com.example.linienbegleiter.data.routes.EditableRoutePoint
import com.example.linienbegleiter.data.routes.RouteDraftStop
import com.example.linienbegleiter.data.routes.RoomEditableRouteRepository

@RunWith(AndroidJUnit4::class)
class RecordingRepositoryTest {
    private lateinit var database: RecordingDatabase
    private lateinit var repository: RoomRecordingRepository

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(
            context,
            RecordingDatabase::class.java
        ).build()
        repository = RoomRecordingRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun recordingAndOriginalTrackCanBeSavedAndReadInSequenceOrder() = runBlocking {
        val session = repository.createSession("Тестовый маршрут", 1_000L)
        repository.startRecording(session.id, 1_100L)

        repository.appendPoint(
            session.id,
            RecordingPoint(
                latitude = 52.5200,
                longitude = 13.4050,
                timestampEpochMillis = 1_200L,
                accuracyMeters = 4f,
                speedMetersPerSecond = 3f,
                bearingDegrees = 90f,
                elapsedRealtimeNanos = 10_000L
            )
        )
        repository.appendPoint(
            session.id,
            RecordingPoint(
                latitude = 52.5210,
                longitude = 13.4060,
                timestampEpochMillis = 1_400L,
                accuracyMeters = 5f,
                speedMetersPerSecond = null,
                bearingDegrees = null,
                elapsedRealtimeNanos = 210_000L
            )
        )
        repository.appendStop(
            session.id,
            RecordingStop(
                name = "Alexanderplatz",
                latitude = 52.5220,
                longitude = 13.4070,
                timestampEpochMillis = 1_500L
            )
        )
        repository.finishRecording(
            recordingId = session.id,
            finishedAtEpochMillis = 2_000L,
            durationSeconds = 9L,
            totalDistanceMeters = 140.0
        )

        var appendAfterFinishRejected = false
        try {
            repository.appendPoint(
                session.id,
                RecordingPoint(
                    latitude = 52.5230,
                    longitude = 13.4080,
                    timestampEpochMillis = 2_100L,
                    accuracyMeters = 4f,
                    speedMetersPerSecond = null,
                    bearingDegrees = null,
                    elapsedRealtimeNanos = 710_000L
                )
            )
        } catch (_: IllegalStateException) {
            appendAfterFinishRejected = true
        }
        assertEquals(true, appendAfterFinishRejected)

        val saved = repository.getDetails(session.id)
        assertNotNull(saved)
        saved!!
        assertEquals(RecordingStatus.FINISHED, saved.session.status)
        assertEquals(session.originalTrackId, saved.originalTrack.id)
        assertEquals(2, saved.points.size)
        assertEquals(listOf(0, 1), saved.points.map { it.sequence })
        assertEquals(52.5200, saved.points.first().latitude, 0.000001)
        assertEquals("Alexanderplatz", saved.stops.single().name)
        assertEquals(0, saved.stops.single().sequence)
        assertEquals(2_000L, saved.originalTrack.finalizedAtEpochMillis)

        val reread = repository.getDetails(session.id)
        assertEquals(saved.points, reread?.points)
        assertEquals(saved.stops, reread?.stops)
    }

    @Test
    fun deletingFinishedRecordingRemovesItsTrackAndChildrenOnly() = runBlocking {
        val target = repository.createSession("Удаляемый трек", 1_000L)
        repository.startRecording(target.id, 1_100L)
        repository.appendPoint(
            target.id,
            RecordingPoint(
                latitude = 52.5200,
                longitude = 13.4050,
                timestampEpochMillis = 1_200L,
                accuracyMeters = 4f,
                speedMetersPerSecond = null,
                bearingDegrees = null,
                elapsedRealtimeNanos = 10_000L
            )
        )
        repository.appendPoint(
            target.id,
            RecordingPoint(
                latitude = 52.5210,
                longitude = 13.4060,
                timestampEpochMillis = 1_250L,
                accuracyMeters = 4f,
                speedMetersPerSecond = null,
                bearingDegrees = null,
                elapsedRealtimeNanos = 60_000L
            )
        )
        repository.appendStop(
            target.id,
            RecordingStop(
                name = "Остановка",
                latitude = 52.5210,
                longitude = 13.4060,
                timestampEpochMillis = 1_300L
            )
        )
        repository.finishRecording(target.id, 2_000L, 9L, 100.0)

        val editableRepository = RoomEditableRouteRepository(database)
        val detailsBeforeDelete = requireNotNull(repository.getDetails(target.id))
        val independentRoute = editableRepository.createRoute(
            title = "Самостоятельный маршрут",
            createdAtEpochMillis = 2_050L,
            points = detailsBeforeDelete.points.mapIndexed { index, point ->
                EditableRoutePoint(index, point.latitude, point.longitude)
            },
            stops = detailsBeforeDelete.stops.map { stop ->
                RouteDraftStop(
                    routePointSequence = null,
                    name = stop.name,
                    latitude = stop.latitude,
                    longitude = stop.longitude
                )
            },
            routeKind = EditableRouteKind.MANUAL
        )

        val unrelated = repository.createSession("Оставить", 2_100L)
        val originalTrackId = target.originalTrackId
        repository.deleteSession(target.id)

        assertNull(repository.getDetails(target.id))
        assertNull(database.originalTrackDao().getById(originalTrackId))
        assertEquals(emptyList<com.example.linienbegleiter.data.local.RecordingPointEntity>(),
            database.recordingPointDao().getForRecording(target.id))
        assertEquals(emptyList<com.example.linienbegleiter.data.local.RecordingStopEntity>(),
            database.recordingStopDao().getForRecording(target.id))
        assertNotNull(repository.getDetails(unrelated.id))
        val preservedRoute = requireNotNull(
            editableRepository.getDetails(independentRoute.route.id)
        )
        assertNull(preservedRoute.route.sourceOriginalTrackId)
        assertEquals(independentRoute.points, preservedRoute.points)
    }

    @Test
    fun deletingActiveRecordingIsRejectedAndLeavesSessionIntact() = runBlocking {
        val session = repository.createSession("Активный трек", 1_000L)
        repository.startRecording(session.id, 1_100L)

        var rejected = false
        try {
            repository.deleteSession(session.id)
        } catch (_: IllegalStateException) {
            rejected = true
        }

        assertEquals(true, rejected)
        assertNotNull(repository.getDetails(session.id))
        assertEquals(RecordingStatus.RECORDING, repository.getSession(session.id)?.status)
    }
}
