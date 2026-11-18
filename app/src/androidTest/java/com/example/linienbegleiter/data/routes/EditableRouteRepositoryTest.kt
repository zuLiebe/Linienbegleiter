package com.example.linienbegleiter.data.routes

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.linienbegleiter.data.local.RecordingDatabase
import com.example.linienbegleiter.data.recording.RecordingPoint
import com.example.linienbegleiter.data.recording.RecordingRepository
import com.example.linienbegleiter.data.recording.RecordingStatus
import com.example.linienbegleiter.data.recording.RecordingStop
import com.example.linienbegleiter.data.recording.RoomRecordingRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditableRouteRepositoryTest {
    private lateinit var database: RecordingDatabase
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var routeRepository: RoomEditableRouteRepository

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(
            context,
            RecordingDatabase::class.java
        ).build()
        recordingRepository = RoomRecordingRepository(database)
        routeRepository = RoomEditableRouteRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun editedRouteIsIndependentFromGpsRecording() = runBlocking {
        val session = recordingRepository.createSession("Оригинальный маршрут", 1_000L)
        recordingRepository.startRecording(session.id, 1_100L)
        recordingRepository.appendPoint(
            session.id,
            RecordingPoint(
                latitude = 53.5511,
                longitude = 9.9937,
                timestampEpochMillis = 1_200L,
                accuracyMeters = 4f,
                speedMetersPerSecond = 2f,
                bearingDegrees = 90f,
                elapsedRealtimeNanos = 10_000L
            )
        )
        recordingRepository.appendPoint(
            session.id,
            RecordingPoint(
                latitude = 53.5520,
                longitude = 9.9950,
                timestampEpochMillis = 1_400L,
                accuracyMeters = 5f,
                speedMetersPerSecond = 2f,
                bearingDegrees = 90f,
                elapsedRealtimeNanos = 210_000L
            )
        )
        recordingRepository.appendStop(
            session.id,
            RecordingStop(
                name = "Hauptbahnhof",
                latitude = 53.5515,
                longitude = 9.9940,
                timestampEpochMillis = 1_300L
            )
        )
        recordingRepository.finishRecording(
            recordingId = session.id,
            finishedAtEpochMillis = 2_000L,
            durationSeconds = 1L,
            totalDistanceMeters = 130.0
        )
        val originalBefore = requireNotNull(recordingRepository.getDetails(session.id))

        val editable = routeRepository.createRoute(
            title = "Самостоятельный маршрут",
            createdAtEpochMillis = 2_100L,
            points = originalBefore.points.mapIndexed { index, point ->
                EditableRoutePoint(index, point.latitude, point.longitude)
            },
            stops = originalBefore.stops.map { stop ->
                RouteDraftStop(
                    routePointSequence = null,
                    name = stop.name,
                    latitude = stop.latitude,
                    longitude = stop.longitude
                )
            },
            routeKind = EditableRouteKind.MANUAL
        )

        val movedPoints = editable.points.mapIndexed { index, point ->
            if (index == 1) point.copy(latitude = point.latitude + 0.001)
            else point
        }
        routeRepository.updateRoute(
            routeId = editable.route.id,
            title = "Основная редакция",
            points = movedPoints,
            stops = editable.stops.map { stop ->
                RouteDraftStop(
                    routePointSequence = stop.routePointSequence,
                    name = stop.name,
                    latitude = stop.latitude,
                    longitude = stop.longitude,
                    id = stop.id
                )
            }
        )

        val originalAfter = requireNotNull(recordingRepository.getDetails(session.id))
        val editedAfter = requireNotNull(routeRepository.getDetails(editable.route.id))

        assertEquals(RecordingStatus.FINISHED, originalAfter.session.status)
        assertEquals(originalBefore.points, originalAfter.points)
        assertEquals(originalBefore.stops, originalAfter.stops)
        assertEquals(null, editedAfter.route.sourceOriginalTrackId)
        assertEquals(null, editedAfter.route.parentRouteId)
        assertEquals(EditableRouteKind.MANUAL, editedAfter.route.routeKind)
        assertEquals(movedPoints.map { it.copy(id = 0L) }, editedAfter.points.map { it.copy(id = 0L) })
    }

    @Test
    fun duplicateTitlesAreRejectedAcrossRecordingAndRouteTables() = runBlocking {
        recordingRepository.createSession("Одинаковое название", 1_000L)

        try {
            routeRepository.createRoute(
                title = "Одинаковое название",
                createdAtEpochMillis = 2_000L,
                points = listOf(
                    EditableRoutePoint(0, 53.55, 9.99),
                    EditableRoutePoint(1, 53.56, 10.00)
                ),
                stops = emptyList()
            )
            throw AssertionError("Одинаковые названия должны отклоняться.")
        } catch (expected: IllegalStateException) {
            assertEquals(
                "Маршрут с названием «Одинаковое название» уже существует. Укажите другое название.",
                expected.message
            )
        }
    }

    @Test
    fun deletingIndependentRouteKeepsGpsRecording() = runBlocking {
        val session = recordingRepository.createSession("Исходная запись", 1_000L)
        recordingRepository.startRecording(session.id, 1_100L)
        val first = RecordingPoint(
            latitude = 53.55, longitude = 9.99, timestampEpochMillis = 1_200L,
            accuracyMeters = 4f, speedMetersPerSecond = 1f, bearingDegrees = 90f,
            elapsedRealtimeNanos = 10_000L
        )
        val second = RecordingPoint(
            latitude = 53.56, longitude = 10.00, timestampEpochMillis = 1_400L,
            accuracyMeters = 4f, speedMetersPerSecond = 1f, bearingDegrees = 90f,
            elapsedRealtimeNanos = 210_000L
        )
        recordingRepository.appendPoint(session.id, first)
        recordingRepository.appendPoint(session.id, second)
        recordingRepository.finishRecording(session.id, 2_000L, 1L, 1_000.0)
        val originalBefore = requireNotNull(recordingRepository.getDetails(session.id))
        val editable = routeRepository.createRoute(
            title = "Редактируемая копия",
            createdAtEpochMillis = 2_100L,
            points = originalBefore.points.mapIndexed { index, point ->
                EditableRoutePoint(index, point.latitude, point.longitude)
            },
            stops = emptyList(),
            routeKind = EditableRouteKind.MANUAL
        )

        routeRepository.deleteRoute(editable.route.id)

        assertEquals(null, routeRepository.getDetails(editable.route.id))
        assertEquals(originalBefore, recordingRepository.getDetails(session.id))
    }

    @Test
    fun directionUsesBearingAndStoresSeparateReverseOrder() {
        val stops = listOf(
            EditableRouteStop(0, null, "A", 53.55, 9.99, 11L),
            EditableRouteStop(1, null, "B", 53.55, 10.00, 12L),
            EditableRouteStop(2, null, "C", 53.55, 10.01, 13L)
        )

        val directions = createRouteDirections(
            routeId = "route",
            stops = stops,
            startStopIndex = 2,
            includeReverse = true
        )

        assertEquals("N", compassDirection(360.0).code)
        assertEquals("W", compassDirection(directions.first().initialBearingDegrees).code)
        assertEquals(listOf(13L, 11L, 12L), directions.first().stopIdsInOrder)
        assertEquals(listOf(12L, 11L, 13L), directions[1].stopIdsInOrder)
        assertEquals(true, directions.first().isGeometryReversed)
        assertEquals(false, directions[1].isGeometryReversed)
    }
}
