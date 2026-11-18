package com.example.linienbegleiter

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.linienbegleiter.data.local.RecordingDatabase
import com.example.linienbegleiter.data.location.LocationProviderStatus
import com.example.linienbegleiter.data.recording.RecordingPoint
import com.example.linienbegleiter.data.recording.RecordingRepository
import com.example.linienbegleiter.data.recording.RecordingRuntimeState
import com.example.linienbegleiter.data.recording.RecordingSession
import com.example.linienbegleiter.data.recording.RecordingStatus
import com.example.linienbegleiter.data.recording.RecordingStop
import com.example.linienbegleiter.data.recording.RoomRecordingRepository
import com.example.linienbegleiter.service.RouteRecordingService
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun RecordingScreen(
    modifier: Modifier = Modifier,
    initialCameraPosition: CameraPosition?,
    onCameraPositionChanged: (CameraPosition) -> Unit,
    recordingId: String?,
    onRecordingIdChanged: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context.findRecordingActivity()
    val database = remember(context) { RecordingDatabase.getInstance(context) }
    val repository: RecordingRepository = remember(database) {
        RoomRecordingRepository(database)
    }
    val coroutineScope = rememberCoroutineScope()

    val activeSession by remember(repository) {
        repository.observeActiveSession()
    }.collectAsState(initial = null)

    LaunchedEffect(activeSession?.id, recordingId) {
        if (recordingId == null) {
            activeSession?.let { onRecordingIdChanged(it.id) }
        }
    }

    val selectedSession by remember(repository, recordingId) {
        recordingId?.let { repository.observeSession(it) } ?: flowOf(null)
    }.collectAsState(initial = null)
    val points by remember(repository, recordingId) {
        recordingId?.let { repository.observePoints(it) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val stops by remember(repository, recordingId) {
        recordingId?.let { repository.observeStops(it) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    var boundService by remember {
        mutableStateOf<RouteRecordingService.RecordingBinder?>(null)
    }
    var isStarting by remember { mutableStateOf(false) }
    var hasRequestedRecordingStart by remember { mutableStateOf(false) }
    val serviceConnection = remember(context) {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = binder as? RouteRecordingService.RecordingBinder
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundService = null
            }
        }
    }
    DisposableEffect(context, serviceConnection) {
        val bound = context.bindService(
            Intent(context, RouteRecordingService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
        onDispose {
            if (bound) context.unbindService(serviceConnection)
            boundService = null
        }
    }

    LaunchedEffect(activeSession?.id) {
        val active = activeSession
        if (
            !hasRequestedRecordingStart &&
            active != null &&
            active.status in setOf(RecordingStatus.RECORDING, RecordingStatus.PAUSED)
        ) {
            RouteRecordingService.restore(context)
        }
    }

    val runtimeFlow = remember(boundService) {
        boundService?.recordingState ?: flowOf(RecordingRuntimeState())
    }
    val runtimeState by runtimeFlow.collectAsState(initial = RecordingRuntimeState())

    var routeTitle by remember { mutableStateOf("Новый маршрут") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showAddStopDialog by remember { mutableStateOf(false) }
    var showFinishDialog by remember { mutableStateOf(false) }
    var stopName by remember { mutableStateOf("") }
    var permissionDialog by remember { mutableStateOf(RecordingPermissionDialog.NONE) }
    val hasLocationPermission = hasForegroundLocationPermission(context)
    val hasNotificationPermission = hasNotificationPermission(context)

    LaunchedEffect(selectedSession?.id, selectedSession?.status) {
        selectedSession?.let { session ->
            if (session.status == RecordingStatus.IDLE) {
                routeTitle = session.title
            }
            if (session.status != RecordingStatus.IDLE) {
                isStarting = false
            }
        }
    }

    fun createAndStartSession() {
        if (isStarting) return
        val cleanTitle = routeTitle.trim()
        if (selectedSession == null && cleanTitle.isEmpty()) {
            errorMessage = "Введите название маршрута."
            return
        }
        isStarting = true
        hasRequestedRecordingStart = true
        errorMessage = null
        coroutineScope.launch {
            try {
                val id = selectedSession
                    ?.takeIf { it.status == RecordingStatus.IDLE }
                    ?.id
                    ?: repository.createSession(
                        title = cleanTitle.ifEmpty { "Новый маршрут" },
                        createdAtEpochMillis = System.currentTimeMillis()
                    ).id
                onRecordingIdChanged(id)
                RouteRecordingService.start(activity, id)
            } catch (exception: Exception) {
                isStarting = false
                errorMessage = exception.message
                    ?: "Не удалось запустить службу GPS-записи."
            }
        }
    }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                createAndStartSession()
            } else {
                permissionDialog =
                    if (activity.shouldShowRequestPermissionRationale(
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    ) {
                        RecordingPermissionDialog.NOTIFICATION_DENIED
                    } else {
                        RecordingPermissionDialog.NOTIFICATION_SETTINGS
                    }
            }
        }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val locationGranted =
            results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!locationGranted) {
            val showRationale =
                activity.shouldShowRequestPermissionRationale(
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) || activity.shouldShowRequestPermissionRationale(
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            permissionDialog = if (showRationale) {
                RecordingPermissionDialog.LOCATION_DENIED
            } else {
                RecordingPermissionDialog.LOCATION_SETTINGS
            }
        } else if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasNotificationPermission(context)
        ) {
            permissionDialog = RecordingPermissionDialog.NOTIFICATION_EXPLANATION
        } else {
            createAndStartSession()
        }
    }

    fun requestRecordingStart() {
        errorMessage = null
        if (!hasForegroundLocationPermission(context)) {
            permissionDialog = RecordingPermissionDialog.LOCATION_EXPLANATION
        } else if (!hasNotificationPermission(context)) {
            permissionDialog = RecordingPermissionDialog.NOTIFICATION_EXPLANATION
        } else {
            createAndStartSession()
        }
    }

    val selectedStatus = selectedSession?.status
    val hasStarted = selectedStatus == RecordingStatus.RECORDING ||
        selectedStatus == RecordingStatus.PAUSED ||
        selectedStatus == RecordingStatus.FINISHED

    if (hasStarted) {
        RecordingMapDashboard(
            modifier = modifier,
            initialCameraPosition = initialCameraPosition,
            onCameraPositionChanged = onCameraPositionChanged,
            onBack = onBack,
            session = requireNotNull(selectedSession),
            points = points,
            stops = stops,
            runtimeState = runtimeState,
            boundService = boundService,
            errorMessage = errorMessage ?: runtimeState.errorMessage,
            onAddStopRequested = {
                val lastPoint = points.lastOrNull()
                val ageMillis = lastPoint?.let {
                    System.currentTimeMillis() - it.timestampEpochMillis
                }
                val freshAndAccurate = lastPoint != null &&
                    ageMillis != null &&
                    ageMillis in -5_000L..10_000L &&
                    lastPoint.accuracyMeters != null &&
                    lastPoint.accuracyMeters <= 50f
                if (!freshAndAccurate) {
                    errorMessage =
                        "Нет свежей GPS-точки с точностью до 50 м. Подождите сигнал GPS и попробуйте снова."
                } else {
                    stopName = ""
                    showAddStopDialog = true
                }
            },
            onPause = { RouteRecordingService.pause(context) },
            onResume = { RouteRecordingService.resume(context) },
            onFinishRequested = { showFinishDialog = true },
            onOpenRoutes = onBack
        )
    } else {
        RecordingStartScreen(
            modifier = modifier,
            initialCameraPosition = initialCameraPosition,
            onCameraPositionChanged = onCameraPositionChanged,
            onBack = onBack,
            title = routeTitle,
            onTitleChanged = { routeTitle = it },
            hasLocationPermission = hasLocationPermission,
            hasNotificationPermission = hasNotificationPermission,
            isStarting = isStarting,
            errorMessage = errorMessage,
            onStart = ::requestRecordingStart
        )
    }

    when (permissionDialog) {
        RecordingPermissionDialog.NONE -> Unit
        RecordingPermissionDialog.LOCATION_EXPLANATION -> AlertDialog(
            onDismissRequest = { permissionDialog = RecordingPermissionDialog.NONE },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 12.dp,
            shape = RoundedCornerShape(28.dp),
            title = { Text("Разрешить геолокацию") },
            text = {
                Text(
                    "Геолокация нужна, чтобы записывать путь автобуса и ставить остановки " +
                        "на их фактические координаты. Разрешение используется только после " +
                        "нажатия «Начать запись»."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    permissionDialog = RecordingPermissionDialog.NONE
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }) {
                    Text("Продолжить")
                }
            },
            dismissButton = {
                TextButton(onClick = { permissionDialog = RecordingPermissionDialog.NONE }) {
                    Text("Отмена")
                }
            }
        )
        RecordingPermissionDialog.LOCATION_DENIED -> PermissionInfoDialog(
            title = "Без геолокации запись не начнётся",
            message = "Разрешите геолокацию, чтобы приложение могло сохранять GPS-точки маршрута.",
            confirmText = "Понятно",
            onConfirm = { permissionDialog = RecordingPermissionDialog.NONE }
        )
        RecordingPermissionDialog.LOCATION_SETTINGS -> PermissionInfoDialog(
            title = "Разрешение отключено",
            message = "Включите геолокацию для Linienbegleiter в настройках приложения.",
            confirmText = "Открыть настройки",
            onConfirm = {
                permissionDialog = RecordingPermissionDialog.NONE
                openApplicationSettings(activity)
            },
            onDismiss = { permissionDialog = RecordingPermissionDialog.NONE }
        )
        RecordingPermissionDialog.NOTIFICATION_EXPLANATION -> AlertDialog(
            onDismissRequest = { permissionDialog = RecordingPermissionDialog.NONE },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 12.dp,
            shape = RoundedCornerShape(28.dp),
            title = { Text("Показывать состояние записи") },
            text = {
                Text(
                    "Постоянное уведомление показывает, что GPS-запись продолжается, " +
                        "и позволяет открыть экран маршрута при выключенном экране."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    permissionDialog = RecordingPermissionDialog.NONE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    } else {
                        createAndStartSession()
                    }
                }) {
                    Text("Продолжить")
                }
            },
            dismissButton = {
                TextButton(onClick = { permissionDialog = RecordingPermissionDialog.NONE }) {
                    Text("Отмена")
                }
            }
        )
        RecordingPermissionDialog.NOTIFICATION_DENIED -> PermissionInfoDialog(
            title = "Нужно разрешить уведомления",
            message = "Для этой версии Linienbegleiter уведомление нужно, чтобы запись не работала незаметно.",
            confirmText = "Понятно",
            onConfirm = { permissionDialog = RecordingPermissionDialog.NONE }
        )
        RecordingPermissionDialog.NOTIFICATION_SETTINGS -> PermissionInfoDialog(
            title = "Уведомления отключены",
            message = "Включите уведомления Linienbegleiter в настройках приложения, чтобы видеть активную запись.",
            confirmText = "Открыть настройки",
            onConfirm = {
                permissionDialog = RecordingPermissionDialog.NONE
                openApplicationSettings(activity)
            },
            onDismiss = { permissionDialog = RecordingPermissionDialog.NONE }
        )
    }

    if (showAddStopDialog) {
        AlertDialog(
            onDismissRequest = { showAddStopDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 12.dp,
            shape = RoundedCornerShape(28.dp),
            title = { Text("Добавить остановку") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Координаты будут взяты из последнего точного GPS-измерения.")
                    OutlinedTextField(
                        value = stopName,
                        onValueChange = { stopName = it },
                        label = { Text("Название остановки") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = stopName.isNotBlank() && boundService != null,
                    onClick = {
                        val service = boundService
                        if (service == null) {
                            errorMessage = "Служба записи ещё подключается. Попробуйте ещё раз."
                        } else {
                            val name = stopName.trim()
                            coroutineScope.launch {
                                try {
                                    service.addStop(name)
                                    showAddStopDialog = false
                                    errorMessage = null
                                } catch (exception: Exception) {
                                    errorMessage = exception.message
                                        ?: "Не удалось сохранить остановку."
                                }
                            }
                        }
                    }
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddStopDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    if (showFinishDialog) {
        AlertDialog(
            onDismissRequest = { showFinishDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 12.dp,
            shape = RoundedCornerShape(28.dp),
            title = { Text("Завершить запись?") },
            text = {
                Text("GPS-точки и добавленные остановки будут сохранены как исходная запись.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showFinishDialog = false
                    RouteRecordingService.stop(context)
                }) {
                    Text("Завершить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFinishDialog = false }) {
                    Text("Продолжить запись")
                }
            }
        )
    }
}

@Composable
private fun RecordingStartScreen(
    modifier: Modifier,
    initialCameraPosition: CameraPosition?,
    onCameraPositionChanged: (CameraPosition) -> Unit,
    onBack: () -> Unit,
    title: String,
    onTitleChanged: (String) -> Unit,
    hasLocationPermission: Boolean,
    hasNotificationPermission: Boolean,
    isStarting: Boolean,
    errorMessage: String?,
    onStart: () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        MapScreen(
            modifier = Modifier.fillMaxSize(),
            initialCameraPosition = initialCameraPosition,
            onCameraPositionChanged = onCameraPositionChanged,
            onBack = {},
            showChrome = false
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .padding(bottom = 52.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            RecordingHeader(
                title = "Запись маршрута",
                status = "Готово к записи",
                onBack = onBack
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Новая GPS-запись",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = title,
                        onValueChange = onTitleChanged,
                        label = { Text("Название маршрута") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        if (hasLocationPermission) {
                            "Геолокация разрешена. Запись начнётся только после нажатия кнопки."
                        } else {
                            "Для записи потребуется разрешить доступ к геолокации."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (hasNotificationPermission) {
                            "Уведомление будет показывать состояние записи в фоне."
                        } else {
                            "Для видимого фонового режима потребуется разрешить уведомления."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (errorMessage != null) {
                        Text(
                            errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Button(
                        onClick = onStart,
                        enabled = !isStarting && title.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(
                            if (isStarting) "Запускаем запись…" else "Начать запись",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingMapDashboard(
    modifier: Modifier,
    initialCameraPosition: CameraPosition?,
    onCameraPositionChanged: (CameraPosition) -> Unit,
    onBack: () -> Unit,
    session: RecordingSession,
    points: List<RecordingPoint>,
    stops: List<RecordingStop>,
    runtimeState: RecordingRuntimeState,
    boundService: RouteRecordingService.RecordingBinder?,
    errorMessage: String?,
    onAddStopRequested: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinishRequested: () -> Unit,
    onOpenRoutes: () -> Unit
) {
    val context = LocalContext.current
    val currentPosition = points.lastOrNull()?.takeIf { point ->
        session.status == RecordingStatus.RECORDING &&
            System.currentTimeMillis() - point.timestampEpochMillis in -5_000L..15_000L
    }
    val runtimeDuration = if (runtimeState.session?.id == session.id) {
        runtimeState.durationSeconds
    } else {
        session.durationSeconds
    }
    val accuracy = runtimeState.lastSample?.accuracyMeters
        ?: currentPosition?.accuracyMeters
    Box(modifier = modifier.fillMaxSize()) {
        MapScreen(
            modifier = Modifier.fillMaxSize(),
            initialCameraPosition = initialCameraPosition,
            onCameraPositionChanged = onCameraPositionChanged,
            onBack = {},
            trackPoints = points,
            recordingStops = stops,
            routeColorArgb = session.routeColorArgb,
            routeLineWidth = session.routeLineWidth,
            currentPosition = currentPosition,
            followCurrentPosition = session.status == RecordingStatus.RECORDING,
            showChrome = false
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .padding(bottom = 54.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            RecordingHeader(
                title = session.title,
                status = when (session.status) {
                    RecordingStatus.RECORDING -> "Идёт запись"
                    RecordingStatus.PAUSED -> "Пауза"
                    RecordingStatus.FINISHED -> "Завершено"
                    RecordingStatus.IDLE -> "Подготовка"
                },
                onBack = onBack
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 390.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    RecordingStatistics(
                        session = session,
                        pointCount = points.size,
                        stopCount = stops.size,
                        accuracy = accuracy,
                        runtimeStatus = runtimeState,
                        runtimeDurationSeconds = runtimeDuration
                    )
                    val drivingEnabled = remember { AppPreferences.drivingAnalysisEnabled(context) }
                    val drivingStats = runtimeState.drivingStats
                    if (drivingEnabled && drivingStats != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Eco-Drive рейтинг", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("${drivingStats.score}/100", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                                Text(drivingStats.grade, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Резких торможений: ${drivingStats.harshBrakingCount} · Резких разгонов: ${drivingStats.harshAccelerationCount}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (errorMessage != null) {
                        Text(
                            errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    when (session.status) {
                        RecordingStatus.RECORDING -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = onAddStopRequested,
                                    enabled = boundService != null,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(56.dp)
                                ) {
                                    Text("＋ Остановка")
                                }
                                OutlinedButton(
                                    onClick = onPause,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(56.dp)
                                ) {
                                    Text("Пауза")
                                }
                            }
                            Button(
                                onClick = onFinishRequested,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Завершить запись")
                            }
                        }
                        RecordingStatus.PAUSED -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onResume,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(56.dp)
                                ) {
                                    Text("Продолжить")
                                }
                                OutlinedButton(
                                    onClick = onFinishRequested,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(56.dp)
                                ) {
                                    Text("Завершить")
                                }
                            }
                        }
                        RecordingStatus.FINISHED -> {
                            FinishedStops(stops = stops)
                            OutlinedButton(
                                onClick = onOpenRoutes,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                            ) {
                                Text("К списку маршрутов")
                            }
                        }
                        RecordingStatus.IDLE -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingHeader(
    title: String,
    status: String,
    onBack: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("‹ Назад")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun RecordingStatistics(
    session: RecordingSession,
    pointCount: Int,
    stopCount: Int,
    accuracy: Float?,
    runtimeStatus: RecordingRuntimeState,
    runtimeDurationSeconds: Long
) {
    val durationText = formatRecordingDuration(runtimeDurationSeconds)
    val distanceText = String.format(
        Locale.getDefault(),
        "%.2f км",
        session.totalDistanceMeters / 1_000.0
    )
    Text(
        when (session.status) {
            RecordingStatus.RECORDING -> "Запись активна"
            RecordingStatus.PAUSED -> "Запись приостановлена"
            RecordingStatus.FINISHED -> "Маршрут сохранён"
            RecordingStatus.IDLE -> "Подготовка"
        },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        StatValue("Время", durationText)
        StatValue("Расстояние", distanceText)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        StatValue("GPS-точки", pointCount.toString())
        StatValue("Остановки", stopCount.toString())
        StatValue("Точность", accuracy?.let { "±" + it.roundToInt() + " м" } ?: "Ожидание")
    }
    Text(
        locationStatusText(runtimeStatus),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun StatValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FinishedStops(stops: List<RecordingStop>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Остановки",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        if (stops.isEmpty()) {
            Text(
                "Во время записи остановки не добавлялись.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            stops.forEach { stop ->
                Text(
                    (stop.sequence + 1).toString() + ". " + stop.name,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun PermissionInfoDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit = onConfirm
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 12.dp,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

private enum class RecordingPermissionDialog {
    NONE,
    LOCATION_EXPLANATION,
    LOCATION_DENIED,
    LOCATION_SETTINGS,
    NOTIFICATION_EXPLANATION,
    NOTIFICATION_DENIED,
    NOTIFICATION_SETTINGS
}

private fun hasForegroundLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

private fun openApplicationSettings(activity: Activity) {
    activity.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        }
    )
}

private tailrec fun Context.findRecordingActivity(): Activity =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findRecordingActivity()
        else -> error("RecordingScreen requires an Activity context")
    }

private fun locationStatusText(state: RecordingRuntimeState): String =
    when (state.locationStatus) {
        is LocationProviderStatus.ProviderAvailable ->
            state.lastSample?.accuracyMeters?.let {
                "Геолокация доступна · точность ±" + it.roundToInt() + " м"
            } ?: "Геолокация доступна. Ожидается измерение."
        LocationProviderStatus.WaitingForSignal ->
            "Ожидается сигнал GPS. Запись продолжится при появлении измерений."
        LocationProviderStatus.ProviderDisabled ->
            "Геолокация отключена. Включите её в настройках устройства."
        LocationProviderStatus.PermissionMissing ->
            "Разрешение геолокации отозвано. Запись не получает новые координаты."
        is LocationProviderStatus.Error ->
            state.locationStatus.message
    }

private fun formatRecordingDuration(seconds: Long): String {
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val remainingSeconds = seconds % 60
    return String.format(
        Locale.getDefault(),
        "%02d:%02d:%02d",
        hours,
        minutes,
        remainingSeconds
    )
}
