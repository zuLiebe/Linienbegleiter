package com.example.linienbegleiter.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RecordingEntity::class,
        OriginalTrackEntity::class,
        RecordingPointEntity::class,
        RecordingStopEntity::class,
        EditableRouteEntity::class,
        EditableRoutePointEntity::class,
        EditableRouteStopEntity::class,
        EditableRouteDirectionEntity::class,
        EditableRouteDirectionStopEntity::class,
        EditableRouteZoneEntity::class,
        EditableRouteZoneAnnotationEntity::class
    ],
    version = 6,
    exportSchema = true
)
@TypeConverters(RecordingTypeConverters::class)
abstract class RecordingDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun originalTrackDao(): OriginalTrackDao
    abstract fun recordingPointDao(): RecordingPointDao
    abstract fun recordingStopDao(): RecordingStopDao
    abstract fun editableRouteDao(): EditableRouteDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS editable_routes (" +
                        "id TEXT NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "createdAtEpochMillis INTEGER NOT NULL, " +
                        "sourceOriginalTrackId TEXT, " +
                        "distanceMeters REAL NOT NULL, " +
                        "PRIMARY KEY(id), " +
                        "FOREIGN KEY(sourceOriginalTrackId) REFERENCES original_tracks(id) " +
                        "ON UPDATE NO ACTION ON DELETE SET NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_editable_routes_sourceOriginalTrackId " +
                        "ON editable_routes(sourceOriginalTrackId)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS editable_route_points (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "routeId TEXT NOT NULL, " +
                        "sequence INTEGER NOT NULL, " +
                        "latitude REAL NOT NULL, " +
                        "longitude REAL NOT NULL, " +
                        "FOREIGN KEY(routeId) REFERENCES editable_routes(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_editable_route_points_routeId_sequence " +
                        "ON editable_route_points(routeId, sequence)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS editable_route_stops (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "routeId TEXT NOT NULL, " +
                        "sequence INTEGER NOT NULL, " +
                        "routePointSequence INTEGER NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "latitude REAL NOT NULL, " +
                        "longitude REAL NOT NULL, " +
                        "FOREIGN KEY(routeId) REFERENCES editable_routes(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_editable_route_stops_routeId_sequence " +
                        "ON editable_route_stops(routeId, sequence)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_editable_route_stops_routeId_routePointSequence " +
                        "ON editable_route_stops(routeId, routePointSequence)"
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE editable_routes ADD COLUMN routeKind TEXT NOT NULL " +
                        "DEFAULT 'MANUAL'"
                )
                db.execSQL(
                    "ALTER TABLE editable_routes ADD COLUMN parentRouteId TEXT"
                )
                db.execSQL(
                    "UPDATE editable_routes SET routeKind = 'RECORDING_EDIT' " +
                        "WHERE sourceOriginalTrackId IS NOT NULL"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_editable_routes_parentRouteId " +
                        "ON editable_routes(parentRouteId)"
                )
                db.execSQL(
                    "CREATE TABLE editable_route_stops_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "routeId TEXT NOT NULL, " +
                        "sequence INTEGER NOT NULL, " +
                        "routePointSequence INTEGER, " +
                        "name TEXT NOT NULL, " +
                        "latitude REAL NOT NULL, " +
                        "longitude REAL NOT NULL, " +
                        "FOREIGN KEY(routeId) REFERENCES editable_routes(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO editable_route_stops_new " +
                        "(id, routeId, sequence, routePointSequence, name, latitude, longitude) " +
                        "SELECT id, routeId, sequence, routePointSequence, name, latitude, longitude " +
                        "FROM editable_route_stops"
                )
                db.execSQL("DROP TABLE editable_route_stops")
                db.execSQL(
                    "ALTER TABLE editable_route_stops_new RENAME TO editable_route_stops"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX index_editable_route_stops_routeId_sequence " +
                        "ON editable_route_stops(routeId, sequence)"
                )
                db.execSQL(
                    "CREATE INDEX index_editable_route_stops_routeId_routePointSequence " +
                        "ON editable_route_stops(routeId, routePointSequence)"
                )
                db.execSQL(
                    "CREATE TABLE editable_route_directions (" +
                        "id TEXT NOT NULL, " +
                        "routeId TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "startStopId INTEGER NOT NULL, " +
                        "endStopId INTEGER NOT NULL, " +
                        "initialBearingDegrees REAL NOT NULL, " +
                        "isGeometryReversed INTEGER NOT NULL, " +
                        "PRIMARY KEY(id), " +
                        "FOREIGN KEY(routeId) REFERENCES editable_routes(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX index_editable_route_directions_routeId " +
                        "ON editable_route_directions(routeId)"
                )
                db.execSQL(
                    "CREATE TABLE editable_route_direction_stops (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "directionId TEXT NOT NULL, " +
                        "stopId INTEGER NOT NULL, " +
                        "sequence INTEGER NOT NULL, " +
                        "FOREIGN KEY(directionId) REFERENCES editable_route_directions(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX index_editable_route_direction_stops_directionId_sequence " +
                        "ON editable_route_direction_stops(directionId, sequence)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX index_editable_route_direction_stops_directionId_stopId " +
                        "ON editable_route_direction_stops(directionId, stopId)"
                )
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE recordings ADD COLUMN routeColorArgb INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE recordings ADD COLUMN routeLineWidth REAL NOT NULL DEFAULT 5.0"
                )
                db.execSQL(
                    "ALTER TABLE editable_routes ADD COLUMN routeColorArgb INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE editable_routes ADD COLUMN routeLineWidth REAL NOT NULL DEFAULT 5.0"
                )
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE editable_routes ADD COLUMN routeMetadataJson TEXT"
                )
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `editable_route_zones` (" +
                        "`id` TEXT NOT NULL, " +
                        "`routeId` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`centerLatitude` REAL NOT NULL, " +
                        "`centerLongitude` REAL NOT NULL, " +
                        "`radiusMeters` REAL NOT NULL, " +
                        "`targetZoom` REAL NOT NULL DEFAULT 18.0, " +
                        "`metadataJson` TEXT, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`routeId`) REFERENCES `editable_routes`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_editable_route_zones_routeId` " +
                        "ON `editable_route_zones`(`routeId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `editable_route_zone_annotations` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`zoneId` TEXT NOT NULL, " +
                        "`annotationType` TEXT NOT NULL, " +
                        "`label` TEXT, " +
                        "`geometryJson` TEXT NOT NULL, " +
                        "FOREIGN KEY(`zoneId`) REFERENCES `editable_route_zones`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_editable_route_zone_annotations_zoneId` " +
                        "ON `editable_route_zone_annotations`(`zoneId`)"
                )
            }
        }

        @Volatile
        private var instance: RecordingDatabase? = null

        fun getInstance(context: Context): RecordingDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RecordingDatabase::class.java,
                    "linienbegleiter.db"
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6
                ).build().also { instance = it }
            }
    }
}
