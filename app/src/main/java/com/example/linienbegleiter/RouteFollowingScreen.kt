package com.example.linienbegleiter

import android.Manifest
import com.example.linienbegleiter.data.routes.initialBearingDegrees
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.linienbegleiter.data.local.RecordingDatabase
import com.example.linienbegleiter.data.recording.RecordingPoint
import com.example.linienbegleiter.data.recording.RecordingRepository
import com.example.linienbegleiter.data.recording.RoomRecordingRepository
import com.example.linienbegleiter.data.routes.EditableRouteDirection
import com.example.linienbegleiter.data.routes.EditableRouteRepository
import com.example.linienbegleiter.data.routes.NextRouteSuggestion
import com.example.linienbegleiter.data.routes.RouteFollowSource
import com.example.linienbegleiter.data.routes.RouteColorPalette
import com.example.linienbegleiter.data.routes.RouteFollowingState
import com.example.linienbegleiter.data.routes.RoomEditableRouteRepository
import com.example.linienbegleiter.data.routes.directionDescription
import com.example.linienbegleiter.service.RouteFollowingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import java.util.Locale
import kotlin.math.roundToInt

private data class RouteFollowingPreview(
    val title: String,
    val isFinished: Boolean,
    val points: List<MapRoutePoint>,
    val stops: List<MapRouteStop>,
    val directions: List<EditableRouteDirection> = emptyList(),
    val routeColorArgb: Int,
    val routeLineWidth: Float
)

