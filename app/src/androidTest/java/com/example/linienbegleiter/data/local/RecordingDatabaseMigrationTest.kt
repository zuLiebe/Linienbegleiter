package com.example.linienbegleiter.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingDatabaseMigrationTest {
    private val testDatabaseName = "recording-database-migration-test"

    private val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RecordingDatabase::class.java
    )

    @Test
    fun migration2To3PreservesOriginalDataAndMakesStopCoordinatesIndependent() {
        val oldDatabase = helper.createDatabase(testDatabaseName, 2)
        oldDatabase.execSQL(
            "INSERT INTO recordings " +
                "(id, title, createdAtEpochMillis, startedAtEpochMillis, finishedAtEpochMillis, " +
                "status, totalDistanceMeters, durationSeconds, originalTrackId) " +
                "VALUES ('recording-1', 'Оригинал', 1000, 1100, 2000, 'FINISHED', 125.0, 60, 'track-1')"
        )
        oldDatabase.execSQL(
            "INSERT INTO original_tracks (id, recordingId, createdAtEpochMillis, finalizedAtEpochMillis) " +
                "VALUES ('track-1', 'recording-1', 1000, 2000)"
        )
        oldDatabase.execSQL(
            "INSERT INTO recording_points " +
                "(id, recordingId, sequence, latitude, longitude, timestampEpochMillis, " +
                "accuracyMeters, speedMetersPerSecond, bearingDegrees, elapsedRealtimeNanos) " +
                "VALUES (11, 'recording-1', 0, 53.5511, 9.9937, 1200, 4.0, 3.0, 90.0, 1200000)"
        )
        oldDatabase.execSQL(
            "INSERT INTO recording_stops " +
                "(id, recordingId, sequence, name, latitude, longitude, timestampEpochMillis) " +
                "VALUES (21, 'recording-1', 0, 'Hauptbahnhof', 53.5520, 10.0060, 1500)"
        )
        oldDatabase.execSQL(
            "INSERT INTO editable_routes " +
                "(id, title, createdAtEpochMillis, sourceOriginalTrackId, distanceMeters) " +
                "VALUES ('edited-1', 'Редакция', 3000, 'track-1', 130.0)"
        )
        oldDatabase.execSQL(
            "INSERT INTO editable_route_points (id, routeId, sequence, latitude, longitude) " +
                "VALUES (31, 'edited-1', 0, 53.5511, 9.9937)"
        )
        oldDatabase.execSQL(
            "INSERT INTO editable_route_stops " +
                "(id, routeId, sequence, routePointSequence, name, latitude, longitude) " +
                "VALUES (41, 'edited-1', 0, 0, 'Hauptbahnhof', 53.5520, 10.0060)"
        )
        oldDatabase.close()

        val migrated = helper.runMigrationsAndValidate(
            testDatabaseName,
            5,
            false,
            RecordingDatabase.MIGRATION_2_3,
            RecordingDatabase.MIGRATION_3_4,
            RecordingDatabase.MIGRATION_4_5
        )
        migrated.use { database ->
            database.query(
                "SELECT latitude, longitude, sequence FROM recording_points " +
                    "WHERE id = 11"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(53.5511, cursor.getDouble(0), 0.000001)
                assertEquals(9.9937, cursor.getDouble(1), 0.000001)
                assertEquals(0, cursor.getInt(2))
            }
            database.query(
                "SELECT name, id FROM recording_stops WHERE id = 21"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Hauptbahnhof", cursor.getString(0))
                assertEquals(21L, cursor.getLong(1))
            }
            database.query(
                "SELECT routeKind, parentRouteId, routeColorArgb, routeLineWidth, " +
                    "routeMetadataJson " +
                    "FROM editable_routes WHERE id = 'edited-1'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("RECORDING_EDIT", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals(5.0, cursor.getDouble(3), 0.000001)
                assertTrue(cursor.isNull(4))
            }
            database.query(
                "SELECT routeColorArgb, routeLineWidth FROM recordings WHERE id = 'recording-1'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(5.0, cursor.getDouble(1), 0.000001)
            }
            database.query(
                "SELECT id, routePointSequence, name FROM editable_route_stops " +
                    "WHERE routeId = 'edited-1'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(41L, cursor.getLong(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals("Hauptbahnhof", cursor.getString(2))
            }

            database.execSQL(
                "INSERT INTO editable_route_stops " +
                    "(routeId, sequence, routePointSequence, name, latitude, longitude) " +
                    "VALUES ('edited-1', 1, NULL, 'Unabhängige Haltestelle', 53.5530, 10.0070)"
            )
            database.query(
                "SELECT routePointSequence FROM editable_route_stops " +
                    "WHERE name = 'Unabhängige Haltestelle'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
            database.query("SELECT COUNT(*) FROM editable_route_directions").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }
}
