package com.example.linienbegleiter.data.recording

enum class RecordingStatus {
    IDLE,
    RECORDING,
    PAUSED,
    FINISHED
}

data class RecordingSession(
    val id: String,
    val title: String,
    val createdAtEpochMillis: Long,
    val startedAtEpochMillis: Long?,
    val finishedAtEpochMillis: Long?,
    val status: RecordingStatus,
    val totalDistanceMeters: Double,
    val durationSeconds: Long,
    val originalTrackId: String,
    val routeColorArgb: Int = 0,
    val routeLineWidth: Float = 5f
)

data class OriginalTrack(
    val id: String,
    val recordingId: String,
    val createdAtEpochMillis: Long,
    val finalizedAtEpochMillis: Long?
)

data class RecordingPoint(
    val sequence: Int = 0,
    val latitude: Double,
    val longitude: Double,
    val timestampEpochMillis: Long,
    val accuracyMeters: Float?,
    val speedMetersPerSecond: Float?,
    val bearingDegrees: Float?,
    val elapsedRealtimeNanos: Long?
)

data class RecordingStop(
    val sequence: Int = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val timestampEpochMillis: Long
)

data class RecordingDetails(
    val session: RecordingSession,
    val originalTrack: OriginalTrack,
    val points: List<RecordingPoint>,
    val stops: List<RecordingStop>
)
