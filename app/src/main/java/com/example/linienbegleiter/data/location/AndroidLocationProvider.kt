package com.example.linienbegleiter.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class AndroidLocationProvider(
    context: Context
) : LocationProvider {
    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile
    private var latest: LocationSample? = null

    @Volatile
    private var listener: LocationListener? = null

    @Volatile
    private var locationThread: HandlerThread? = null

    private var activeConfig = LocationRequestConfig()

    override fun start(
        config: LocationRequestConfig,
        onSample: (LocationSample) -> Unit,
        onStatusChanged: (LocationProviderStatus) -> Unit
    ) {
        synchronized(this) {
            if (listener != null) return
            activeConfig = config

            val permission = foregroundLocationPermission()
            if (permission == ForegroundLocationPermission.MISSING) {
                onStatusChanged(LocationProviderStatus.PermissionMissing)
                return
            }

            val provider = chooseProvider(permission)
            if (provider == null) {
                onStatusChanged(LocationProviderStatus.ProviderDisabled)
                return
            }

            onStatusChanged(LocationProviderStatus.ProviderAvailable(provider))
            onStatusChanged(LocationProviderStatus.WaitingForSignal)

            val thread = HandlerThread("LinienbegleiterLocation").apply { start() }
            locationThread = thread
            val handler = Handler(thread.looper)

            val newListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val sample = location.toSample(config.staleAfterMillis)
                    latest = sample
                    onSample(sample)
                }

                override fun onProviderEnabled(provider: String) {
                    onStatusChanged(LocationProviderStatus.ProviderAvailable(provider))
                }

                override fun onProviderDisabled(provider: String) {
                    onStatusChanged(LocationProviderStatus.ProviderDisabled)
                }
            }
            listener = newListener

            try {
                locationManager.requestLocationUpdates(
                    provider,
                    config.intervalMillis,
                    config.minimumDistanceMeters,
                    newListener,
                    handler.looper
                )
            } catch (_: SecurityException) {
                listener = null
                locationThread = null
                thread.quitSafely()
                onStatusChanged(LocationProviderStatus.PermissionMissing)
            } catch (exception: IllegalArgumentException) {
                listener = null
                locationThread = null
                thread.quitSafely()
                onStatusChanged(
                    LocationProviderStatus.Error(
                        exception.message ?: "Источник геолокации недоступен."
                    )
                )
            }
        }
    }

    override fun stop() {
        synchronized(this) {
            val currentListener = listener
            listener = null
            if (currentListener != null) {
                try {
                    locationManager.removeUpdates(currentListener)
                } catch (_: SecurityException) {
                    // Permission may have been revoked while recording.
                }
            }

            locationThread?.quitSafely()
            locationThread = null
        }
    }

    override suspend fun getCurrentLocation(timeoutMillis: Long): LocationSample? {
        require(timeoutMillis > 0L)
        getLastKnownLocation(maxAgeMillis = minOf(timeoutMillis, 5_000L))?.let { return it }
        if (listener != null) return latestSample(timeoutMillis)
        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                start(
                    config = LocationRequestConfig(
                        intervalMillis = 1_000L,
                        minimumDistanceMeters = 0f,
                        staleAfterMillis = timeoutMillis
                    ),
                    onSample = { sample ->
                        if (continuation.isActive) {
                            continuation.resume(sample)
                            stop()
                        }
                    },
                    onStatusChanged = { status ->
                        if (status is LocationProviderStatus.PermissionMissing ||
                            status is LocationProviderStatus.ProviderDisabled ||
                            status is LocationProviderStatus.Error
                        ) {
                            if (continuation.isActive) continuation.resume(null)
                            stop()
                        }
                    }
                )
                continuation.invokeOnCancellation { stop() }
            }
        }
    }

    override fun getLastKnownLocation(maxAgeMillis: Long): LocationSample? {
        require(maxAgeMillis > 0L)
        if (getPermissionState() == LocationPermissionState.MISSING) return null
        val now = System.currentTimeMillis()
        return try {
            locationManager.getProviders(true)
                .mapNotNull { provider ->
                    runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
                }
                .filter { now - it.time in 0L..maxAgeMillis }
                .maxByOrNull(Location::getTime)
                ?.toSample(maxAgeMillis)
        } catch (_: SecurityException) {
            null
        }
    }

    override fun getPermissionState(): LocationPermissionState =
        when (foregroundLocationPermission()) {
            ForegroundLocationPermission.FINE -> LocationPermissionState.FINE
            ForegroundLocationPermission.APPROXIMATE -> LocationPermissionState.APPROXIMATE
            ForegroundLocationPermission.MISSING -> LocationPermissionState.MISSING
        }

    override fun isLocationAvailable(): Boolean = try {
        locationManager.getProviders(true).isNotEmpty()
    } catch (_: SecurityException) {
        false
    }

    override fun latestSample(maxAgeMillis: Long): LocationSample? {
        require(maxAgeMillis > 0)
        val sample = latest ?: return null
        return sample.takeIf {
            !it.isStale &&
                it.isSuitableForStop(
                    nowEpochMillis = System.currentTimeMillis(),
                    maxAgeMillis = maxAgeMillis,
                    maxAccuracyMeters = activeConfig.stopMaximumAccuracyMeters
                )
        }
    }

    private fun chooseProvider(
        permission: ForegroundLocationPermission
    ): String? {
        val preferredProviders = if (permission == ForegroundLocationPermission.FINE) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        }
        return try {
            preferredProviders.firstOrNull { provider ->
                provider in locationManager.allProviders &&
                    locationManager.isProviderEnabled(provider)
            }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun foregroundLocationPermission(): ForegroundLocationPermission {
        val fineGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fineGranted) return ForegroundLocationPermission.FINE

        val coarseGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return if (coarseGranted) {
            ForegroundLocationPermission.APPROXIMATE
        } else {
            ForegroundLocationPermission.MISSING
        }
    }

    private fun Location.toSample(staleAfterMillis: Long): LocationSample {
        val now = System.currentTimeMillis()
        val ageMillis = now - time
        val elapsedNanos = elapsedRealtimeNanos.takeIf { it > 0L }
        return LocationSample(
            latitude = latitude,
            longitude = longitude,
            timestampEpochMillis = time,
            accuracyMeters = if (hasAccuracy()) accuracy else null,
            speedMetersPerSecond = if (hasSpeed()) speed else null,
            bearingDegrees = if (hasBearing()) bearing else null,
            elapsedRealtimeNanos = elapsedNanos,
            sourceProvider = provider ?: "unknown",
            ageMillisAtReceipt = ageMillis,
            isStale = ageMillis > staleAfterMillis || ageMillis < -5_000L
        )
    }
}

enum class ForegroundLocationPermission {
    FINE,
    APPROXIMATE,
    MISSING
}