@Composable
fun RouteFollowingScreen(
    modifier: Modifier = Modifier,
    initialCameraPosition: CameraPosition?,
    onCameraPositionChanged: (CameraPosition) -> Unit,
    routeId: String,
    source: RouteFollowSource,
    initialDirectionId: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val database = remember(context) { RecordingDatabase.getInstance(context) }
    val recordingRepository: RecordingRepository = remember(database) {
        RoomRecordingRepository(database)
    }
    val editableRouteRepository: EditableRouteRepository = remember(database) {
        RoomEditableRouteRepository(database)
    }

    var routeTitle by remember(routeId) { mutableStateOf("") }
    var routeColorArgb by remember(routeId) {
        mutableStateOf(RouteColorPalette.automaticFor(routeId))
    }
    var routeLineWidth by remember(routeId) { mutableStateOf(5f) }
    var routePoints by remember(routeId) { mutableStateOf(emptyList<MapRoutePoint>()) }
    var routeStops by remember(routeId) { mutableStateOf(emptyList<MapRouteStop>()) }
    var availableDirections by remember(routeId) {
        mutableStateOf(emptyList<EditableRouteDirection>())
    }
    var selectedDirectionId by remember(routeId, initialDirectionId) {
        mutableStateOf(initialDirectionId)
    }
    var routeIsFinished by remember(routeId) { mutableStateOf(source == RouteFollowSource.EDITABLE_ROUTE) }
    var isLoading by remember(routeId) { mutableStateOf(true) }
    var loadError by remember(routeId) { mutableStateOf<String?>(null) }

    LaunchedEffect(routeId, source, recordingRepository, editableRouteRepository) {
        isLoading = true
        loadError = null
        val result = runCatching {
            withContext(Dispatchers.IO) {
                when (source) {
                    RouteFollowSource.ORIGINAL_RECORDING -> {
                        val details = recordingRepository.getDetails(routeId)
                            ?: error("GPS-запись не найдена.")
                        RouteFollowingPreview(
                            title = details.session.title,
                            isFinished = details.session.status.name == "FINISHED",
                            points = details.points.map {
                                MapRoutePoint(
                                    sequence = it.sequence,
                                    latitude = it.latitude,
                                    longitude = it.longitude,
                                    timestampEpochMillis = it.timestampEpochMillis
                                )
                            },
                            stops = details.stops.map {
                                MapRouteStop(
                                    sequence = it.sequence,
                                    name = it.name,
                                    latitude = it.latitude,
                                    longitude = it.longitude
                                )
                            },
                            routeColorArgb = details.session.routeColorArgb,
                            routeLineWidth = details.session.routeLineWidth
                        )
                    }

                    RouteFollowSource.EDITABLE_ROUTE -> {
                        val details = editableRouteRepository.getDetails(routeId)
                            ?: error("Маршрут не найден.")
                        RouteFollowingPreview(
                            title = details.route.title,
                            isFinished = true,
                            points = details.points.map {
                                MapRoutePoint(
                                    sequence = it.sequence,
                                    latitude = it.latitude,
                                    longitude = it.longitude
                                )
                            },
                            stops = details.stops.map {
                                MapRouteStop(
                                    sequence = it.sequence,
                                    name = it.name,
                                    latitude = it.latitude,
                                    longitude = it.longitude,
                                    id = it.id
                                )
                            },
                            directions = details.directions,
                            routeColorArgb = details.route.routeColorArgb,
                            routeLineWidth = details.route.routeLineWidth
                        )
                    }
                }
            }
        }
        result.onSuccess { preview ->
            routeTitle = preview.title
            routeColorArgb = preview.routeColorArgb
            routeLineWidth = preview.routeLineWidth
            routeIsFinished = preview.isFinished
            routePoints = preview.points
            routeStops = preview.stops
            availableDirections = preview.directions
            selectedDirectionId = initialDirectionId
                ?.takeIf { directionId -> preview.directions.any { it.id == directionId } }
                ?: preview.directions.firstOrNull()?.id
        }.onFailure {
            loadError = it.message ?: "Не удалось загрузить маршрут."
        }
        isLoading = false
    }

    var followingBinder by remember {
        mutableStateOf<RouteFollowingService.FollowingBinder?>(null)
    }
    val serviceConnection = remember(context) {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                followingBinder = binder as? RouteFollowingService.FollowingBinder
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                followingBinder = null
            }
        }
    }
    DisposableEffect(context, serviceConnection) {
        val isBound = context.bindService(
            Intent(context, RouteFollowingService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
        onDispose {
            if (isBound) context.unbindService(serviceConnection)
            followingBinder = null
        }
    }

    val stateFlow = remember(followingBinder) {
        followingBinder?.followingState ?: flowOf(RouteFollowingState())
    }
    val followingState by stateFlow.collectAsState(initial = RouteFollowingState())
    val followsThisRoute = followingState.isActive &&
        followingState.routeId == routeId &&
        followingState.source == source
    val anotherRouteIsActive = followingState.isActive && !followsThisRoute

    var showPermissionExplanation by remember { mutableStateOf(false) }
    var showFinishConfirmation by remember { mutableStateOf(false) }
    var showStopConfirmationDialog by remember { mutableStateOf(false) }
    var followCamera by remember { mutableStateOf(true) }
    var recenterToken by remember { mutableStateOf(0) }
    var userGestureToken by remember { mutableStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (hasFollowingLocationPermission(context)) {
            errorMessage = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                results[Manifest.permission.POST_NOTIFICATIONS] == false
            ) {
                "Уведомления отключены в системе; Android всё равно может показать активное следование в списке приложений."
            } else {
                null
            }
            RouteFollowingService.start(
                context,
                routeId,
                source,
                selectedDirectionId
            )
        } else {
            errorMessage = "Без доступа к геопозиции следование запустить нельзя."
        }
    }

    LaunchedEffect(userGestureToken) {
        if (userGestureToken > 0) {
            kotlinx.coroutines.delay(8_000L)
            followCamera = true
            recenterToken += 1
        }
    }

    BackHandler(enabled = followsThisRoute) {
        showStopConfirmationDialog = true
    }

    if (showStopConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { showStopConfirmationDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 12.dp,
            shape = RoundedCornerShape(28.dp),
            title = { Text("Остановка навигации") },
            text = { Text("Хотите остановить навигацию по маршруту?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStopConfirmationDialog = false
                        RouteFollowingService.stop(context)
                        onBack()
                    }
                ) { Text("Остановить") }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirmationDialog = false }) {
                    Text("Продолжить")
                }
            }
        )
    }

    var isSimulating by remember { mutableStateOf(false) }
    var simulationIndex by remember { mutableIntStateOf(0) }
    var simulatedPosition by remember { mutableStateOf<RecordingPoint?>(null) }

    val selectedDirection = availableDirections.firstOrNull {
        it.id == selectedDirectionId
    }
    val stopById = routeStops.mapNotNull { stop ->
        stop.id?.let { it to stop }
    }.toMap()
    val startStop = stopById[selectedDirection?.startStopId ?: 0L]
    val endStop = stopById[selectedDirection?.endStopId ?: 0L]

    val rawDirectionPoints = if (selectedDirection?.isGeometryReversed == true) {
        routePoints.asReversed().mapIndexed { index, point -> point.copy(sequence = index) }
    } else {
        routePoints
    }

    val directionPoints = if (startStop != null && endStop != null && rawDirectionPoints.size >= 2) {
        val firstPt = rawDirectionPoints.first()
        val lastPt = rawDirectionPoints.last()
        val distToFirstSq = (firstPt.latitude - startStop.latitude).let { it * it } + (firstPt.longitude - startStop.longitude).let { it * it }
        val distToLastSq = (lastPt.latitude - startStop.latitude).let { it * it } + (lastPt.longitude - startStop.longitude).let { it * it }
        if (distToLastSq < distToFirstSq) {
            rawDirectionPoints.asReversed().mapIndexed { index, point -> point.copy(sequence = index) }
        } else {
            rawDirectionPoints
        }
    } else {
        rawDirectionPoints
    }

    LaunchedEffect(isSimulating, directionPoints) {
        if (isSimulating && directionPoints.isNotEmpty()) {
            var idx = simulationIndex.coerceIn(directionPoints.indices)
            while (isSimulating && idx < directionPoints.size) {
                val pt = directionPoints[idx]
                val nextPt = directionPoints.getOrNull(idx + 1)
                val bearing = if (nextPt != null) {
                    initialBearingDegrees(pt.latitude, pt.longitude, nextPt.latitude, nextPt.longitude).toFloat()
                } else {
                    simulatedPosition?.bearingDegrees ?: 0f
                }
                simulatedPosition = RecordingPoint(
                    sequence = pt.sequence,
                    latitude = pt.latitude,
                    longitude = pt.longitude,
                    timestampEpochMillis = System.currentTimeMillis(),
                    accuracyMeters = 5.0f,
                    speedMetersPerSecond = 12.0f,
                    bearingDegrees = bearing,
                    elapsedRealtimeNanos = System.nanoTime()
                )
                recenterToken += 1
                delay(80L)
                idx++
                simulationIndex = idx
                if (idx >= directionPoints.size) {
                    idx = 0
                }
            }
        }
    }

    val activePosition = if (isSimulating) {
        simulatedPosition
    } else if (followsThisRoute) {
        followingState.locationSample?.let { sample ->
            RecordingPoint(
                latitude = sample.latitude,
                longitude = sample.longitude,
                timestampEpochMillis = sample.timestampEpochMillis,
                accuracyMeters = sample.accuracyMeters,
                speedMetersPerSecond = sample.speedMetersPerSecond,
                bearingDegrees = sample.bearingDegrees,
                elapsedRealtimeNanos = sample.elapsedRealtimeNanos
            )
        }
    } else {
        null
    }
    val directionStops = selectedDirection?.stopIdsInOrder
        ?.mapIndexedNotNull { index, id ->
            stopById[id]?.copy(
                sequence = index,
                isStart = id == selectedDirection.startStopId,
                isEnd = id == selectedDirection.endStopId
            )
        } ?: routeStops
    val passedRoutePoints = if (followsThisRoute) {
        val remainingStartSequence = followingState.progress?.remainingPoints?.firstOrNull()?.sequence
        if (remainingStartSequence == null) {
            emptyList()
        } else {
            val passed = followingState.routePoints
                .takeWhile { it.sequence < remainingStartSequence }
                .map {
                    MapRoutePoint(it.sequence, it.latitude, it.longitude, it.timestampEpochMillis)
                }
            val endpoint = activePosition?.let { current ->
                MapRoutePoint(
                    sequence = remainingStartSequence,
                    latitude = current.latitude,
                    longitude = current.longitude,
                    timestampEpochMillis = current.timestampEpochMillis
                )
            }
            if (endpoint == null) passed else passed + endpoint
        }
    } else {
        emptyList()
    }
    val displayPoints = if (followsThisRoute) {
        followingState.progress?.remainingPoints?.map {
            MapRoutePoint(
                sequence = it.sequence,
                latitude = it.latitude,
                longitude = it.longitude,
                timestampEpochMillis = it.timestampEpochMillis
            )
        } ?: routePoints
    } else {
        directionPoints
    }
    val displayStops = if (followsThisRoute) {
        followingState.stops.map { stop ->
            MapRouteStop(
                sequence = stop.sequence,
                name = stop.name,
                latitude = stop.latitude,
                longitude = stop.longitude,
                id = stop.id,
                isStart = stop.sequence == 0,
                isEnd = stop.sequence == followingState.stops.lastIndex,
                isNext = stop.sequence == followingState.progress?.nextStop?.sequence
            )
        }
    } else {
        directionStops
    }

    if (showPermissionExplanation) {
        AlertDialog(
            onDismissRequest = { showPermissionExplanation = false },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 12.dp,
            shape = RoundedCornerShape(28.dp),
            title = { Text("Разрешения для следования") },
            text = {
                Text(
                    "Linienbegleiter использует геопозицию, чтобы показывать ваше место на маршруте, " +
                        "расстояние до остановок и отклонение от линии. Следование продолжится в фоне " +
                        "с постоянным уведомлением. Фоновое разрешение на геолокацию не запрашивается."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPermissionExplanation = false
                        val permissions = buildList {
                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                            add(Manifest.permission.ACCESS_COARSE_LOCATION)
                            if (notificationPermissionMissing(context)) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        permissionLauncher.launch(permissions.toTypedArray())
                    }
                ) { Text("Продолжить") }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionExplanation = false }) {
                    Text("Не сейчас")
                }
            }
        )
    }

    if (showFinishConfirmation) {
        AlertDialog(
            onDismissRequest = { showFinishConfirmation = false },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 12.dp,
            shape = RoundedCornerShape(28.dp),
            title = { Text("Остановить следование?") },
            text = { Text("Текущее положение больше не будет отслеживаться.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFinishConfirmation = false
                        RouteFollowingService.stop(context)
                    }
                ) { Text("Остановить") }
            },
            dismissButton = {
                TextButton(onClick = { showFinishConfirmation = false }) {
                    Text("Продолжить")
                }
            }
        )
    }

    if (isLoading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Загружаю маршрут…", style = MaterialTheme.typography.bodyLarge)
        }
    } else if (loadError != null) {
        Box(
            modifier.fillMaxSize().padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
        }
    } else {
        val progress = if (followsThisRoute) followingState.progress else null
        Box(modifier.fillMaxSize()) {
            MapScreen(
                modifier = Modifier.fillMaxSize(),
                initialCameraPosition = initialCameraPosition,
                onCameraPositionChanged = onCameraPositionChanged,
                onBack = onBack,
                routePoints = displayPoints,
                routeStops = displayStops,
                routeColorArgb = routeColorArgb,
                routeLineWidth = routeLineWidth,
                passedRoutePoints = passedRoutePoints,
                currentPosition = activePosition,
                showRoutePointMarkers = false,
                followCurrentPosition = followsThisRoute && followCamera,
                recenterToken = recenterToken,
                onUserMapGesture = {
                    if (followsThisRoute) {
                        followCamera = false
                        userGestureToken += 1
                    }
                },
                showChrome = false
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 4.dp
                ) {
                    Text(
                        text = routeTitle.ifBlank { "Маршрут" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }

                Surface(
                    onClick = { isSimulating = !isSimulating },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 52.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSimulating) Color(0xFFD32F2F) else Color(0xFF2E7D32),
                    contentColor = Color.White,
                    tonalElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(if (isSimulating) "⏹" else "▶", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(if (isSimulating) "Стоп" else "Старт", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }

            val announcement = followingState.latestStreetAnnouncement
            val showAnnouncement = announcement != null &&
                (System.currentTimeMillis() - announcement.timestampEpochMillis) in 0L..10_000L
            if (followsThisRoute && showAnnouncement && !followingState.isOffRoute) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 52.dp),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E3A8A),
                        contentColor = Color.White
                    ),
                    border = BorderStroke(1.5.dp, Color(0xFF60A5FA))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("🛣️", fontSize = 20.sp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Вы находитесь на улице",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF93C5FD)
                            )
                            Text(
                                announcement!!.streetName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            if (followsThisRoute && followingState.isOffRoute) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 52.dp),
                    shape = RoundedCornerShape(14.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFD32F2F),
                        contentColor = Color.White
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("⚠️", fontSize = 18.sp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Вы сошли с маршрута!",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            val devMeters = followingState.offRouteDistanceMeters?.roundToInt() ?: 0
                            val threshold = AppPreferences.offRouteThresholdMeters(context).roundToInt()
                            Text(
                                "Отклонение: $devMeters м (порог: $threshold м)",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            }

            if (anotherRouteIsActive) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 52.dp)
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(
                            "Уже выполняется следование по другому маршруту.",
                            modifier = Modifier.padding(10.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            if (followsThisRoute && followingState.isArrivedAtDestination) {
                ArrivalDeparturePanel(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    state = followingState,
                    onStartNextRoute = { suggestion ->
                        RouteFollowingService.start(
                            context,
                            suggestion.routeId,
                            suggestion.source,
                            suggestion.directionId
                        )
                    },
                    onFinish = {
                        RouteFollowingService.stop(context)
                    }
                )
            } else if (followsThisRoute && followingState.progress != null) {
                FollowingStatusPanel(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    state = followingState,
                    onRecenter = {
                        followCamera = true
                        recenterToken += 1
                    },
                    onStop = { showFinishConfirmation = true }
                )
            } else {
                BeforeFollowingPanel(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    context = context,
                    source = source,
                    directions = availableDirections,
                    selectedDirectionId = selectedDirectionId,
                    onDirectionSelected = { selectedDirectionId = it },
                    routeIsFinished = routeIsFinished,
                    routePointCount = routePoints.size,
                    routeStopCount = routeStops.size,
                    gpsReady = isLocationProviderEnabled(context),
                    errorMessage = errorMessage ?: followingState.errorMessage,
                    anotherRouteIsActive = anotherRouteIsActive,
                    onStart = {
                        if (!routeIsFinished || routePoints.size < 2) {
                            errorMessage = "Для следования нужна завершённая запись минимум с двумя точками пути."
                        } else if (anotherRouteIsActive) {
                            errorMessage = "Сначала остановите следование по другому маршруту."
                        } else if (
                            !hasFollowingLocationPermission(context) ||
                            notificationPermissionMissing(context)
                        ) {
                            showPermissionExplanation = true
                        } else {
                            errorMessage = null
                            RouteFollowingService.start(
                                context,
                                routeId,
                                source,
                                selectedDirectionId
                            )
                        }
                    },
                    onStopOther = { showFinishConfirmation = true }
                )
            }
        }
    }
}

@Composable
private fun ArrivalDeparturePanel(
    modifier: Modifier = Modifier,
    state: RouteFollowingState,
    onStartNextRoute: (NextRouteSuggestion) -> Unit,
    onFinish: () -> Unit
) {
    val terminalStopName = state.stops.lastOrNull()?.name?.ifBlank { null }
        ?: state.progress?.previousStop?.name
        ?: "Конечная станция"
    Card(
        modifier = modifier.heightIn(max = 340.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "🎉 Прибытие на конечную!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Остановка: $terminalStopName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onFinish,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Завершить", fontSize = 12.sp)
                }
            }

            if (state.suggestedNextRoutes.isNotEmpty()) {
                Text(
                    "Выберите следующий рейс (Прибытие ➔ Отправление):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )

                state.suggestedNextRoutes.forEach { suggestion ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (suggestion.isReturnTrip) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            }
                        ),
                        border = BorderStroke(
                            width = if (suggestion.isReturnTrip) 1.5.dp else 1.dp,
                            color = if (suggestion.isReturnTrip) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (suggestion.isReturnTrip) {
                                        Text(
                                            "🔄 Обратный рейс",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        Text(
                                            "🚌 Рейс",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(
                                    suggestion.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    suggestion.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Button(
                                onClick = { onStartNextRoute(suggestion) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("▶ Старт", fontSize = 12.sp)
                            }
                        }
                    }
                }
            } else {
                Text(
                    "Рейс завершён. Других сохранённых маршрутов от этой остановки не найдено.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FollowingStatusPanel(
    modifier: Modifier = Modifier,
    state: RouteFollowingState,
    onRecenter: () -> Unit,
    onStop: () -> Unit
) {
    val progress = state.progress
    val sample = state.locationSample
    val nextStop = progress?.nextStop
    val previousStop = progress?.previousStop
    val gpsText = if (sample?.accuracyMeters != null) {
        "GPS ±${sample.accuracyMeters.toInt()}м"
    } else {
        locationStatusText(state)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xEE111827),
        contentColor = Color.White,
        tonalElevation = 8.dp,
        border = BorderStroke(1.5.dp, Color(0xFF3B82F6))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("🎯", fontSize = 20.sp)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "СЛЕД: ${nextStop?.name ?: "—"}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF60A5FA)
                    )
                    Text(
                        text = progress?.distanceToNextStopMeters?.let(::formatFollowDistance) ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Откл: ${progress?.distanceToRouteMeters?.let(::formatFollowDistance) ?: "—"} · $gpsText",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF9CA3AF)
                    )
                }
            }

            Surface(
                onClick = onRecenter,
                shape = CircleShape,
                color = Color(0xFF1E3A8A),
                contentColor = Color.White,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("📍", fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun BeforeFollowingPanel(
    modifier: Modifier = Modifier,
    context: Context,
    source: RouteFollowSource,
    directions: List<EditableRouteDirection>,
    selectedDirectionId: String?,
    onDirectionSelected: (String) -> Unit,
    routeIsFinished: Boolean,
    routePointCount: Int,
    routeStopCount: Int,
    gpsReady: Boolean,
    errorMessage: String?,
    anotherRouteIsActive: Boolean,
    onStart: () -> Unit,
    onStopOther: () -> Unit
) {
    Card(
        modifier = modifier.heightIn(max = 280.dp),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
            when {
                !routeIsFinished && source == RouteFollowSource.ORIGINAL_RECORDING ->
                    "Эта GPS-запись ещё не завершена."
                !hasFollowingLocationPermission(context) -> "Нужно разрешить доступ к геопозиции."
                !gpsReady -> "Включите GPS или службы определения местоположения."
                else -> "Готов к началу следования"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "$routePointCount точек пути · $routeStopCount остановок",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (directions.isNotEmpty()) {
            Text("Направление", style = MaterialTheme.typography.titleSmall)
            directions.forEach { direction ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = direction.id == selectedDirectionId,
                        onClick = { onDirectionSelected(direction.id) }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(direction.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            directionDescription(direction.initialBearingDegrees),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (anotherRouteIsActive) {
                OutlinedButton(
                    onClick = onStopOther,
                    modifier = Modifier.weight(1f)
                ) { Text("Остановить текущее") }
            }
            Button(
                onClick = onStart,
                modifier = Modifier.weight(1f),
                enabled = routePointCount >= 2 && routeIsFinished && !anotherRouteIsActive
            ) {
                Text("Начать следование")
            }
            }
        }
    }
}

private fun hasFollowingLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

private fun notificationPermissionMissing(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED

private fun isLocationProviderEnabled(context: Context): Boolean {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return runCatching {
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(false)
}

private fun locationStatusText(state: RouteFollowingState): String =
    when (state.locationStatus) {
        com.example.linienbegleiter.data.location.LocationProviderStatus.WaitingForSignal ->
            "Ожидание сигнала GPS…"
        com.example.linienbegleiter.data.location.LocationProviderStatus.PermissionMissing ->
            "Нет разрешения на геопозицию."
        com.example.linienbegleiter.data.location.LocationProviderStatus.ProviderDisabled ->
            "Службы определения местоположения выключены."
        is com.example.linienbegleiter.data.location.LocationProviderStatus.ProviderAvailable ->
            "GPS доступен."
        is com.example.linienbegleiter.data.location.LocationProviderStatus.Error ->
            state.locationStatus.message
    }

private fun directionFromBearing(bearing: Float): Pair<String, String> {
    val directions = listOf(
        "Север",
        "Северо-восток",
        "Восток",
        "Юго-восток",
        "Юг",
        "Юго-запад",
        "Запад",
        "Северо-запад"
    )
    val arrows = listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
    val index = (((bearing % 360f + 360f) % 360f + 22.5f) / 45f).toInt() % 8
    return arrows[index] to directions[index]
}

private fun formatFollowDistance(meters: Double): String =
    if (meters < 1_000.0) {
        "${meters.roundToInt()} м"
    } else {
        String.format(Locale.getDefault(), "%.1f км", meters / 1_000.0)
    }
