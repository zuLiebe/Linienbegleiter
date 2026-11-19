package com.example.linienbegleiter.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EditableRouteDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRoute(route: EditableRouteEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRoutes(routes: List<EditableRouteEntity>)

    @Query("SELECT * FROM editable_routes ORDER BY createdAtEpochMillis")
    suspend fun getAllForBackup(): List<EditableRouteEntity>

    @Update
    suspend fun updateRoute(route: EditableRouteEntity)

    @Query("UPDATE editable_routes SET routeColorArgb = :colorArgb, routeLineWidth = :lineWidth WHERE id = :routeId")
    suspend fun updateAppearance(routeId: String, colorArgb: Int, lineWidth: Float)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPoints(points: List<EditableRoutePointEntity>): List<Long>

    @Query("SELECT * FROM editable_route_points ORDER BY routeId, sequence")
    suspend fun getAllPointsForBackup(): List<EditableRoutePointEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStops(stops: List<EditableRouteStopEntity>): List<Long>

    @Query("SELECT * FROM editable_route_stops ORDER BY routeId, sequence")
    suspend fun getAllStopsForBackup(): List<EditableRouteStopEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDirection(direction: EditableRouteDirectionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDirections(directions: List<EditableRouteDirectionEntity>)

    @Query("SELECT * FROM editable_route_directions ORDER BY routeId, name")
    suspend fun getAllDirectionsForBackup(): List<EditableRouteDirectionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDirectionStops(stops: List<EditableRouteDirectionStopEntity>)

    @Query("SELECT * FROM editable_route_direction_stops ORDER BY directionId, sequence")
    suspend fun getAllDirectionStopsForBackup(): List<EditableRouteDirectionStopEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertZones(zones: List<EditableRouteZoneEntity>)

    @Query("SELECT * FROM editable_route_zones ORDER BY routeId, title")
    suspend fun getAllZonesForBackup(): List<EditableRouteZoneEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertZoneAnnotations(annotations: List<EditableRouteZoneAnnotationEntity>)

    @Query("SELECT * FROM editable_route_zone_annotations ORDER BY zoneId")
    suspend fun getAllZoneAnnotationsForBackup(): List<EditableRouteZoneAnnotationEntity>

    @Query("SELECT * FROM editable_route_zones WHERE routeId = :routeId ORDER BY title ASC")
    suspend fun getZones(routeId: String): List<EditableRouteZoneEntity>

    @Query("SELECT * FROM editable_route_zones WHERE routeId = :routeId ORDER BY title ASC")
    fun observeZones(routeId: String): Flow<List<EditableRouteZoneEntity>>

    @Query("SELECT * FROM editable_route_zone_annotations WHERE zoneId = :zoneId")
    suspend fun getZoneAnnotations(zoneId: String): List<EditableRouteZoneAnnotationEntity>

    @Query("DELETE FROM editable_route_zones WHERE routeId = :routeId")
    suspend fun deleteZones(routeId: String)

    @Query("DELETE FROM editable_route_zones WHERE id = :zoneId")
    suspend fun deleteZoneById(zoneId: String)

    @Query("SELECT * FROM editable_routes ORDER BY createdAtEpochMillis DESC")
    fun observeAll(): Flow<List<EditableRouteEntity>>

    @Query("SELECT * FROM editable_routes ORDER BY createdAtEpochMillis DESC")
    suspend fun getAll(): List<EditableRouteEntity>

    @Query("SELECT * FROM editable_routes WHERE id = :id")
    suspend fun getById(id: String): EditableRouteEntity?

    @Query("SELECT id FROM editable_routes WHERE lower(trim(title)) = lower(trim(:title)) AND (:exceptId IS NULL OR id != :exceptId) LIMIT 1")
    suspend fun findIdByTitle(title: String, exceptId: String? = null): String?

    @Query("UPDATE editable_routes SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("UPDATE editable_routes SET sourceOriginalTrackId = NULL, parentRouteId = NULL, routeKind = CASE WHEN routeKind IN ('RECORDING_EDIT', 'VARIANT') THEN 'MANUAL' ELSE routeKind END")
    suspend fun detachLegacyRouteLinks()

    @Query("DELETE FROM editable_routes WHERE id = :id")
    suspend fun deleteRoute(id: String): Int

    @Query("SELECT * FROM editable_routes WHERE id = :id")
    fun observeById(id: String): Flow<EditableRouteEntity?>

    @Query(
        "SELECT * FROM editable_route_points WHERE routeId = :routeId " +
            "ORDER BY sequence ASC"
    )
    suspend fun getPoints(routeId: String): List<EditableRoutePointEntity>

    @Query(
        "SELECT * FROM editable_route_points WHERE routeId = :routeId " +
            "ORDER BY sequence ASC"
    )
    fun observePoints(routeId: String): Flow<List<EditableRoutePointEntity>>

    @Query(
        "SELECT * FROM editable_route_stops WHERE routeId = :routeId " +
            "ORDER BY sequence ASC"
    )
    suspend fun getStops(routeId: String): List<EditableRouteStopEntity>

    @Query(
        "SELECT * FROM editable_route_stops WHERE routeId = :routeId " +
            "ORDER BY sequence ASC"
    )
    fun observeStops(routeId: String): Flow<List<EditableRouteStopEntity>>

    @Query(
        "SELECT * FROM editable_route_directions WHERE routeId = :routeId " +
            "ORDER BY name ASC"
    )
    suspend fun getDirections(routeId: String): List<EditableRouteDirectionEntity>

    @Query(
        "SELECT * FROM editable_route_directions WHERE routeId = :routeId " +
            "ORDER BY name ASC"
    )
    fun observeDirections(routeId: String): Flow<List<EditableRouteDirectionEntity>>

    @Query(
        "SELECT directionStops.* FROM editable_route_direction_stops AS directionStops " +
            "INNER JOIN editable_route_directions AS directions " +
            "ON directions.id = directionStops.directionId " +
            "WHERE directions.routeId = :routeId " +
            "ORDER BY directionStops.directionId, directionStops.sequence"
    )
    fun observeDirectionStops(routeId: String): Flow<List<EditableRouteDirectionStopEntity>>

    @Query(
        "SELECT * FROM editable_route_direction_stops WHERE directionId = :directionId " +
            "ORDER BY sequence ASC"
    )
    suspend fun getDirectionStops(directionId: String): List<EditableRouteDirectionStopEntity>

    @Query("DELETE FROM editable_route_points WHERE routeId = :routeId")
    suspend fun deletePoints(routeId: String)

    @Query("DELETE FROM editable_route_stops WHERE routeId = :routeId")
    suspend fun deleteStops(routeId: String)

    @Query("DELETE FROM editable_route_directions WHERE routeId = :routeId")
    suspend fun deleteDirections(routeId: String)
}
