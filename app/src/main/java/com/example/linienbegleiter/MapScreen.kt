package com.example.linienbegleiter

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.PointF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.view.MotionEvent
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.style.layers.Property
import kotlin.math.abs
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.example.linienbegleiter.data.local.RecordingDatabase
import com.example.linienbegleiter.data.recording.RecordingStatus
import com.example.linienbegleiter.data.recording.RoomRecordingRepository
import com.example.linienbegleiter.data.routes.RouteFollowSource
import com.example.linienbegleiter.data.routes.RoomEditableRouteRepository
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.linienbegleiter.data.recording.RecordingPoint
import com.example.linienbegleiter.data.recording.RecordingStop
import com.example.linienbegleiter.data.location.AndroidLocationProvider
import com.example.linienbegleiter.data.location.LocationProvider
import com.example.linienbegleiter.data.location.LocationProviderStatus
import com.example.linienbegleiter.data.location.LocationRequestConfig
import com.example.linienbegleiter.data.location.LocationSample
import com.example.linienbegleiter.data.routes.RouteColorPalette
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.utils.BitmapUtils

private data class ProjectedMapStop(val item: MapStopOverlayItem, val x: Float, val y: Float)
private data class ProjectedMapCoordinate(val x: Float, val y: Float)

data class MapRoutePoint(
    val sequence: Int,
    val latitude: Double,
    val longitude: Double,
    val timestampEpochMillis: Long? = null
)

data class MapRouteSegmentHit(
    val sequence: Int,
    val fraction: Double,
    val position: LatLng,
)

data class MapRouteStop(
    val sequence: Int,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val id: Long? = null,
    val isStart: Boolean = false,
    val isEnd: Boolean = false,
    val isSelected: Boolean = false,
    val isNext: Boolean = false
)

internal data class MapStopOverlayItem(
    val sequence: Int,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val isSelected: Boolean,
    val isNext: Boolean
)

internal fun buildMapStopOverlayItems(
    recordingStops: List<RecordingStop>,
    routeStops: List<MapRouteStop>
): List<MapStopOverlayItem> = buildList {
    recordingStops.forEach { stop ->
        add(MapStopOverlayItem(stop.sequence, stop.name, stop.latitude, stop.longitude, false, false))
    }
    routeStops.forEach { stop ->
        add(MapStopOverlayItem(stop.sequence, stop.name, stop.latitude, stop.longitude, stop.isSelected, stop.isNext))
    }
}.filter { stop ->
    stop.latitude.isFinite() && stop.longitude.isFinite() &&
        stop.latitude in -90.0..90.0 && stop.longitude in -180.0..180.0
}

const val OPENFREEMAP_LIBERTY_STYLE =
    "https://tiles.openfreemap.org/styles/liberty"
const val OPENFREEMAP_DARK_STYLE =
    "https://tiles.openfreemap.org/styles/dark"

private val GERMANY_OVERVIEW = CameraPosition.Builder()
    .target(LatLng(51.1657, 10.4515))
    .zoom(5.5)
    .build()

private val HAMBURG_OVERVIEW = CameraPosition.Builder()
    .target(LatLng(53.5511, 9.9937))
    .zoom(11.0)
    .build()

