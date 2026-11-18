package com.example.linienbegleiter.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(recording: RecordingEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(recordings: List<RecordingEntity>)

    @Query("SELECT * FROM recordings ORDER BY createdAtEpochMillis")
    suspend fun getAllForBackup(): List<RecordingEntity>

    @Query("UPDATE recordings SET routeColorArgb = :colorArgb, routeLineWidth = :lineWidth WHERE id = :recordingId")
    suspend fun updateAppearance(recordingId: String, colorArgb: Int, lineWidth: Float)

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getById(id: String): RecordingEntity?

    @Query("SELECT id FROM recordings WHERE lower(trim(title)) = lower(trim(:title)) AND (:exceptId IS NULL OR id != :exceptId) LIMIT 1")
    suspend fun findIdByTitle(title: String, exceptId: String? = null): String?

    @Query("UPDATE recordings SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observeById(id: String): Flow<RecordingEntity?>

    @Query(
        "SELECT * FROM recordings " +
            "WHERE status IN ('RECORDING', 'PAUSED') " +
            "ORDER BY createdAtEpochMillis DESC LIMIT 1"
    )
    suspend fun getActive(): RecordingEntity?

    @Query(
        "SELECT * FROM recordings " +
            "WHERE status IN ('RECORDING', 'PAUSED') " +
            "ORDER BY createdAtEpochMillis DESC LIMIT 1"
    )
    fun observeActive(): Flow<RecordingEntity?>

    @Query("SELECT * FROM recordings ORDER BY createdAtEpochMillis DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query(
        "UPDATE recordings SET status = 'RECORDING', startedAtEpochMillis = :startedAtEpochMillis " +
            "WHERE id = :id"
    )
    suspend fun markStarted(id: String, startedAtEpochMillis: Long)

    @Query(
        "UPDATE recordings SET status = :status, durationSeconds = :durationSeconds, " +
            "totalDistanceMeters = :totalDistanceMeters WHERE id = :id"
    )
    suspend fun updateProgress(
        id: String,
        status: com.example.linienbegleiter.data.recording.RecordingStatus,
        durationSeconds: Long,
        totalDistanceMeters: Double
    )

    @Query(
        "UPDATE recordings SET status = 'FINISHED', finishedAtEpochMillis = :finishedAtEpochMillis, " +
            "durationSeconds = :durationSeconds, totalDistanceMeters = :totalDistanceMeters " +
            "WHERE id = :id"
    )
    suspend fun markFinished(
        id: String,
        finishedAtEpochMillis: Long,
        durationSeconds: Long,
        totalDistanceMeters: Double
    )
}

@Dao
interface OriginalTrackDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(track: OriginalTrackEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(tracks: List<OriginalTrackEntity>)

    @Query("SELECT * FROM original_tracks ORDER BY createdAtEpochMillis")
    suspend fun getAllForBackup(): List<OriginalTrackEntity>

    @Query("SELECT * FROM original_tracks WHERE id = :id")
    suspend fun getById(id: String): OriginalTrackEntity?

    @Query("SELECT * FROM original_tracks WHERE recordingId = :recordingId")
    suspend fun getByRecordingId(recordingId: String): OriginalTrackEntity?

    @Query("SELECT * FROM original_tracks WHERE recordingId = :recordingId")
    fun observeByRecordingId(recordingId: String): Flow<OriginalTrackEntity?>

    @Query(
        "UPDATE original_tracks SET finalizedAtEpochMillis = :finishedAtEpochMillis " +
            "WHERE id = :id AND finalizedAtEpochMillis IS NULL"
    )
    suspend fun finalize(id: String, finishedAtEpochMillis: Long)
}

@Dao
interface RecordingPointDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(point: RecordingPointEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(points: List<RecordingPointEntity>)

    @Query("SELECT * FROM recording_points ORDER BY recordingId, sequence")
    suspend fun getAllForBackup(): List<RecordingPointEntity>

    @Query(
        "SELECT COALESCE(MAX(sequence), -1) + 1 FROM recording_points " +
            "WHERE recordingId = :recordingId"
    )
    suspend fun nextSequence(recordingId: String): Int

    @Query(
        "SELECT * FROM recording_points WHERE recordingId = :recordingId " +
            "ORDER BY sequence ASC"
    )
    suspend fun getForRecording(recordingId: String): List<RecordingPointEntity>

    @Query(
        "SELECT * FROM recording_points WHERE recordingId = :recordingId " +
            "ORDER BY sequence ASC"
    )
    fun observeForRecording(recordingId: String): Flow<List<RecordingPointEntity>>
}

@Dao
interface RecordingStopDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(stop: RecordingStopEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(stops: List<RecordingStopEntity>)

    @Query("SELECT * FROM recording_stops ORDER BY recordingId, sequence")
    suspend fun getAllForBackup(): List<RecordingStopEntity>

    @Query(
        "SELECT COALESCE(MAX(sequence), -1) + 1 FROM recording_stops " +
            "WHERE recordingId = :recordingId"
    )
    suspend fun nextSequence(recordingId: String): Int

    @Query(
        "SELECT * FROM recording_stops WHERE recordingId = :recordingId " +
            "ORDER BY sequence ASC"
    )
    suspend fun getForRecording(recordingId: String): List<RecordingStopEntity>

    @Query(
        "SELECT * FROM recording_stops WHERE recordingId = :recordingId " +
            "ORDER BY sequence ASC"
    )
    fun observeForRecording(recordingId: String): Flow<List<RecordingStopEntity>>
}
