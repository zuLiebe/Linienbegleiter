package com.example.linienbegleiter.data.location

data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val timestampEpochMillis: Long,
    val accuracyMeters: Float?,
    val speedMetersPerSecond: Float?,
    val bearingDegrees: Float?,
    val elapsedRealtimeNanos: Long?,
    val sourceProvider: String,
    val ageMillisAtReceipt: Long,
    val isStale: Boolean
) {
    fun isSuitableForStop(
        nowEpochMillis: Long,
        maxAgeMillis: Long,
        maxAccuracyMeters: Float
    ): Boolean {
        val ageMillis = nowEpochMillis - timestampEpochMillis
        return latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0 &&
            ageMillis in -5_000L..maxAgeMillis &&
            accuracyMeters != null &&
            accuracyMeters <= maxAccuracyMeters
    }
}
