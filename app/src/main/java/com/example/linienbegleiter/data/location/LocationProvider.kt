package com.example.linienbegleiter.data.location

enum class LocationPermissionState {
    FINE,
    APPROXIMATE,
    MISSING
}

interface LocationProvider {
    fun start(
        config: LocationRequestConfig = LocationRequestConfig(),
        onSample: (LocationSample) -> Unit,
        onStatusChanged: (LocationProviderStatus) -> Unit
    )

    fun stop()

    /** Returns an updated position within the timeout, or null when no fix is available. */
    suspend fun getCurrentLocation(timeoutMillis: Long = 20_000L): LocationSample?

    /** Returns a cached OS location only when it is no older than [maxAgeMillis]. */
    fun getLastKnownLocation(maxAgeMillis: Long): LocationSample?

    fun getPermissionState(): LocationPermissionState

    fun isLocationAvailable(): Boolean

    fun latestSample(maxAgeMillis: Long): LocationSample?
}

data class LocationRequestConfig(
    val intervalMillis: Long = 2_000L,
    val minimumDistanceMeters: Float = 2f,
    val staleAfterMillis: Long = 15_000L,
    val stopMaximumAgeMillis: Long = 10_000L,
    val stopMaximumAccuracyMeters: Float = 50f
) {
    init {
        require(intervalMillis > 0)
        require(minimumDistanceMeters >= 0f)
        require(staleAfterMillis > 0)
        require(stopMaximumAgeMillis > 0)
        require(stopMaximumAccuracyMeters > 0f)
    }
}

sealed interface LocationProviderStatus {
    data object WaitingForSignal : LocationProviderStatus
    data object PermissionMissing : LocationProviderStatus
    data object ProviderDisabled : LocationProviderStatus
    data class ProviderAvailable(val provider: String) : LocationProviderStatus
    data class Error(val message: String) : LocationProviderStatus
}
