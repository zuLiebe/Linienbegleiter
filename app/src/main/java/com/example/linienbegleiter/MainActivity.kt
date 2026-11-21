package com.example.linienbegleiter

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.WindowManager
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.linienbegleiter.data.local.RecordingDatabase
import com.example.linienbegleiter.data.recording.RecordingRepository
import com.example.linienbegleiter.data.recording.RoomRecordingRepository
import com.example.linienbegleiter.data.routes.EditableRouteRepository
import com.example.linienbegleiter.data.routes.RouteFollowSource
import com.example.linienbegleiter.data.routes.RoomEditableRouteRepository
import com.example.linienbegleiter.service.RouteFollowingService
import com.example.linienbegleiter.ui.theme.HamburgTransitPalette
import com.example.linienbegleiter.ui.theme.LinienbegleiterTheme
import kotlinx.coroutines.flow.flowOf
import org.maplibre.android.camera.CameraPosition

private enum class AppScreen {
    ROUTES,
    SETTINGS,
    IMPORT_EXPORT,
    MAP,
    CREATE,
    ADD_POINTS,
    DRAW,
    RECORDING,
    FOLLOWING,
    EDITOR
}

private enum class MainTab {
    MAP,
    ROUTES,
    CREATE,
    NAVIGATION,
    SETTINGS
}

private data class RecentRouteSummary(
    val title: String,
    val savedAtEpochMillis: Long
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var themeMode by remember { mutableStateOf(AppPreferences.themeMode(this@MainActivity)) }
            var autoDarkTheme by remember { mutableStateOf(false) }

            val sensorManager = remember(this@MainActivity) {
                getSystemService(Context.SENSOR_SERVICE) as SensorManager
            }
            val lightSensor = remember(sensorManager) {
                sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
            }

            DisposableEffect(sensorManager, lightSensor) {
                if (lightSensor == null) return@DisposableEffect onDispose {}

                var pendingTarget: Boolean? = null
                var debounceJob: kotlinx.coroutines.Job? = null
                val coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)

                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val lux = event.values.firstOrNull()?.coerceAtLeast(0f) ?: return

                        // Hysteresis calculation:
                        // Switch to Dark only if lux < 10.0lx
                        // Switch to Light only if lux > 25.0lx
                        val desiredDark = when {
                            lux < 10.0f -> true
                            lux > 25.0f -> false
                            else -> pendingTarget ?: autoDarkTheme
                        }

                        if (desiredDark != autoDarkTheme) {
                            if (pendingTarget != desiredDark) {
                                pendingTarget = desiredDark
                                debounceJob?.cancel()
                                debounceJob = coroutineScope.launch {
                                    kotlinx.coroutines.delay(40_000L) // 40s debounce delay before switching theme
                                    autoDarkTheme = desiredDark
                                    pendingTarget = null
                                }
                            }
                        } else {
                            debounceJob?.cancel()
                            debounceJob = null
                            pendingTarget = null
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }

                sensorManager.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
                onDispose {
                    debounceJob?.cancel()
                    sensorManager.unregisterListener(listener)
                }
            }

            val darkTheme = when (themeMode) {
                AppThemeMode.DAY -> false
                AppThemeMode.NIGHT -> true
                AppThemeMode.AUTO -> if (lightSensor != null) autoDarkTheme else false
            }
            LinienbegleiterTheme(darkTheme = darkTheme) {
                LinienbegleiterApp(
                    themeMode = themeMode,
                    darkTheme = darkTheme,
                    onThemeModeChanged = { mode ->
                        themeMode = mode
                        AppPreferences.setThemeMode(this@MainActivity, mode)
                    }
                )
            }
        }
    }
}

