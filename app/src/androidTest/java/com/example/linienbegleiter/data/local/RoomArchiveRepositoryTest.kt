package com.example.linienbegleiter.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.linienbegleiter.data.recording.RecordingPoint
import com.example.linienbegleiter.data.recording.RecordingRepository
import com.example.linienbegleiter.data.recording.RecordingStop
import com.example.linienbegleiter.data.recording.RoomRecordingRepository
import com.example.linienbegleiter.data.routes.EditableRouteDirection
import com.example.linienbegleiter.data.routes.EditableRoutePoint
import com.example.linienbegleiter.data.routes.EditableRouteRepository
import com.example.linienbegleiter.data.routes.RoomEditableRouteRepository
import com.example.linienbegleiter.data.routes.RouteDraftStop
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class RoomArchiveRepositoryTest {
    private lateinit var database: RecordingDatabase
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var routeRepository: EditableRouteRepository
    private lateinit var archiveRepository: RoomArchiveRepository

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, RecordingDatabase::class.java).build()
        recordingRepository = RoomRecordingRepository(database)
        routeRepository = RoomEditableRouteRepository(database)
        archiveRepository = RoomArchiveRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun archiveImportAddsRemappedCopyAndLeavesOriginalDataUntouched() = runBlocking {
        val recording = recordingRepository.createSession("Hamburg 5", 1_000L)
        recordingRepository.startRecording(recording.id, 1_100L)
        recordingRepository.appendPoint(
            recording.id,
            RecordingPoint(
                latitude = 53.55,
                longitude = 9.99,
                timestampEpochMillis = 1_200L,
                accuracyMeters = 4f,
                speedMetersPerSecond = 2f,
                bearingDegrees = 90f,
                elapsedRealtimeNanos = 10_000L
            )
        )
        recordingRepository.appendPoint(
            recording.id,
            RecordingPoint(
                latitude = 53.56,
                longitude = 10.01,
                timestampEpochMillis = 1_400L,
                accuracyMeters = 5f,
                speedMetersPerSecond = null,
                bearingDegrees = null,
                elapsedRealtimeNanos = 210_000L
            )
        )
        recordingRepository.appendStop(
            recording.id,
            RecordingStop(
                name = "Hauptbahnhof",
                latitude = 53.55,
                longitude = 9.99,
                timestampEpochMillis = 1_300L
            )
        )
        recordingRepository.finishRecording(recording.id, 2_000L, 9L, 140.0)
        val originalBefore = requireNotNull(recordingRepository.getDetails(recording.id))

        val route = routeRepository.createRoute(
            title = "Основная версия",
            createdAtEpochMillis = 2_100L,
            points = listOf(
                EditableRoutePoint(0, 53.55, 9.99),
                EditableRoutePoint(1, 53.56, 10.01)
            ),
            stops = listOf(
                RouteDraftStop(null, "Start", 53.55, 9.99),
                RouteDraftStop(null, "End", 53.56, 10.01)
            ),
            routeColorArgb = 0xFFCC2222.toInt()
        )
        routeRepository.saveDirections(
            route.route.id,
            listOf(
                EditableRouteDirection(
                    id = "direction-outbound",
                    routeId = route.route.id,
                    name = "Hin",
                    startStopId = route.stops.first().id,
                    endStopId = route.stops.last().id,
                    initialBearingDegrees = 90.0,
                    isGeometryReversed = false,
                    stopIdsInOrder = route.stops.map { it.id }
                )
            )
        )

        val archiveBytes = ByteArrayOutputStream()
        archiveRepository.export(archiveBytes)
        val importedSummary = archiveRepository.import(ByteArrayInputStream(archiveBytes.toByteArray()))

        assertEquals(1, importedSummary.recordingsAdded)
        assertEquals(1, importedSummary.routesAdded)
        val recordings = database.recordingDao().getAllForBackup()
        val routes = database.editableRouteDao().getAllForBackup()
        val importedRecording = recordings.single { it.id != recording.id }
        val importedRoute = routes.single { it.id != route.route.id }
        assertNotEquals(recording.id, importedRecording.id)
        assertNotEquals(route.route.id, importedRoute.id)

        val originalAfter = requireNotNull(recordingRepository.getDetails(recording.id))
        assertEquals(originalBefore.points, originalAfter.points)
        assertEquals(originalBefore.stops, originalAfter.stops)
        assertEquals(originalBefore.originalTrack, originalAfter.originalTrack)

        val importedRouteDetails = requireNotNull(routeRepository.getDetails(importedRoute.id))
        assertEquals(route.points.map { it.latitude }, importedRouteDetails.points.map { it.latitude })
        assertEquals(route.stops.map { it.name }, importedRouteDetails.stops.map { it.name })
        assertNotEquals(route.stops.map { it.id }, importedRouteDetails.stops.map { it.id })
        assertEquals(1, importedRouteDetails.directions.size)
        assertEquals(
            importedRouteDetails.stops.map { it.id },
            importedRouteDetails.directions.single().stopIdsInOrder
        )
        assertEquals(null, importedRoute.sourceOriginalTrackId)
        assertEquals(null, importedRoute.parentRouteId)
    }
}
