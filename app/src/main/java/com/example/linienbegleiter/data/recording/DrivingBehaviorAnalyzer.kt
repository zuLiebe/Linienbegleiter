package com.example.linienbegleiter.data.recording

data class DrivingAnalysisStats(
    val harshBrakingCount: Int = 0,
    val harshAccelerationCount: Int = 0,
    val speedingCount: Int = 0,
    val score: Int = 100
) {
    val grade: String
        get() = when (score) {
            in 90..100 -> "⭐⭐⭐⭐⭐ Отлично"
            in 75..89 -> "⭐⭐⭐⭐ Хорошо"
            in 60..74 -> "⭐⭐⭐ Нормально"
            in 40..59 -> "⭐⭐ Требует внимания"
            else -> "⭐ Опасно"
        }
}

class DrivingBehaviorAnalyzer {
    private var harshBrakingCount = 0
    private var harshAccelerationCount = 0
    private var speedingCount = 0
    private var lastSpeedMps: Float? = null
    private var lastTimeEpochMillis: Long = 0L

    fun updateSample(speedMps: Float?, timestampEpochMillis: Long) {
        if (speedMps == null) return
        val speedKmh = speedMps * 3.6f
        if (speedKmh > 90f) {
            speedingCount++
        }
        val lastSpeed = lastSpeedMps
        val lastTime = lastTimeEpochMillis
        if (lastSpeed != null && lastTime > 0L) {
            val dtSeconds = (timestampEpochMillis - lastTime) / 1000f
            if (dtSeconds > 0.1f) {
                val acceleration = (speedMps - lastSpeed) / dtSeconds
                if (acceleration < -3.0f) {
                    harshBrakingCount++
                } else if (acceleration > 3.0f) {
                    harshAccelerationCount++
                }
            }
        }
        lastSpeedMps = speedMps
        lastTimeEpochMillis = timestampEpochMillis
    }

    fun getStats(): DrivingAnalysisStats {
        val penalties = (harshBrakingCount * 6) + (harshAccelerationCount * 4) + (speedingCount / 5 * 2)
        val score = (100 - penalties).coerceIn(0, 100)
        return DrivingAnalysisStats(
            harshBrakingCount = harshBrakingCount,
            harshAccelerationCount = harshAccelerationCount,
            speedingCount = speedingCount,
            score = score
        )
    }
}