@Composable
private fun LinienbegleiterApp(
    themeMode: AppThemeMode,
    darkTheme: Boolean,
    onThemeModeChanged: (AppThemeMode) -> Unit
) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(AppScreen.MAP) }
    var importExportReturnScreen by remember { mutableStateOf(AppScreen.SETTINGS) }
    var selectedMainTab by remember { mutableStateOf(MainTab.MAP) }
    var keepScreenOn by remember { mutableStateOf(AppPreferences.keepScreenOn(context)) }
    val shouldKeepScreenOn = keepScreenOn && screen in setOf(AppScreen.MAP, AppScreen.RECORDING, AppScreen.FOLLOWING)
    DisposableEffect(shouldKeepScreenOn) {
        val window = (context as? Activity)?.window
        if (window != null) {
            if (shouldKeepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    var mapCameraPosition by remember { mutableStateOf<CameraPosition?>(null) }
    var recordingId by remember { mutableStateOf<String?>(null) }
    var followingRouteId by remember { mutableStateOf<String?>(null) }
    var followingRouteSource by remember { mutableStateOf(RouteFollowSource.ORIGINAL_RECORDING) }
    var followingDirectionId by remember { mutableStateOf<String?>(null) }
    var editorRouteId by remember { mutableStateOf<String?>(null) }
    var editorRouteSource by remember {
        mutableStateOf(RouteFollowSource.ORIGINAL_RECORDING)
    }

    var showFirstRunLanguageDialog by remember { mutableStateOf(!AppPreferences.hasSelectedLanguage(context)) }

    if (showFirstRunLanguageDialog) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 12.dp,
            shape = RoundedCornerShape(28.dp),
            title = { Text("Choose Language / Выберите язык") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Please select your preferred language:", style = MaterialTheme.typography.bodyMedium)
                    AppPreferences.SUPPORTED_LANGUAGES.forEach { (code, nativeName, englishName) ->
                        OutlinedButton(
                            onClick = {
                                AppPreferences.setAppLanguage(context, code)
                                showFirstRunLanguageDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(nativeName, fontWeight = FontWeight.Bold)
                                Text(englishName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    BackHandler(
        enabled = screen == AppScreen.RECORDING ||
            screen == AppScreen.FOLLOWING ||
            screen == AppScreen.IMPORT_EXPORT
    ) {
        screen = when (screen) {
            AppScreen.FOLLOWING -> AppScreen.ROUTES
            AppScreen.IMPORT_EXPORT -> importExportReturnScreen
            else -> AppScreen.MAP
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (screen == AppScreen.MAP || screen == AppScreen.ROUTES || screen == AppScreen.SETTINGS || screen == AppScreen.CREATE) {
                MainNavigationBar(
                    selectedTab = selectedMainTab,
                    onTabSelected = { tab ->
                        selectedMainTab = tab
                        if (tab == MainTab.NAVIGATION) {
                            val activeFollowing = RouteFollowingService.savedFollowingRoute(context)
                            if (activeFollowing != null) {
                                followingRouteId = activeFollowing.routeId
                                followingRouteSource = activeFollowing.source
                                followingDirectionId = activeFollowing.directionId
                                screen = AppScreen.FOLLOWING
                            } else {
                                screen = AppScreen.ROUTES
                            }
                        } else {
                            screen = when (tab) {
                                MainTab.MAP -> AppScreen.MAP
                                MainTab.ROUTES -> AppScreen.ROUTES
                                MainTab.CREATE -> AppScreen.CREATE
                                MainTab.NAVIGATION -> AppScreen.ROUTES
                                MainTab.SETTINGS -> AppScreen.SETTINGS
                            }
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        when (screen) {
            AppScreen.ROUTES -> SavedRoutesScreen(
                modifier = Modifier.padding(innerPadding),
                onBack = { screen = AppScreen.MAP },
                onOpenImportExport = {
                    importExportReturnScreen = AppScreen.ROUTES
                    screen = AppScreen.IMPORT_EXPORT
                },
                onCreateRoute = { screen = AppScreen.CREATE },
                onOpenRecording = { id ->
                    recordingId = id
                    screen = AppScreen.RECORDING
                },
                onFollowRoute = { id, source ->
                    selectedMainTab = MainTab.NAVIGATION
                    followingRouteId = id
                    followingRouteSource = source
                    followingDirectionId = null
                    screen = AppScreen.FOLLOWING
                },
                onEditRoute = { id, source ->
                    editorRouteId = id
                    editorRouteSource = source
                    screen = AppScreen.EDITOR
                }
            )

            AppScreen.SETTINGS -> SettingsScreen(
                modifier = Modifier.padding(innerPadding),
                onOpenImportExport = {
                    importExportReturnScreen = AppScreen.SETTINGS
                    screen = AppScreen.IMPORT_EXPORT
                },
                onBack = { screen = AppScreen.MAP },
                themeMode = themeMode,
                onThemeModeChanged = onThemeModeChanged,
                keepScreenOn = keepScreenOn,
                onKeepScreenOnChanged = { enabled ->
                    keepScreenOn = enabled
                    AppPreferences.setKeepScreenOn(context, enabled)
                }
            )

            AppScreen.IMPORT_EXPORT -> ImportExportScreen(
                modifier = Modifier.padding(innerPadding),
                onBack = { screen = importExportReturnScreen }
            )

            AppScreen.MAP -> androidx.compose.runtime.key(themeMode) {
                MapScreen(
                modifier = Modifier.padding(innerPadding),
                mapStyleUrl = if (darkTheme) OPENFREEMAP_DARK_STYLE else OPENFREEMAP_LIBERTY_STYLE,
                initialCameraPosition = mapCameraPosition,
                onCameraPositionChanged = { mapCameraPosition = it },
                onBack = {},
                onOpenRoutes = { selectedMainTab = MainTab.ROUTES; screen = AppScreen.ROUTES },
                onOpenSettings = { selectedMainTab = MainTab.SETTINGS; screen = AppScreen.SETTINGS },
                onRecordRoute = {
                    recordingId = null
                    screen = AppScreen.RECORDING
                },
                onFollowRoute = { id, source ->
                    selectedMainTab = MainTab.NAVIGATION
                    followingRouteId = id
                    followingRouteSource = source
                    followingDirectionId = null
                    screen = AppScreen.FOLLOWING
                }
            )
            }

            AppScreen.CREATE -> CreateRouteScreen(
                modifier = Modifier.padding(innerPadding),
                onBack = { screen = AppScreen.MAP },
                onAddPoints = { screen = AppScreen.ADD_POINTS },
                onDraw = { screen = AppScreen.DRAW }
            )

            AppScreen.ADD_POINTS -> EditableRouteCreationScreen(
                modifier = Modifier.padding(innerPadding),
                initialCameraPosition = mapCameraPosition,
                onCameraPositionChanged = { mapCameraPosition = it },
                onBack = { screen = AppScreen.CREATE },
                onRouteSaved = { id ->
                    editorRouteId = id
                    editorRouteSource = RouteFollowSource.EDITABLE_ROUTE
                    screen = AppScreen.EDITOR
                }
            )

            AppScreen.DRAW -> MapModePlaceholderScreen(
                modifier = Modifier.padding(innerPadding),
                title = "Рисовать на карте",
                description = "Инструмент рисования пути появится позже. Общую карту можно открыть с главного экрана.",
                onBack = { screen = AppScreen.CREATE }
            )

            AppScreen.RECORDING -> RecordingScreen(
                modifier = Modifier.padding(innerPadding),
                initialCameraPosition = mapCameraPosition,
                onCameraPositionChanged = { mapCameraPosition = it },
                recordingId = recordingId,
                onRecordingIdChanged = { recordingId = it },
                onBack = { screen = AppScreen.MAP }
            )

            AppScreen.FOLLOWING -> RouteFollowingScreen(
                modifier = Modifier.padding(innerPadding),
                initialCameraPosition = mapCameraPosition,
                onCameraPositionChanged = { mapCameraPosition = it },
                routeId = followingRouteId.orEmpty(),
                source = followingRouteSource,
                initialDirectionId = followingDirectionId,
                onBack = { screen = AppScreen.ROUTES }
            )

            AppScreen.EDITOR -> RouteEditorScreen(
                modifier = Modifier.padding(innerPadding),
                routeId = editorRouteId.orEmpty(),
                source = editorRouteSource,
                onCameraPositionChanged = { mapCameraPosition = it },
                onBack = { screen = AppScreen.ROUTES },
                onSaved = { screen = AppScreen.ROUTES }
            )
        }
    }
}

private fun AppScreen.isMainTab(): Boolean =
    this == AppScreen.MAP || this == AppScreen.ROUTES || this == AppScreen.SETTINGS

private fun AppScreen.toMainTab(): MainTab = when (this) {
    AppScreen.ROUTES -> MainTab.ROUTES
    AppScreen.SETTINGS -> MainTab.SETTINGS
    AppScreen.CREATE, AppScreen.ADD_POINTS -> MainTab.CREATE
    AppScreen.FOLLOWING -> MainTab.NAVIGATION
    else -> MainTab.MAP
}

@Composable
private fun MainNavigationBar(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit
) {
    NavigationBar {
        NavigationBarItem(
            selected = selectedTab == MainTab.MAP,
            onClick = { onTabSelected(MainTab.MAP) },
            icon = { Text("⌖", fontSize = 22.sp) },
            label = { Text("Карта", maxLines = 1) }
        )
        NavigationBarItem(
            selected = selectedTab == MainTab.ROUTES,
            onClick = { onTabSelected(MainTab.ROUTES) },
            icon = { Text("▤", fontSize = 20.sp) },
            label = { Text("Маршруты") }
        )
        NavigationBarItem(
            selected = selectedTab == MainTab.CREATE,
            onClick = { onTabSelected(MainTab.CREATE) },
            icon = { Text("＋", fontSize = 22.sp) },
            label = { Text("Создать", maxLines = 1) }
        )
        NavigationBarItem(
            selected = selectedTab == MainTab.NAVIGATION,
            onClick = { onTabSelected(MainTab.NAVIGATION) },
            icon = { Text("➤", fontSize = 20.sp) },
            label = { Text("Навигация", maxLines = 1) }
        )
        NavigationBarItem(
            selected = selectedTab == MainTab.SETTINGS,
            onClick = { onTabSelected(MainTab.SETTINGS) },
            icon = { Text("⚙", fontSize = 20.sp) },
            label = { Text("Настройки") }
        )
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenImportExport: () -> Unit,
    onBack: () -> Unit,
    themeMode: AppThemeMode,
    onThemeModeChanged: (AppThemeMode) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOnChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var preferredZoom by remember { mutableFloatStateOf(AppPreferences.preferredZoom(context)) }
    var autoFollow by remember { mutableStateOf(AppPreferences.autoFollow(context)) }
    var showLocation by remember { mutableStateOf(AppPreferences.showLocation(context)) }
    var snapToRoad by remember { mutableStateOf(AppPreferences.editorSnapToRoad(context)) }
    Column(
        modifier.fillMaxSize()
            .background(HamburgTransitPalette.LightSurface)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(HamburgTransitPalette.HarborNavy)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("‹", style = MaterialTheme.typography.headlineMedium, color = HamburgTransitPalette.White)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Настройки",
                    style = MaterialTheme.typography.headlineSmall,
                    color = HamburgTransitPalette.White,
                    fontWeight = FontWeight.Bold
                )
                Text("Карта, экран и редактор", color = HamburgTransitPalette.NightMuted)
            }
        }
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFFCCD6E2)),
            colors = CardDefaults.cardColors(containerColor = HamburgTransitPalette.White)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Экран и освещение", style = MaterialTheme.typography.titleMedium, color = HamburgTransitPalette.HarborNavy)
                TextButton(onClick = { onThemeModeChanged(when (themeMode) { AppThemeMode.AUTO -> AppThemeMode.DAY; AppThemeMode.DAY -> AppThemeMode.NIGHT; AppThemeMode.NIGHT -> AppThemeMode.AUTO }) }) {
                    Text("Режим: ${when (themeMode) { AppThemeMode.AUTO -> "Авто (датчик)"; AppThemeMode.DAY -> "День"; AppThemeMode.NIGHT -> "Ночь" }} · изменить")
                }
                TextButton(onClick = { onKeepScreenOnChanged(!keepScreenOn) }) { Text("Не выключать экран: ${if (keepScreenOn) "включено" else "выключено"}") }
            }
        }
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFFCCD6E2)),
            colors = CardDefaults.cardColors(containerColor = HamburgTransitPalette.White)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var showLangDialog by remember { mutableStateOf(false) }
                val currentLang = AppPreferences.appLanguage(context)
                val langName = AppPreferences.SUPPORTED_LANGUAGES.firstOrNull { it.first == currentLang }?.second ?: "English"

                Text("Язык / Language", style = MaterialTheme.typography.titleMedium, color = HamburgTransitPalette.HarborNavy)
                TextButton(onClick = { showLangDialog = true }) {
                    Text("Текущий язык: $langName · Изменить")
                }

                if (showLangDialog) {
                    AlertDialog(
                        onDismissRequest = { showLangDialog = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 12.dp,
                        shape = RoundedCornerShape(28.dp),
                        title = { Text("Выберите язык / Choose Language") },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 380.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                AppPreferences.SUPPORTED_LANGUAGES.forEach { (code, nativeName, englishName) ->
                                    OutlinedButton(
                                        onClick = {
                                            AppPreferences.setAppLanguage(context, code)
                                            showLangDialog = false
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(nativeName, fontWeight = FontWeight.Bold)
                                            Text(englishName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showLangDialog = false }) { Text("Закрыть") }
                        }
                    )
                }
            }
        }
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFFCCD6E2)),
            colors = CardDefaults.cardColors(containerColor = HamburgTransitPalette.White)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Карта", style = MaterialTheme.typography.titleMedium, color = HamburgTransitPalette.HarborNavy)
                TextButton(onClick = { autoFollow = !autoFollow; AppPreferences.setAutoFollow(context, autoFollow) }) { Text("Автоследование: ${if (autoFollow) "включено" else "выключено"}") }
                TextButton(onClick = { showLocation = !showLocation; AppPreferences.setShowLocation(context, showLocation) }) { Text("GPS-маркер: ${if (showLocation) "показывать" else "скрывать"}") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { preferredZoom = (preferredZoom - 1f).coerceAtLeast(10f); AppPreferences.setPreferredZoom(context, preferredZoom) }) { Text("−") }
                    Text("Масштаб ${preferredZoom.toInt()}")
                    TextButton(onClick = { preferredZoom = (preferredZoom + 1f).coerceAtMost(19f); AppPreferences.setPreferredZoom(context, preferredZoom) }) { Text("+") }
                }
            }
        }
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFFCCD6E2)),
            colors = CardDefaults.cardColors(containerColor = HamburgTransitPalette.White)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var offRouteThreshold by remember { mutableFloatStateOf(AppPreferences.offRouteThresholdMeters(context)) }
                Text("Порог схода с маршрута", style = MaterialTheme.typography.titleMedium, color = HamburgTransitPalette.HarborNavy)
                Text("Оповещать при отклонении от линии маршрута больше заданной дистанции.", style = MaterialTheme.typography.bodySmall, color = HamburgTransitPalette.MutedGraphite)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = {
                            val newValue = (offRouteThreshold - AppPreferences.OFF_ROUTE_THRESHOLD_STEP_METERS)
                                .coerceAtLeast(AppPreferences.MIN_OFF_ROUTE_THRESHOLD_METERS)
                            offRouteThreshold = newValue
                            AppPreferences.setOffRouteThresholdMeters(context, newValue)
                        }
                    ) { Text("− 5 м", fontSize = 16.sp, fontWeight = FontWeight.Bold) }

                    Text(
                        "${offRouteThreshold.toInt()} м",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = HamburgTransitPalette.HarborNavy
                    )

                    TextButton(
                        onClick = {
                            val newValue = (offRouteThreshold + AppPreferences.OFF_ROUTE_THRESHOLD_STEP_METERS)
                                .coerceAtMost(AppPreferences.MAX_OFF_ROUTE_THRESHOLD_METERS)
                            offRouteThreshold = newValue
                            AppPreferences.setOffRouteThresholdMeters(context, newValue)
                        }
                    ) { Text("+ 5 м", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                }
                Text("Мин: 10 м · По умолчанию: 25 м · Макс: 100 м", style = MaterialTheme.typography.labelSmall, color = HamburgTransitPalette.MutedGraphite)
            }
        }
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFFCCD6E2)),
            colors = CardDefaults.cardColors(containerColor = HamburgTransitPalette.White)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Элементы управления (Кнопки HUD)", style = MaterialTheme.typography.titleMedium, color = HamburgTransitPalette.HarborNavy)
                Text("Включение и отключение элементов на карте", style = MaterialTheme.typography.bodySmall, color = HamburgTransitPalette.MutedGraphite)

                var showZoom by remember { mutableStateOf(AppPreferences.showZoomControls(context)) }
                var show3d by remember { mutableStateOf(AppPreferences.show3dControls(context)) }
                var showCompass by remember { mutableStateOf(AppPreferences.showCompass(context)) }
                var showSpeed by remember { mutableStateOf(AppPreferences.showSpeedBadge(context)) }

                TextButton(onClick = {
                    showZoom = !showZoom
                    AppPreferences.setShowZoomControls(context, showZoom)
                }) { Text("Кнопки зума (+/−): ${if (showZoom) "включено" else "выключено"}") }

                TextButton(onClick = {
                    show3d = !show3d
                    AppPreferences.setShow3dControls(context, show3d)
                }) { Text("Кнопка 3D / 2D: ${if (show3d) "включено" else "выключено"}") }

                TextButton(onClick = {
                    showCompass = !showCompass
                    AppPreferences.setShowCompass(context, showCompass)
                }) { Text("Компас (ориентация): ${if (showCompass) "включено" else "выключено"}") }

                TextButton(onClick = {
                    showSpeed = !showSpeed
                    AppPreferences.setShowSpeedBadge(context, showSpeed)
                }) { Text("Индикатор скорости: ${if (showSpeed) "включено" else "выключено"}") }
            }
        }
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFFCCD6E2)),
            colors = CardDefaults.cardColors(containerColor = HamburgTransitPalette.White)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Анализ вождения (Eco-Drive)", style = MaterialTheme.typography.titleMedium, color = HamburgTransitPalette.HarborNavy)
                var drivingAnalysis by remember { mutableStateOf(AppPreferences.drivingAnalysisEnabled(context)) }
                TextButton(onClick = {
                    drivingAnalysis = !drivingAnalysis
                    AppPreferences.setDrivingAnalysisEnabled(context, drivingAnalysis)
                }) {
                    Text("Глобальный анализ: ${if (drivingAnalysis) "включено (работает в фоне)" else "выключено"}")
                }
                Text("Оценивает плавность, скорость и рейтинг поездок независимо от экрана.", style = MaterialTheme.typography.bodySmall, color = HamburgTransitPalette.MutedGraphite)
            }
        }
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFFCCD6E2)),
            colors = CardDefaults.cardColors(containerColor = HamburgTransitPalette.White)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Редактор", style = MaterialTheme.typography.titleMedium, color = HamburgTransitPalette.HarborNavy)
                TextButton(onClick = { snapToRoad = !snapToRoad; AppPreferences.setEditorSnapToRoad(context, snapToRoad) }) { Text("Привязка к дороге: ${if (snapToRoad) "включена" else "выключена"}") }
                Text("Маршрутизатор дорожной сети пока не подключён.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(
            onClick = onOpenImportExport,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = HamburgTransitPalette.ElbeBlue,
                contentColor = HamburgTransitPalette.White
            )
        ) { Text("Импорт и экспорт маршрутов") }
    }
}

