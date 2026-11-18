package com.example.linienbegleiter.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.example.linienbegleiter.data.recording.RecordingStatus

@Entity(tableName = "recordings")
@TypeConverters(RecordingTypeConverters::class)
data class RecordingEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAtEpochMillis: Long,
    val startedAtEpochMillis: Long?,
    val finishedAtEpochMillis: Long?,
    val status: RecordingStatus,
    val totalDistanceMeters: Double,
    val durationSeconds: Long,
    val originalTrackId: String,
    @ColumnInfo(defaultValue = "0") val routeColorArgb: Int = 0,
    @ColumnInfo(defaultValue = "5.0") val routeLineWidth: Float = 5f
)

@Entity(
    tableName = "original_tracks",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["recordingId"], unique = true)]
)
data class OriginalTrackEntity(
    @PrimaryKey val id: String,
    val recordingId: String,
    val createdAtEpochMillis: Long,
    val finalizedAtEpochMillis: Long?
)

@Entity(
    tableName = "recording_points",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["recordingId", "sequence"], unique = true)]
)
data class RecordingPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String,
    val sequence: Int,
    val latitude: Double,
    val longitude: Double,
    val timestampEpochMillis: Long,
    val accuracyMeters: Float?,
    val speedMetersPerSecond: Float?,
    val bearingDegrees: Float?,
    val elapsedRealtimeNanos: Long?
)

@Entity(
    tableName = "recording_stops",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["recordingId", "sequence"], unique = true)]
)
data class RecordingStopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String,
    val sequence: Int,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val timestampEpochMillis: Long
)

class RecordingTypeConverters {
    @TypeConverter
    fun recordingStatusToString(status: RecordingStatus): String = status.name

    @TypeConverter
    fun stringToRecordingStatus(value: String): RecordingStatus =
        RecordingStatus.valueOf(value)
}