@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    initialCameraPosition: CameraPosition?,
    onCameraPositionChanged: (CameraPosition) -> Unit,
    onBack: () -> Unit,
    mapStyleUrl: String = OPENFREEMAP_LIBERTY_STYLE,
    onOpenRoutes: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onRecordRoute: () -> Unit = {},
    trackPoints: List<RecordingPoint> = emptyList(),
    recordingStops: List<RecordingStop> = emptyList(),
    routePoints: List<MapRoutePoint> = emptyList(),
    routeStops: List<MapRouteStop> = emptyList(),
    currentPosition: RecordingPoint? = null,
    onMapTap: ((LatLng) -> Unit)? = null,
    showRoutePointMarkers: Boolean = true,
    selectedRoutePointSequence: Int? = null,
    onRoutePointTap: ((Int) -> Unit)? = null,
    onRouteSegmentTap: ((MapRouteSegmentHit) -> Unit)? = null,
    onStopTap: ((MapRouteStop) -> Unit)? = null,
    selectedRouteSectionCenter: LatLng? = null,
    selectedRouteSectionPoints: List<MapRoutePoint> = emptyList(),
    previewRouteSectionPoints: List<MapRoutePoint> = emptyList(),
    onRouteSectionDrag: ((LatLng) -> Unit)? = null,
    onRouteSectionDragEnd: (() -> Unit)? = null,
    onRouteSectionDragCancel: (() -> Unit)? = null,
    followCurrentPosition: Boolean = false,
    recenterToken: Int = 0,
    onUserMapGesture: (() -> Unit)? = null,
    showChrome: Boolean = true,
    requestLocationOnOpen: Boolean = showChrome,
    routeColorArgb: Int? = null,
    routeLineWidth: Float = 5f,
    originalComparisonPoints: List<MapRoutePoint> = emptyList(),
    passedRoutePoints: List<MapRoutePoint> = emptyList(),
    directionDrawingEnabled: Boolean = false,
    directionPoints: List<MapRoutePoint> = emptyList(),
    onDirectionDrawn: (List<MapRoutePoint>) -> Unit = {},
    onFollowRoute: ((String, RouteFollowSource) -> Unit)? = null
) {
    val context = LocalContext.current
    val database = remember(context) { RecordingDatabase.getInstance(context) }
    val editableRouteRepository = remember(database) { RoomEditableRouteRepository(database) }
    val recordingRepository = remember(database) { RoomRecordingRepository(database) }
    val editableRoutes by remember(editableRouteRepository) {
        editableRouteRepository.observeRoutes()
    }.collectAsState(initial = emptyList())
    val sessions by remember(recordingRepository) {
        recordingRepository.observeSessions()
    }.collectAsState(initial = emptyList())
    var showRouteDropdownDialog by remember { mutableStateOf(false) }

    var deviceCompassAzimuth by remember { mutableFloatStateOf(0f) }
    val sensorManager = remember(context) {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }

    DisposableEffect(sensorManager) {
        if (sensorManager == null) return@DisposableEffect onDispose {}
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION)
        if (rotationSensor == null) return@DisposableEffect onDispose {}

        val listener = object : SensorEventListener {
            private val rotationMatrix = FloatArray(9)
            private val orientation = FloatArray(3)
            private var lastAzimuth = 0f

            override fun onSensorChanged(event: SensorEvent) {
                val azimuth = if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    Math.toDegrees(orientation[0].toDouble()).toFloat()
                } else {
                    event.values[0]
                }
                val normalized = (azimuth + 360f) % 360f
                val diff = ((normalized - lastAzimuth + 540f) % 360f) - 180f
                if (abs(diff) > 1.2f) {
                    val smoothed = (lastAzimuth + diff * 0.3f + 360f) % 360f
                    lastAzimuth = smoothed
                    deviceCompassAzimuth = smoothed
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(
            listener,
            rotationSensor,
            SensorManager.SENSOR_DELAY_UI
        )
        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    val routeHitRadiusPixels = with(LocalDensity.current) { 48.dp.toPx() }
    val lifecycle = context.findComponentActivity().lifecycle
    val locationProvider: LocationProvider = remember(context) {
        AndroidLocationProvider(context)
    }
    var initialUserPosition by remember { mutableStateOf<RecordingPoint?>(null) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    var showLocationPermissionExplanation by remember { mutableStateOf(false) }
    var hasUserMovedMap by remember { mutableStateOf(false) }
    var shouldCenterAfterLocation by remember { mutableStateOf(false) }
    var userLocationRecenterToken by remember { mutableIntStateOf(0) }
    var locationRequestToken by remember { mutableIntStateOf(0) }
    var lifecycleForegroundToken by remember { mutableIntStateOf(0) }
    val latestShouldCenterAfterLocation = rememberUpdatedState(shouldCenterAfterLocation)
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleLoaded by remember { mutableStateOf(false) }
    var routeSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    var originalComparisonSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    var passedRouteSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    var directionSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    var routePointsSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    var selectedRoutePointSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    var selectedRouteSectionSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    var previewRouteSectionSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    var currentPositionSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    var hasCenteredOnCurrentPosition by remember { mutableStateOf(false) }
    var smoothedCameraBearing by remember { mutableStateOf(0f) }
    var hasStableCameraBearing by remember { mutableStateOf(false) }
    var selectedMapStop by remember { mutableStateOf<MapRouteStop?>(null) }
    var projectedStopMarkers by remember { mutableStateOf(emptyList<ProjectedMapStop>()) }
    var projectedRouteSectionHandle by remember { mutableStateOf<ProjectedMapCoordinate?>(null) }
    val latestMapStopItems = rememberUpdatedState(buildMapStopOverlayItems(recordingStops, routeStops))
    val latestRouteSectionCenter = rememberUpdatedState(selectedRouteSectionCenter)
    val latestOnMapTap = rememberUpdatedState(onMapTap)
    val latestOnRoutePointTap = rememberUpdatedState(onRoutePointTap)
    val latestOnRouteSegmentTap = rememberUpdatedState(onRouteSegmentTap)
    val latestOnRouteSectionDrag = rememberUpdatedState(onRouteSectionDrag)
    val latestOnRouteSectionDragEnd = rememberUpdatedState(onRouteSectionDragEnd)
    val latestOnRouteSectionDragCancel = rememberUpdatedState(onRouteSectionDragCancel)
    val latestRoutePoints = rememberUpdatedState(routePoints)
    val latestRouteStops = rememberUpdatedState(routeStops)
    val latestRecordingStops = rememberUpdatedState(recordingStops)
    val latestOnStopTap = rememberUpdatedState(onStopTap)
    val latestDirectionDrawingEnabled = rememberUpdatedState(directionDrawingEnabled)
    val latestOnDirectionDrawn = rememberUpdatedState(onDirectionDrawn)
    val inProgressDirection = remember { mutableListOf<MapRoutePoint>() }
    val latestOnUserMapGesture = rememberUpdatedState(onUserMapGesture)

    val routeArrowBitmap = remember(context) {
        BitmapUtils.getBitmapFromDrawable(
            ContextCompat.getDrawable(context.applicationContext, R.drawable.ic_route_direction)
        )
    }
    val gpsArrowBitmap = remember(context) {
        BitmapUtils.getBitmapFromDrawable(
            ContextCompat.getDrawable(context.applicationContext, R.drawable.ic_gps_bus)
        )
    }

    fun requestOneShotLocation() {
        if (!hasMapLocationPermission(context)) {
            showLocationPermissionExplanation = true
            return
        }
        locationProvider.getLastKnownLocation(MAX_CACHED_LOCATION_AGE_MILLIS)
            ?.let { initialUserPosition = it.toRecordingPoint() }
        locationRequestToken += 1
        locationMessage = "Определяем ваше положение…"
        locationProvider.start(
            config = LocationRequestConfig(
                intervalMillis = MAP_LOCATION_UPDATE_INTERVAL_MILLIS,
                minimumDistanceMeters = MAP_LOCATION_MINIMUM_DISTANCE_METERS,
                staleAfterMillis = MAP_LOCATION_STALE_AFTER_MILLIS
            ),
            onSample = { sample ->
                initialUserPosition = sample.toRecordingPoint()
                locationMessage = null
                if (latestShouldCenterAfterLocation.value) {
                    userLocationRecenterToken += 1
                    shouldCenterAfterLocation = false
                }
            },
            onStatusChanged = { status ->
                locationMessage = when (status) {
                    LocationProviderStatus.WaitingForSignal -> "Определяем ваше положение…"
                    LocationProviderStatus.PermissionMissing -> "Разрешение на геопозицию не предоставлено."
                    LocationProviderStatus.ProviderDisabled -> "Включите службы определения местоположения."
                    is LocationProviderStatus.ProviderAvailable -> null
                    is LocationProviderStatus.Error -> status.message
                }
            }
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        showLocationPermissionExplanation = false
        if (hasMapLocationPermission(context)) {
            requestOneShotLocation()
        } else {
            locationMessage = if (
                results[Manifest.permission.ACCESS_FINE_LOCATION] == false ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == false
            ) {
                "Доступ к местоположению не разрешён; карта останется на обзорной области."
            } else {
                "Чтобы показать ваше положение, разрешите геолокацию."
            }
        }
    }

    LaunchedEffect(requestLocationOnOpen, lifecycleForegroundToken) {
        if (requestLocationOnOpen && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (hasMapLocationPermission(context)) {
                requestOneShotLocation()
            } else {
                showLocationPermissionExplanation = true
            }
        }
    }

    LaunchedEffect(locationRequestToken) {
        if (locationRequestToken > 0) {
            delay(20_000L)
            val fixTime = initialUserPosition?.timestampEpochMillis
            if (fixTime == null || System.currentTimeMillis() - fixTime > 20_000L) {
                locationMessage = "Положение пока не получено. Проверьте GPS и попробуйте ещё раз."
            }
        }
    }

    DisposableEffect(lifecycle, locationProvider) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> lifecycleForegroundToken += 1
                Lifecycle.Event.ON_STOP -> locationProvider.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleForegroundToken += 1
        }
        onDispose {
            lifecycle.removeObserver(observer)
            locationProvider.stop()
        }
    }

    val displayedPosition = currentPosition ?: initialUserPosition

    fun updateProjectedStopMarkers(map: MapLibreMap) {
        projectedStopMarkers = latestMapStopItems.value.map { item ->
            val pixel = map.projection.toScreenLocation(LatLng(item.latitude, item.longitude))
            ProjectedMapStop(item, pixel.x, pixel.y)
        }
        projectedRouteSectionHandle = latestRouteSectionCenter.value?.let { center ->
            val pixel = map.projection.toScreenLocation(center)
            ProjectedMapCoordinate(pixel.x, pixel.y)
        }
    }

    LaunchedEffect(styleLoaded, latestMapStopItems.value, selectedRouteSectionCenter) {
        mapLibreMap?.let(::updateProjectedStopMarkers)
    }

    val mapView = remember(context) {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply {
            onCreate(null)
        }
    }

    DisposableEffect(mapView, lifecycle) {
        var isStarted = false
        var isResumed = false
        var isDestroyed = false

        fun startMap() {
            if (!isStarted && !isDestroyed) {
                mapView.onStart()
                isStarted = true
            }
        }

        fun resumeMap() {
            startMap()
            if (!isResumed && !isDestroyed) {
                mapView.onResume()
                isResumed = true
            }
        }

        fun pauseMap() {
            if (isResumed && !isDestroyed) {
                mapView.onPause()
                isResumed = false
            }
        }

        fun stopMap() {
            pauseMap()
            if (isStarted && !isDestroyed) {
                mapView.onStop()
                isStarted = false
            }
        }

        fun destroyMap() {
            if (!isDestroyed) {
                stopMap()
                mapView.onDestroy()
                isDestroyed = true
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> startMap()
                Lifecycle.Event.ON_RESUME -> resumeMap()
                Lifecycle.Event.ON_PAUSE -> pauseMap()
                Lifecycle.Event.ON_STOP -> stopMap()
                Lifecycle.Event.ON_DESTROY -> destroyMap()
                else -> Unit
            }
        }

        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            resumeMap()
        } else if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            startMap()
        }

        onDispose {
            lifecycle.removeObserver(observer)
            destroyMap()
        }
    }

    LaunchedEffect(
        styleLoaded,
        trackPoints,
        recordingStops,
        routePoints,
        routeStops,
        originalComparisonPoints,
        passedRoutePoints,
        directionPoints,
        displayedPosition,
        selectedRoutePointSequence,
        showRoutePointMarkers
    ) {
        if (styleLoaded) {
            val routeJson = if (routePoints.isEmpty()) {
                buildTrackGeoJson(trackPoints)
            } else {
                buildRouteLineGeoJson(routePoints)
            }
            routeSource?.setGeoJson(routeJson)
            originalComparisonSource?.setGeoJson(
                buildRouteLineGeoJson(originalComparisonPoints)
            )
            passedRouteSource?.setGeoJson(buildRouteLineGeoJson(passedRoutePoints))
            directionSource?.setGeoJson(buildRouteLineGeoJson(directionPoints))
            routePointsSource?.setGeoJson(
                if (showRoutePointMarkers) buildRoutePointsGeoJson(routePoints)
                else emptyFeatureCollection()
            )
            selectedRoutePointSource?.setGeoJson(
                buildSelectedRoutePointGeoJson(routePoints, selectedRoutePointSequence)
            )
        }
    }

    val isMoving = (displayedPosition?.speedMetersPerSecond ?: 0f) > 0.8f
    val currentBearing = if (isMoving && displayedPosition?.bearingDegrees != null && displayedPosition.bearingDegrees > 0f) {
        displayedPosition.bearingDegrees
    } else {
        deviceCompassAzimuth
    }

    LaunchedEffect(styleLoaded, displayedPosition, currentBearing) {
        if (styleLoaded) {
            currentPositionSource?.setGeoJson(buildCurrentPositionGeoJson(displayedPosition, currentBearing))
        }
    }

    LaunchedEffect(styleLoaded, selectedRouteSectionPoints, previewRouteSectionPoints) {
        if (styleLoaded) {
            selectedRouteSectionSource?.setGeoJson(
                buildRouteLineGeoJson(selectedRouteSectionPoints)
            )
            previewRouteSectionSource?.setGeoJson(
                buildRouteLineGeoJson(previewRouteSectionPoints)
            )
        }
    }

    LaunchedEffect(styleLoaded, routeColorArgb, routeLineWidth) {
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        val color = RouteColorPalette.toMapLibreColor(
            routeColorArgb ?: DEFAULT_ROUTE_COLOR_ARGB
        )
        style.getLayer(ROUTE_OUTLINE_LAYER_ID)?.setProperties(
            PropertyFactory.lineWidth(routeLineWidth + ROUTE_OUTLINE_EXTRA_WIDTH)
        )
        style.getLayer(ROUTE_LAYER_ID)?.setProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineWidth(routeLineWidth)
        )
        style.getLayer(ROUTE_POINTS_LAYER_ID)?.setProperties(
            PropertyFactory.circleColor(color)
        )
        style.getLayer(SELECTED_ROUTE_SECTION_LAYER_ID)?.setProperties(
            PropertyFactory.lineWidth(routeLineWidth + 7f)
        )
        style.getLayer(PREVIEW_ROUTE_SECTION_LAYER_ID)?.setProperties(
            PropertyFactory.lineWidth(routeLineWidth + 2f)
        )
    }

    LaunchedEffect(
        styleLoaded,
        displayedPosition?.timestampEpochMillis,
        displayedPosition?.bearingDegrees,
        displayedPosition?.speedMetersPerSecond,
        displayedPosition?.accuracyMeters,
        followCurrentPosition
    ) {
        val position = displayedPosition
        if (styleLoaded && position != null) {
            val target = LatLng(position.latitude, position.longitude)
            if (followCurrentPosition && !hasUserMovedMap) {
                val speed = position.speedMetersPerSecond
                val accuracy = position.accuracyMeters
                val measuredBearing = position.bearingDegrees
                val bearingIsReliable = measuredBearing != null &&
                    (speed == null || speed >= MIN_CAMERA_HEADING_SPEED_METERS_PER_SECOND) &&
                    (accuracy == null || accuracy <= MAX_CAMERA_HEADING_ACCURACY_METERS)
                if (bearingIsReliable) {
                    smoothedCameraBearing = if (hasStableCameraBearing) {
                        smoothCameraBearing(smoothedCameraBearing, measuredBearing!!)
                    } else {
                        normalizeBearing(measuredBearing!!)
                    }
                    hasStableCameraBearing = true
                }
                val camera = mapLibreMap?.cameraPosition
                val zoom = if (!hasCenteredOnCurrentPosition || camera == null) {
                    NAVIGATION_ZOOM
                } else {
                    camera.zoom.coerceIn(MIN_NAVIGATION_ZOOM, MAX_NAVIGATION_ZOOM)
                }
                val bearing = if (hasStableCameraBearing) {
                    smoothedCameraBearing
                } else {
                    camera?.bearing ?: 0.0
                }
                val nextCamera = CameraPosition.Builder()
                    .target(target)
                    .zoom(zoom)
                    .bearing(bearing.toDouble())
                    .tilt(camera?.tilt ?: 0.0)
                    .build()
                mapLibreMap?.animateCamera(
                    CameraUpdateFactory.newCameraPosition(nextCamera),
                    CAMERA_FOLLOW_ANIMATION_MILLIS
                )
                hasCenteredOnCurrentPosition = true
            } else if (!hasCenteredOnCurrentPosition && !hasUserMovedMap) {
                mapLibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 15.5))
                hasCenteredOnCurrentPosition = true
            }
        }
    }

    LaunchedEffect(styleLoaded, routePoints) {
        if (styleLoaded && routePoints.size >= 2 && !hasCenteredOnCurrentPosition) {
            val latLngs = routePoints.map { LatLng(it.latitude, it.longitude) }
            runCatching {
                val bounds = LatLngBounds.fromLatLngs(latLngs)
                mapLibreMap?.animateCamera(
                    CameraUpdateFactory.newLatLngBounds(bounds, 100),
                    700
                )
                hasCenteredOnCurrentPosition = true
            }
        }
    }

    LaunchedEffect(styleLoaded, displayedPosition) {
        if (styleLoaded && displayedPosition != null && !hasCenteredOnCurrentPosition && !hasUserMovedMap) {
            mapLibreMap?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(displayedPosition.latitude, displayedPosition.longitude),
                    15.5
                )
            )
            hasCenteredOnCurrentPosition = true
        }
    }

    LaunchedEffect(styleLoaded, recenterToken) {
        val position = displayedPosition
        if (styleLoaded && recenterToken > 0 && position != null) {
            hasUserMovedMap = false
            val cameraBearing = if (hasStableCameraBearing) {
                smoothedCameraBearing.toDouble()
            } else {
                mapLibreMap?.cameraPosition?.bearing ?: 0.0
            }
            val camera = CameraPosition.Builder()
                .target(LatLng(position.latitude, position.longitude))
                .zoom(NAVIGATION_ZOOM)
                .bearing(cameraBearing)
                .build()
            mapLibreMap?.animateCamera(
                CameraUpdateFactory.newCameraPosition(camera),
                CAMERA_FOLLOW_ANIMATION_MILLIS
            )
            hasCenteredOnCurrentPosition = true
        }
    }

    LaunchedEffect(styleLoaded, userLocationRecenterToken) {
        val position = displayedPosition
        if (styleLoaded && userLocationRecenterToken > 0 && position != null) {
            mapLibreMap?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(position.latitude, position.longitude),
                    15.5
                )
            )
            hasCenteredOnCurrentPosition = true
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        locationMessage?.let { message ->
            Text(
                message,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AndroidView(
                factory = {
                    mapView.apply {
                        getMapAsync { map ->
                            mapLibreMap = map
                            mapView.setOnTouchListener { _, event ->
                                if (!latestDirectionDrawingEnabled.value) return@setOnTouchListener false
                                when (event.actionMasked) {
                                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                                        val coordinate = map.projection.fromScreenLocation(
                                            PointF(event.x, event.y)
                                        )
                                        val current = inProgressDirection
                                        if (current.isEmpty() ||
                                            current.last().latitude != coordinate.latitude ||
                                            current.last().longitude != coordinate.longitude
                                        ) current += MapRoutePoint(
                                            current.size, coordinate.latitude, coordinate.longitude
                                        )
                                        directionSource?.setGeoJson(buildRouteLineGeoJson(current))
                                    }
                                    MotionEvent.ACTION_UP -> {
                                        latestOnDirectionDrawn.value(inProgressDirection.toList())
                                        inProgressDirection.clear()
                                    }
                                    MotionEvent.ACTION_CANCEL -> inProgressDirection.clear()
                                }
                                true
                            }
                            map.uiSettings.isScrollGesturesEnabled = true
                            map.uiSettings.isZoomGesturesEnabled = true
                            map.uiSettings.isDoubleTapGesturesEnabled = true
                            map.addOnCameraMoveListener {
                                updateProjectedStopMarkers(map)
                            }
                            map.addOnCameraIdleListener {
                                updateProjectedStopMarkers(map)
                                onCameraPositionChanged(map.cameraPosition)
                                context.saveMapCameraPosition(map.cameraPosition)
                            }
                            map.addOnCameraMoveStartedListener { reason ->
                                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                                    hasUserMovedMap = true
                                    latestOnUserMapGesture.value?.invoke()
                                }
                            }
                            map.addOnMapClickListener { point ->
                                val tapPixel = map.projection.toScreenLocation(point)
                                val tappableStops = latestRouteStops.value + latestRecordingStops.value.map { stop ->
                                    MapRouteStop(stop.sequence, stop.name, stop.latitude, stop.longitude)
                                }
                                val nearestStop = tappableStops
                                    .map { stop ->
                                        stop to distancePixels(
                                            tapPixel,
                                            map.projection.toScreenLocation(LatLng(stop.latitude, stop.longitude))
                                        )
                                    }
                                    .minByOrNull { it.second }
                                if (nearestStop != null &&
                                    nearestStop.second <= routeHitRadiusPixels &&
                                    latestOnRouteSegmentTap.value == null
                                ) {
                                    selectedMapStop = nearestStop.first
                                    latestOnStopTap.value?.invoke(nearestStop.first)
                                    return@addOnMapClickListener true
                                }
                                val routeSegmentsCallback = latestOnRouteSegmentTap.value
                                if (routeSegmentsCallback != null) {
                                    val tapPixel = map.projection.toScreenLocation(point)
                                    val nearestSegment = latestRoutePoints.value
                                        .zipWithNext()
                                        .mapIndexed { index, (start, end) ->
                                            val startPixel = map.projection.toScreenLocation(
                                                LatLng(start.latitude, start.longitude)
                                            )
                                            val endPixel = map.projection.toScreenLocation(
                                                LatLng(end.latitude, end.longitude)
                                            )
                                            val projected = projectOntoSegmentPixels(
                                                tapPixel,
                                                startPixel,
                                                endPixel
                                            )
                                            MapRouteSegmentHit(
                                                sequence = index,
                                                fraction = projected.fraction.toDouble(),
                                                position = map.projection.fromScreenLocation(
                                                    PointF(projected.x, projected.y)
                                                )
                                            ) to projected.distancePixels
                                        }
                                        .minByOrNull { it.second }
                                    val hitRadius = routeHitRadiusPixels
                                    if (nearestSegment != null &&
                                        nearestSegment.second <= hitRadius
                                    ) {
                                        routeSegmentsCallback(nearestSegment.first)
                                        return@addOnMapClickListener true
                                    }
                                }

                                val routePointCallback = latestOnRoutePointTap.value
                                if (routePointCallback != null) {
                                    val tapPixel = map.projection.toScreenLocation(point)
                                    val nearestPoint = latestRoutePoints.value
                                        .map { routePoint ->
                                            val pixel = map.projection.toScreenLocation(
                                                LatLng(routePoint.latitude, routePoint.longitude)
                                            )
                                            routePoint.sequence to distancePixels(tapPixel, pixel)
                                        }
                                        .minByOrNull { it.second }
                                    val hitRadius = routeHitRadiusPixels
                                    if (nearestPoint != null && nearestPoint.second <= hitRadius) {
                                        routePointCallback(nearestPoint.first)
                                        return@addOnMapClickListener true
                                    }
                                }

                                latestOnMapTap.value?.invoke(point)
                                latestOnMapTap.value != null
                            }
                            map.setStyle(mapStyleUrl) { style ->
                                val newRouteSource = GeoJsonSource(
                                    ROUTE_SOURCE_ID,
                                    emptyFeatureCollection()
                                )
                                val newOriginalComparisonSource = GeoJsonSource(
                                    ORIGINAL_COMPARISON_SOURCE_ID,
                                    emptyFeatureCollection()
                                )
                                val newPassedRouteSource = GeoJsonSource(
                                    PASSED_ROUTE_SOURCE_ID,
                                    emptyFeatureCollection()
                                )
                                val newDirectionSource = GeoJsonSource(
                                    DIRECTION_SOURCE_ID, emptyFeatureCollection()
                                )
                                val newRoutePointsSource = GeoJsonSource(
                                    ROUTE_POINTS_SOURCE_ID,
                                    emptyFeatureCollection()
                                )
                                val newSelectedRoutePointSource = GeoJsonSource(
                                    SELECTED_ROUTE_POINT_SOURCE_ID,
                                    emptyFeatureCollection()
                                )
                                val newSelectedRouteSectionSource = GeoJsonSource(
                                    SELECTED_ROUTE_SECTION_SOURCE_ID,
                                    emptyFeatureCollection()
                                )
                                val newPreviewRouteSectionSource = GeoJsonSource(
                                    PREVIEW_ROUTE_SECTION_SOURCE_ID,
                                    emptyFeatureCollection()
                                )
                                val newCurrentPositionSource = GeoJsonSource(
                                    CURRENT_POSITION_SOURCE_ID,
                                    emptyFeatureCollection()
                                )
                                style.addSource(newRouteSource)
                                style.addSource(newOriginalComparisonSource)
                                style.addSource(newPassedRouteSource)
                                style.addSource(newDirectionSource)
                                style.addSource(newRoutePointsSource)
                                style.addSource(newSelectedRoutePointSource)
                                style.addSource(newSelectedRouteSectionSource)
                                style.addSource(newPreviewRouteSectionSource)
                                style.addSource(newCurrentPositionSource)
                                if (routeArrowBitmap != null) {
                                    style.addImage(ROUTE_ARROW_ICON_ID, routeArrowBitmap)
                                }
                                if (gpsArrowBitmap != null) {
                                    style.addImage(GPS_ARROW_ICON_ID, gpsArrowBitmap)
                                }
                                style.addLayer(
                                    LineLayer(
                                        ORIGINAL_COMPARISON_LAYER_ID,
                                        ORIGINAL_COMPARISON_SOURCE_ID
                                    ).withProperties(
                                        PropertyFactory.lineColor("#66717A"),
                                        PropertyFactory.lineWidth(4f),
                                        PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
                                        PropertyFactory.lineCap("round"),
                                        PropertyFactory.lineJoin("round")
                                    )
                                )
                                style.addLayer(
                                    LineLayer(ROUTE_OUTLINE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                                        PropertyFactory.lineColor("#FFFFFF"),
                                        PropertyFactory.lineWidth(ROUTE_DEFAULT_WIDTH + ROUTE_OUTLINE_EXTRA_WIDTH),
                                        PropertyFactory.lineOpacity(0.9f),
                                        PropertyFactory.lineCap("round"),
                                        PropertyFactory.lineJoin("round")
                                    )
                                )
                                style.addLayer(
                                    LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                                        PropertyFactory.lineColor(
                                            RouteColorPalette.toMapLibreColor(
                                                routeColorArgb ?: DEFAULT_ROUTE_COLOR_ARGB
                                            )
                                        ),
                                        PropertyFactory.lineWidth(routeLineWidth),
                                        PropertyFactory.lineCap("round"),
                                        PropertyFactory.lineJoin("round")
                                    )
                                )
                                style.addLayer(
                                    LineLayer(PASSED_ROUTE_LAYER_ID, PASSED_ROUTE_SOURCE_ID)
                                        .withProperties(
                                            PropertyFactory.lineColor("#6B7280"),
                                            PropertyFactory.lineWidth(ROUTE_DEFAULT_WIDTH),
                                            PropertyFactory.lineOpacity(0.7f),
                                            PropertyFactory.lineCap("round"),
                                            PropertyFactory.lineJoin("round")
                                        )
                                )
                                style.addLayer(
                                    SymbolLayer(ROUTE_DIRECTION_LAYER_ID, ROUTE_SOURCE_ID)
                                        .withProperties(
                                            PropertyFactory.symbolPlacement(Expression.literal("line")),
                                            PropertyFactory.symbolSpacing(140f),
                                            PropertyFactory.iconImage(ROUTE_ARROW_ICON_ID),
                                            PropertyFactory.iconSize(0.68f),
                                            PropertyFactory.iconRotationAlignment(Expression.literal("map")),
                                            PropertyFactory.iconKeepUpright(false),
                                            PropertyFactory.iconAllowOverlap(true),
                                            PropertyFactory.iconIgnorePlacement(true)
                                        )
                                )
                                style.addLayer(
                                    LineLayer(
                                        SELECTED_ROUTE_SECTION_LAYER_ID,
                                        SELECTED_ROUTE_SECTION_SOURCE_ID
                                    ).withProperties(
                                        PropertyFactory.lineColor("#F3A847"),
                                        PropertyFactory.lineWidth(routeLineWidth + 7f),
                                        PropertyFactory.lineOpacity(0.88f),
                                        PropertyFactory.lineCap("round"),
                                        PropertyFactory.lineJoin("round")
                                    )
                                )
                                style.addLayer(
                                    LineLayer(
                                        PREVIEW_ROUTE_SECTION_LAYER_ID,
                                        PREVIEW_ROUTE_SECTION_SOURCE_ID
                                    ).withProperties(
                                        PropertyFactory.lineColor("#C8102E"),
                                        PropertyFactory.lineWidth(routeLineWidth + 2f),
                                        PropertyFactory.lineOpacity(0.96f),
                                        PropertyFactory.lineCap("round"),
                                        PropertyFactory.lineJoin("round")
                                    )
                                )
                                style.addLayer(
                                    CircleLayer(
                                        ROUTE_POINTS_LAYER_ID,
                                        ROUTE_POINTS_SOURCE_ID
                                    ).withProperties(
                                        PropertyFactory.circleRadius(5f),
                                        PropertyFactory.circleColor("#176B87"),
                                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                                        PropertyFactory.circleStrokeWidth(2f)
                                    )
                                )
                                style.addLayer(
                                    SymbolLayer(ROUTE_POINT_LABELS_LAYER_ID, ROUTE_POINTS_SOURCE_ID)
                                        .withProperties(
                                            PropertyFactory.textField("{sequence}"),
                                            PropertyFactory.textSize(10f),
                                            PropertyFactory.textColor("#FFFFFF"),
                                            PropertyFactory.textAnchor("center"),
                                            PropertyFactory.textAllowOverlap(true),
                                            PropertyFactory.textIgnorePlacement(true)
                                        )
                                )
                                style.addLayer(
                                    CircleLayer(
                                        SELECTED_ROUTE_POINT_LAYER_ID,
                                        SELECTED_ROUTE_POINT_SOURCE_ID
                                    ).withProperties(
                                        PropertyFactory.circleRadius(10f),
                                        PropertyFactory.circleColor("#F3A847"),
                                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                                        PropertyFactory.circleStrokeWidth(3f)
                                    )
                                )

                                style.addLayer(
                                    SymbolLayer(CURRENT_POSITION_LAYER_ID, CURRENT_POSITION_SOURCE_ID)
                                        .withProperties(
                                            PropertyFactory.iconImage(GPS_ARROW_ICON_ID),
                                            PropertyFactory.iconSize(
                                                Expression.interpolate(
                                                    Expression.linear(),
                                                    Expression.zoom(),
                                                    Expression.stop(8, 0.72),
                                                    Expression.stop(16, 1.15)
                                                )
                                            ),
                                            PropertyFactory.iconAllowOverlap(true),
                                            PropertyFactory.iconIgnorePlacement(true),
                                            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                                            PropertyFactory.iconRotate(Expression.get("bearing"))
                                        )
                                )
                                style.addLayer(LineLayer(DIRECTION_LAYER_ID, DIRECTION_SOURCE_ID).withProperties(
                                    PropertyFactory.lineColor("#7B2CBF"),
                                    PropertyFactory.lineWidth(5f),
                                    PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f)),
                                    PropertyFactory.lineCap("round"),
                                    PropertyFactory.lineJoin("round")
                                ))
                                style.addLayer(SymbolLayer(DIRECTION_ARROW_LAYER_ID, DIRECTION_SOURCE_ID)
                                    .withProperties(
                                        PropertyFactory.symbolPlacement(Expression.literal("line")),
                                        PropertyFactory.symbolSpacing(140f),
                                        PropertyFactory.iconImage(ROUTE_ARROW_ICON_ID),
                                        PropertyFactory.iconSize(0.68f),
                                        PropertyFactory.iconRotationAlignment(Expression.literal("map")),
                                        PropertyFactory.iconAllowOverlap(true),
                                        PropertyFactory.iconIgnorePlacement(true)
                                    ))
                                directionSource = newDirectionSource
                                routeSource = newRouteSource
                                originalComparisonSource = newOriginalComparisonSource
                                passedRouteSource = newPassedRouteSource
                                routePointsSource = newRoutePointsSource
                                selectedRoutePointSource = newSelectedRoutePointSource
                                selectedRouteSectionSource = newSelectedRouteSectionSource
                                previewRouteSectionSource = newPreviewRouteSectionSource
                                currentPositionSource = newCurrentPositionSource
                                map.cameraPosition = initialCameraPosition
                                    ?: context.readMapCameraPosition()
                                    ?: HAMBURG_OVERVIEW
                                updateProjectedStopMarkers(map)
                                styleLoaded = true
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            projectedStopMarkers.forEach { marker ->
                MapStopMarker(
                    item = marker.item,
                    modifier = Modifier.offset {
                        IntOffset(
                            marker.x.roundToInt() - 90.dp.roundToPx(),
                            marker.y.roundToInt() - 18.dp.roundToPx()
                        )
                    }
                )
            }
            val projectedHandle = projectedRouteSectionHandle
            if (selectedRouteSectionCenter != null &&
                projectedHandle != null &&
                onRouteSectionDrag != null
            ) {
                RouteSectionDragHandle(
                    screenX = projectedHandle.x,
                    screenY = projectedHandle.y,
                    onDragTo = { x, y ->
                        mapLibreMap?.projection
                            ?.fromScreenLocation(PointF(x, y))
                            ?.let { latestOnRouteSectionDrag.value?.invoke(it) }
                    },
                    onDragEnd = { latestOnRouteSectionDragEnd.value?.invoke() },
                    onDragCancel = { latestOnRouteSectionDragCancel.value?.invoke() }
                )
            }

            if (showChrome) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    // Top-left: GPS recording standalone floating icon
                    Surface(
                        onClick = onRecordRoute,
                        modifier = Modifier.align(Alignment.TopStart),
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFFD32F2F),
                        contentColor = Color.White,
                        tonalElevation = 6.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("🔴", fontSize = 14.sp)
                            Text("Запись", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    // Top-right: Red-and-white bus icon for "Поехали" + Zoom buttons
                    Row(
                        modifier = Modifier.align(Alignment.TopEnd),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = { showRouteDropdownDialog = true },
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xFFD32F2F), // Red and white bus badge
                            contentColor = Color.White,
                            tonalElevation = 6.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("🚌", fontSize = 14.sp)
                                Text("Поехали", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }

                        // Zoom out
                        Surface(
                            onClick = { mapLibreMap?.animateCamera(CameraUpdateFactory.zoomOut()) },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            tonalElevation = 6.dp,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("−", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Zoom in
                        Surface(
                            onClick = { mapLibreMap?.animateCamera(CameraUpdateFactory.zoomIn()) },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            tonalElevation = 6.dp,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRouteDropdownDialog) {
        AlertDialog(
            onDismissRequest = { showRouteDropdownDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 12.dp,
            shape = RoundedCornerShape(28.dp),
            title = { Text("Сохранённые маршруты") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (editableRoutes.isEmpty() && sessions.isEmpty()) {
                        Text("Пока нет сохранённых маршрутов или записей.", style = MaterialTheme.typography.bodyMedium)
                    }
                    editableRoutes.forEach { route ->
                        OutlinedButton(
                            onClick = {
                                showRouteDropdownDialog = false
                                onFollowRoute?.invoke(route.id, RouteFollowSource.EDITABLE_ROUTE)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                                Text(route.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Маршрут", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    sessions.filter { it.status == RecordingStatus.FINISHED }.forEach { session ->
                        OutlinedButton(
                            onClick = {
                                showRouteDropdownDialog = false
                                onFollowRoute?.invoke(session.id, RouteFollowSource.ORIGINAL_RECORDING)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                                Text(session.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("GPS-запись", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showRouteDropdownDialog = false
                    onOpenRoutes()
                }) {
                    Text("Все маршруты")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRouteDropdownDialog = false }) {
                    Text("Закрыть")
                }
            }
        )
    }

    if (showLocationPermissionExplanation && requestLocationOnOpen) {
        AlertDialog(
            onDismissRequest = { showLocationPermissionExplanation = false },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 12.dp,
            shape = RoundedCornerShape(28.dp),
            title = { Text("Местоположение на карте") },
            text = {
                Text(
                    "Пока карта открыта, Linienbegleiter обновляет ваше положение и может вернуть к нему камеру. " +
                        "При уходе с карты обновления останавливаются. Запись маршрута и слежение в фоне не запускаются."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLocationPermissionExplanation = false
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                ) { Text("Разрешить") }
            },
            dismissButton = {
                TextButton(onClick = { showLocationPermissionExplanation = false }) {
                    Text("Не сейчас")
                }
            }
        )
    }

    selectedMapStop?.let { stop ->
        AlertDialog(
            onDismissRequest = { selectedMapStop = null },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 12.dp,
            shape = RoundedCornerShape(28.dp),
            title = { Text(stop.name) },
            text = { Text("Остановка ${stop.sequence + 1}\n${"%.5f".format(stop.latitude)}, ${"%.5f".format(stop.longitude)}") },
            confirmButton = {
                TextButton(onClick = { selectedMapStop = null }) { Text("Закрыть") }
            }
        )
    }
}

@Composable
private fun RouteSectionDragHandle(
    screenX: Float,
    screenY: Float,
    onDragTo: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    val density = LocalDensity.current
    val halfSizePx = with(density) { 28.dp.roundToPx() }
    val latestOnDragTo = rememberUpdatedState(onDragTo)
    val latestOnDragEnd = rememberUpdatedState(onDragEnd)
    val latestOnDragCancel = rememberUpdatedState(onDragCancel)

    var dragOffset by remember(screenX, screenY) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    screenX.roundToInt() - halfSizePx,
                    screenY.roundToInt() - halfSizePx
                )
            }
            .size(56.dp)
            .pointerInput(screenX, screenY) {
                detectDragGestures(
                    onDragStart = { dragOffset = Offset.Zero },
                    onDragEnd = {
                        latestOnDragEnd.value()
                        dragOffset = Offset.Zero
                    },
                    onDragCancel = {
                        latestOnDragCancel.value()
                        dragOffset = Offset.Zero
                    },
                    onDrag = { change, dragAmount ->
                        dragOffset += dragAmount
                        latestOnDragTo.value(
                            screenX + dragOffset.x,
                            screenY + dragOffset.y
                        )
                        change.consume()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt())
                }
                .size(56.dp)
                .background(Color.White, CircleShape)
                .border(3.dp, Color(0xFFC8102E), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⋮",
                color = Color(0xFF172B3A),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun MapStopMarker(item: MapStopOverlayItem, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.width(180.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(Color(0xFF087A46), CircleShape)
                .padding(3.dp)
                .background(Color(0xFFFFD83D), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "H",
                color = Color(0xFF087A46),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        }
        if (item.name.isNotBlank()) {
            Text(
                text = item.name,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.94f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                color = Color(0xFF172B3A),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (item.isSelected || item.isNext) FontWeight.Bold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private const val ROUTE_SOURCE_ID = "recorded-route-source"
private const val ROUTE_LAYER_ID = "recorded-route-line"
private const val ROUTE_OUTLINE_LAYER_ID = "recorded-route-outline"
private const val ROUTE_DIRECTION_LAYER_ID = "recorded-route-direction-arrows"
private const val ROUTE_ARROW_ICON_ID = "route-direction-arrow"
private const val ORIGINAL_COMPARISON_SOURCE_ID = "original-comparison-source"
private const val ORIGINAL_COMPARISON_LAYER_ID = "original-comparison-line"
private const val PASSED_ROUTE_SOURCE_ID = "passed-route-source"
private const val PASSED_ROUTE_LAYER_ID = "passed-route-line"
private const val DIRECTION_SOURCE_ID = "route-direction-guide-source"
private const val DIRECTION_LAYER_ID = "route-direction-guide-line"
private const val DIRECTION_ARROW_LAYER_ID = "route-direction-guide-arrows"
private const val DEFAULT_ROUTE_COLOR_ARGB = 0xFFC62835.toInt()
private const val ROUTE_DEFAULT_WIDTH = 5f
private const val ROUTE_OUTLINE_EXTRA_WIDTH = 3f
private const val GPS_ARROW_ICON_ID = "bus-gps-arrow"
private const val ROUTE_POINTS_SOURCE_ID = "editable-route-points-source"
private const val ROUTE_POINTS_LAYER_ID = "editable-route-points-circles"
private const val ROUTE_POINT_LABELS_LAYER_ID = "editable-route-points-labels"
private const val SELECTED_ROUTE_POINT_SOURCE_ID = "selected-route-point-source"
private const val SELECTED_ROUTE_POINT_LAYER_ID = "selected-route-point-layer"
private const val SELECTED_ROUTE_SECTION_SOURCE_ID = "selected-route-section-source"
private const val SELECTED_ROUTE_SECTION_LAYER_ID = "selected-route-section-layer"
private const val PREVIEW_ROUTE_SECTION_SOURCE_ID = "preview-route-section-source"
private const val PREVIEW_ROUTE_SECTION_LAYER_ID = "preview-route-section-layer"
private const val CURRENT_POSITION_SOURCE_ID = "recorded-position-source"
private const val CURRENT_POSITION_LAYER_ID = "recorded-position-circle"
private const val NAVIGATION_ZOOM = 15.0
private const val MIN_NAVIGATION_ZOOM = 13.5
private const val MAX_NAVIGATION_ZOOM = 16.5
private const val CAMERA_FOLLOW_ANIMATION_MILLIS = 700
private const val MIN_CAMERA_HEADING_SPEED_METERS_PER_SECOND = 1.5f
private const val MAX_CAMERA_HEADING_ACCURACY_METERS = 35f
private const val CAMERA_HEADING_NOISE_THRESHOLD_DEGREES = 8f
private const val CAMERA_HEADING_SMOOTHING_FACTOR = 0.45f
private const val MAX_CACHED_LOCATION_AGE_MILLIS = 5 * 60_000L
private const val MAP_LOCATION_UPDATE_INTERVAL_MILLIS = 5_000L
private const val MAP_LOCATION_MINIMUM_DISTANCE_METERS = 5f
private const val MAP_LOCATION_STALE_AFTER_MILLIS = 30_000L
private const val MAX_RENDERED_GAP_MILLIS = 60_000L
private const val MAP_CAMERA_PREFERENCES = "map_camera_position"
private const val MAP_CAMERA_LATITUDE = "latitude_bits"
private const val MAP_CAMERA_LONGITUDE = "longitude_bits"
private const val MAP_CAMERA_ZOOM = "zoom"
private const val MAX_RENDERED_SPEED_METERS_PER_SECOND = 45.0

private fun normalizeBearing(bearing: Float): Float =
    (bearing % 360f + 360f) % 360f

private fun smoothCameraBearing(current: Float, target: Float): Float {
    val shortestDelta = (normalizeBearing(target - current + 180f) - 180f)
    if (kotlin.math.abs(shortestDelta) < CAMERA_HEADING_NOISE_THRESHOLD_DEGREES) {
        return current
    }
    return normalizeBearing(current + shortestDelta * CAMERA_HEADING_SMOOTHING_FACTOR)
}

private fun distancePixels(first: PointF, second: PointF): Float =
    kotlin.math.hypot(first.x - second.x, first.y - second.y)

private data class PixelSegmentProjection(
    val distancePixels: Float,
    val fraction: Float,
    val x: Float,
    val y: Float
)

private fun projectOntoSegmentPixels(
    point: PointF,
    start: PointF,
    end: PointF
): PixelSegmentProjection {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    val fraction = if (lengthSquared == 0f) {
        0f
    } else {
        (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared)
            .coerceIn(0f, 1f)
    }
    val projectedX = start.x + fraction * dx
    val projectedY = start.y + fraction * dy
    return PixelSegmentProjection(
        distancePixels = distancePixels(point, PointF(projectedX, projectedY)),
        fraction = fraction,
        x = projectedX,
        y = projectedY
    )
}

private fun buildSelectedRoutePointGeoJson(
    points: List<MapRoutePoint>,
    sequence: Int?
): String = buildRoutePointsGeoJson(points.filter { it.sequence == sequence })

private fun Context.readMapCameraPosition(): CameraPosition? {
    val preferences = getSharedPreferences(MAP_CAMERA_PREFERENCES, Context.MODE_PRIVATE)
    if (!preferences.contains(MAP_CAMERA_LATITUDE) ||
        !preferences.contains(MAP_CAMERA_LONGITUDE)
    ) return null
    val latitude = Double.fromBits(preferences.getLong(MAP_CAMERA_LATITUDE, 0L))
    val longitude = Double.fromBits(preferences.getLong(MAP_CAMERA_LONGITUDE, 0L))
    val zoom = preferences.getFloat(MAP_CAMERA_ZOOM, 11f).toDouble()
    if (!latitude.isFinite() || !longitude.isFinite()) return null
    return CameraPosition.Builder()
        .target(LatLng(latitude, longitude))
        .zoom(zoom)
        .build()
}

private fun Context.saveMapCameraPosition(position: CameraPosition) {
    val target = position.target ?: return
    getSharedPreferences(MAP_CAMERA_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putLong(MAP_CAMERA_LATITUDE, java.lang.Double.doubleToRawLongBits(target.latitude))
        .putLong(MAP_CAMERA_LONGITUDE, java.lang.Double.doubleToRawLongBits(target.longitude))
        .putFloat(MAP_CAMERA_ZOOM, position.zoom.toFloat())
        .apply()
}

private fun hasMapLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

private fun LocationSample.toRecordingPoint() = RecordingPoint(
    latitude = latitude,
    longitude = longitude,
    timestampEpochMillis = timestampEpochMillis,
    accuracyMeters = accuracyMeters,
    speedMetersPerSecond = speedMetersPerSecond,
    bearingDegrees = bearingDegrees,
    elapsedRealtimeNanos = elapsedRealtimeNanos
)

private fun emptyFeatureCollection(): String =
    JSONObject()
        .put("type", "FeatureCollection")
        .put("features", JSONArray())
        .toString()

private fun buildTrackGeoJson(points: List<RecordingPoint>): String {
    val features = JSONArray()
    val segments = mutableListOf<JSONArray>()
    var coordinates = JSONArray()
    var previous: RecordingPoint? = null

    points.forEach { point ->
        val lastPoint = previous
        if (lastPoint != null && shouldBreakTrack(lastPoint, point)) {
            if (coordinates.length() >= 2) segments += coordinates
            coordinates = JSONArray()
        }
        coordinates.put(JSONArray().put(point.longitude).put(point.latitude))
        previous = point
    }
    if (coordinates.length() >= 2) segments += coordinates

    segments.forEach { segment ->
        val geometry = JSONObject()
            .put("type", "LineString")
            .put("coordinates", segment)
        features.put(
            JSONObject()
                .put("type", "Feature")
                .put("geometry", geometry)
                .put("properties", JSONObject())
        )
    }
    return JSONObject()
        .put("type", "FeatureCollection")
        .put("features", features)
        .toString()
}

private fun shouldBreakTrack(previous: RecordingPoint, current: RecordingPoint): Boolean {
    val timeDelta = current.timestampEpochMillis - previous.timestampEpochMillis
    if (timeDelta <= 0L || timeDelta > MAX_RENDERED_GAP_MILLIS) return true
    val distance = FloatArray(1)
    Location.distanceBetween(
        previous.latitude,
        previous.longitude,
        current.latitude,
        current.longitude,
        distance
    )
    return distance[0] / (timeDelta / 1_000.0) > MAX_RENDERED_SPEED_METERS_PER_SECOND
}

private fun buildRouteLineGeoJson(points: List<MapRoutePoint>): String {
    if (points.size < 2) return emptyFeatureCollection()
    val segments = mutableListOf<JSONArray>()
    var coordinates = JSONArray()
    var previous: MapRoutePoint? = null

    points.forEach { point ->
        val lastPoint = previous
        if (lastPoint != null && shouldBreakRouteLine(lastPoint, point)) {
            if (coordinates.length() >= 2) segments += coordinates
            coordinates = JSONArray()
        }
        coordinates.put(JSONArray().put(point.longitude).put(point.latitude))
        previous = point
    }
    if (coordinates.length() >= 2) segments += coordinates

    val features = JSONArray()
    segments.forEach { segment ->
        val geometry = JSONObject()
            .put("type", "LineString")
            .put("coordinates", segment)
        features.put(
            JSONObject()
                .put("type", "Feature")
                .put("geometry", geometry)
                .put("properties", JSONObject())
        )
    }
    return JSONObject()
        .put("type", "FeatureCollection")
        .put("features", features)
        .toString()
}

private fun shouldBreakRouteLine(
    previous: MapRoutePoint,
    current: MapRoutePoint
): Boolean {
    val previousTime = previous.timestampEpochMillis ?: return false
    val currentTime = current.timestampEpochMillis ?: return false
    val timeDelta = currentTime - previousTime
    if (timeDelta <= 0L || timeDelta > MAX_RENDERED_GAP_MILLIS) return true
    val distance = FloatArray(1)
    Location.distanceBetween(
        previous.latitude,
        previous.longitude,
        current.latitude,
        current.longitude,
        distance
    )
    return distance[0] / (timeDelta / 1_000.0) > MAX_RENDERED_SPEED_METERS_PER_SECOND
}

private fun buildRoutePointsGeoJson(points: List<MapRoutePoint>): String {
    val features = JSONArray()
    points.forEach { point ->
        val geometry = JSONObject()
            .put("type", "Point")
            .put("coordinates", JSONArray().put(point.longitude).put(point.latitude))
        val properties = JSONObject().put("sequence", point.sequence + 1)
        features.put(
            JSONObject()
                .put("type", "Feature")
                .put("geometry", geometry)
                .put("properties", properties)
        )
    }
    return JSONObject()
        .put("type", "FeatureCollection")
        .put("features", features)
        .toString()
}

private fun buildStopsGeoJson(
    recordingStops: List<RecordingStop>,
    routeStops: List<MapRouteStop>
): String {
    val features = JSONArray()
    recordingStops.forEach { stop ->
        val geometry = JSONObject()
            .put("type", "Point")
            .put(
                "coordinates",
                JSONArray().put(stop.longitude).put(stop.latitude)
            )
        val properties = JSONObject()
            .put("sequence", stop.sequence + 1)
            .put("name", stop.name)
            .put("badge", "")
        features.put(
            JSONObject()
                .put("type", "Feature")
                .put("geometry", geometry)
                .put("properties", properties)
        )
    }
    routeStops.forEach { stop ->
        val geometry = JSONObject()
            .put("type", "Point")
            .put("coordinates", JSONArray().put(stop.longitude).put(stop.latitude))
        val badge = buildList {
            if (stop.isStart) add("Старт")
            if (stop.isEnd) add("Финиш")
            if (stop.isNext) add("Следующая")
            if (stop.isSelected) add("Выбрана")
        }.joinToString(" · ")
        val properties = JSONObject()
            .put("sequence", stop.sequence + 1)
            .put("name", stop.name)
            .put("badge", badge)
        features.put(
            JSONObject()
                .put("type", "Feature")
                .put("geometry", geometry)
                .put("properties", properties)
        )
    }
    return JSONObject()
        .put("type", "FeatureCollection")
        .put("features", features)
        .toString()
}

private fun buildStopHighlightsGeoJson(routeStops: List<MapRouteStop>): String {
    val features = JSONArray()
    routeStops.filter { it.isSelected || it.isNext }.forEach { stop ->
        val geometry = JSONObject()
            .put("type", "Point")
            .put("coordinates", JSONArray().put(stop.longitude).put(stop.latitude))
        features.put(
            JSONObject()
                .put("type", "Feature")
                .put("geometry", geometry)
                .put("properties", JSONObject())
        )
    }
    return JSONObject()
        .put("type", "FeatureCollection")
        .put("features", features)
        .toString()
}

private fun buildCurrentPositionGeoJson(position: RecordingPoint?, bearingDegrees: Float = 0f): String {
    if (position == null) return emptyFeatureCollection()
    val geometry = JSONObject()
        .put("type", "Point")
        .put(
            "coordinates",
            JSONArray().put(position.longitude).put(position.latitude)
        )
    val properties = JSONObject()
        .put("bearing", bearingDegrees)
    val feature = JSONObject()
        .put("type", "Feature")
        .put("geometry", geometry)
        .put("properties", properties)
    return JSONObject()
        .put("type", "FeatureCollection")
        .put("features", JSONArray().put(feature))
        .toString()
}

private tailrec fun Context.findComponentActivity(): ComponentActivity =
    when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findComponentActivity()
        else -> error("MapScreen requires a ComponentActivity context")
    }