@Composable
private fun CreateRouteScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onAddPoints: () -> Unit,
    onDraw: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        ScreenHeading(
            title = "Новый маршрут",
            subtitle = "Выберите, как задать его путь.",
            onBack = onBack
        )
        ChoiceCard(
            symbol = "＋",
            title = "Добавить точки на карте",
            description = "Последовательно задать точки пути и отдельно добавить остановки.",
            onClick = onAddPoints
        )
        ChoiceCard(
            symbol = "〰",
            title = "Рисовать пальцем по карте",
            description = "Набросать линию маршрута вручную.",
            onClick = onDraw
        )
        Text(
            text = "Новый маршрут можно задать точками на карте. Инструмент рисования пальцем появится позже.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MapModePlaceholderScreen(
    modifier: Modifier = Modifier,
    title: String,
    description: String,
    onBack: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        ScreenHeading(title = title, onBack = onBack)
        EmptyStateCard(
            title = "Этот экран появится позже",
            description = description,
            symbol = "⌖"
        )
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text("Назад")
        }
    }
}

@Composable
private fun ScreenHeading(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (onBack != null) {
            Text(
                text = "‹   Назад",
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChoiceCard(
    symbol: String,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = symbol,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyStateCard(
    title: String,
    description: String,
    symbol: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = symbol,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
