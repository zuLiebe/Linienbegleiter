package com.example.linienbegleiter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.linienbegleiter.data.local.RecordingDatabase
import com.example.linienbegleiter.data.routes.EditableRoutePoint
import com.example.linienbegleiter.data.routes.RoomEditableRouteRepository
import com.example.linienbegleiter.data.routes.uniqueRouteTitle
import com.example.linienbegleiter.data.routes.RouteColorPalette
import com.example.linienbegleiter.data.routes.RouteDraftStop
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import kotlin.math.cos
import kotlin.math.hypot

@Composable
fun EditableRouteCreationScreen(
    modifier: Modifier = Modifier,
    initialCameraPosition: CameraPosition?,
    onCameraPositionChanged: (CameraPosition) -> Unit,
    onBack: () -> Unit,
    onRouteSaved: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val database = remember(context) { RecordingDatabase.getInstance(context) }
    val repository = remember(database) { RoomEditableRouteRepository(database) }
    val coroutineScope = rememberCoroutineScope()

    var title by remember { mutableStateOf("Новый маршрут") }

    LaunchedEffect(database) {
        title = database.uniqueRouteTitle(title)
    }
    var pathPoints by remember { mutableStateOf(emptyList<EditableRoutePoint>()) }
    var directionPoints by remember { mutableStateOf(emptyList<MapRoutePoint>()) }
    var drawingDirection by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var stops by remember { mutableStateOf(emptyList<RouteDraftStop>()) }
    var inputMode by remember { mutableStateOf(RouteInputMode.PATH) }
    var stopName by remember { mutableStateOf("") }
    var pendingStop by remember { mutableStateOf<PendingRouteStop?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var selectedRouteColor by remember { mutableStateOf<Int?>(null) }
    var routeLineWidth by remember { mutableStateOf(5f) }
    val routeCreationSeed = remember { System.currentTimeMillis().toString() }
    val automaticColor = remember(title, routeCreationSeed) {
        RouteColorPalette.automaticFor("$title-$routeCreationSeed")
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val mapRoutePoints = pathPoints.mapIndexed { index, point ->
        MapRoutePoint(index, point.latitude, point.longitude)
    }
    val orderedStops = stops.sortedWith(
        compareBy<RouteDraftStop> { it.routePointSequence }
            .thenBy { stops.indexOf(it) }
    )
    val mapRouteStops = orderedStops.mapIndexed { index, stop ->
        MapRouteStop(index, stop.name, stop.latitude, stop.longitude)
    }

    Box(modifier = modifier.fillMaxSize()) {
        MapScreen(
            modifier = Modifier.fillMaxSize(),
            initialCameraPosition = initialCameraPosition,
            onCameraPositionChanged = onCameraPositionChanged,
            onBack = {},
            routePoints = mapRoutePoints,
            routeStops = mapRouteStops,
            directionDrawingEnabled = drawingDirection,
            directionPoints = directionPoints,
            onDirectionDrawn = { points ->
                drawingDirection = false
                directionPoints = points.mapIndexed { i, point -> point.copy(sequence = i) }
                errorMessage = if (points.size >= 2) "Направление задано (${points.size} точек)." else "Нарисуйте линию длиннее."
            },
            routeColorArgb = selectedRouteColor ?: automaticColor,
            routeLineWidth = routeLineWidth,
            onMapTap = { position ->
                errorMessage = null
                if (inputMode == RouteInputMode.PATH) {
                    val guideProgress = directionGuideProgress(position.latitude, position.longitude, directionPoints)
                    val previousProgress = pathPoints.lastOrNull()?.let {
                        directionGuideProgress(it.latitude, it.longitude, directionPoints)
                    }
                    if (guideProgress != null && previousProgress != null && guideProgress < previousProgress) {
                        errorMessage = "Добавляйте точки по ходу заданного направления."
                        return@MapScreen
                    }
                    pathPoints = pathPoints + EditableRoutePoint(
                        sequence = pathPoints.size,
                        latitude = position.latitude,
                        longitude = position.longitude
                    )
                } else if (pathPoints.size < 2) {
                    errorMessage = "Сначала добавьте минимум две точки пути."
                } else {
                    val nearestSegment = findNearestRouteSegment(
                        position.latitude,
                        position.longitude,
                        pathPoints
                    )
                    if (nearestSegment == null) {
                        errorMessage = "Не удалось привязать остановку к линии пути."
                    } else if (
                        nearestSegment.distanceMeters > MAX_STOP_DISTANCE_FROM_ROUTE_METERS
                    ) {
                        errorMessage = "Коснитесь рядом с линией маршрута, чтобы поставить остановку."
                    } else {
                        pendingStop = PendingRouteStop(
                            routePointSequence = nearestSegment.attachmentVertexSequence,
                            latitude = position.latitude,
                            longitude = position.longitude
                        )
                        stopName = ""
                    }
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .padding(bottom = 52.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBack) { Text("‹") }
                    Text(
                        "Новый маршрут",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = inputMode == RouteInputMode.PATH && !drawingDirection, onClick = { showTools = true; inputMode = RouteInputMode.PATH; drawingDirection = false }, label = { Text("＋ ${pathPoints.size}") })
                FilterChip(selected = drawingDirection, onClick = { showTools = true; drawingDirection = true; inputMode = RouteInputMode.PATH }, label = { Text("↗") })
                FilterChip(selected = false, onClick = { showTools = !showTools }, label = { Text(if (showTools) "Скрыть инструменты" else "Инструменты · ${stops.size} остановок") })
            }

            if (showTools) Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 310.dp),
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
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = inputMode == RouteInputMode.PATH, onClick = { inputMode = RouteInputMode.PATH; drawingDirection = false }, label = { Text("＋") })
                        FilterChip(selected = inputMode == RouteInputMode.STOP, enabled = pathPoints.size >= 2, onClick = { inputMode = RouteInputMode.STOP; drawingDirection = false }, label = { Text("●") })
                        FilterChip(selected = drawingDirection, onClick = { drawingDirection = !drawingDirection; inputMode = RouteInputMode.PATH }, label = { Text("Указать направление") })
                        TextButton(onClick = { directionPoints = emptyList(); drawingDirection = false }) { Text("Очистить") }
                    }
                    Text(
                        "Точки пути добавляются касанием карты",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Название маршрута") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Цвет и вид линии", style = MaterialTheme.typography.titleSmall)
                    RouteAppearanceControls(
                        colorArgb = selectedRouteColor ?: automaticColor,
                        lineWidth = routeLineWidth,
                        onColorSelected = { selectedRouteColor = it },
                        onLineWidthChanged = { routeLineWidth = it },
                        allowAutomatic = true,
                        isAutomatic = selectedRouteColor == null,
                        onAutomaticSelected = { selectedRouteColor = null }
                    )
                    Text(
                        "Точек пути: ${pathPoints.size} · Остановок: ${stops.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = inputMode == RouteInputMode.PATH,
                            onClick = { inputMode = RouteInputMode.PATH },
                            label = { Text("Точка пути") }
                        )
                        FilterChip(
                            selected = inputMode == RouteInputMode.STOP,
                            enabled = pathPoints.size >= 2,
                            onClick = { inputMode = RouteInputMode.STOP },
                            label = { Text("Остановка") }
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(
                            enabled = pathPoints.isNotEmpty() && !isSaving,
                            onClick = {
                                val newSize = pathPoints.size - 1
                                pathPoints = pathPoints.take(newSize)
                                val lastSegmentStart = (newSize - 1).coerceAtLeast(0)
                                val removedCount = stops.count {
                                    it.routePointSequence?.let { sequence ->
                                        sequence >= lastSegmentStart
                                    } == true
                                }
                                stops = stops.filter {
                                    it.routePointSequence?.let { sequence ->
                                        sequence < lastSegmentStart
                                    } == true
                                }
                                if (newSize < 2) inputMode = RouteInputMode.PATH
                                errorMessage = if (removedCount > 0) {
                                    "Последняя точка и привязанные к ней остановки удалены."
                                } else {
                                    "Последняя точка удалена."
                                }
                            }
                        ) {
                            Text("Отменить")
                        }
                    }
                    Text(
                        if (inputMode == RouteInputMode.PATH) {
                            "Нажимайте по линии улиц, чтобы задать форму маршрута."
                        } else {
                            "Коснитесь рядом с линией маршрута, чтобы указать место остановки."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (stops.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Остановки",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            orderedStops.forEach { stop ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${(stop.routePointSequence ?: 0) + 1}. ${stop.name}",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    TextButton(
                                        enabled = !isSaving,
                                        onClick = { stops = stops - stop }
                                    ) {
                                        Text("Убрать")
                                    }
                                }
                            }
                        }
                    }
                    if (errorMessage != null) {
                        Text(
                            errorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onBack,
                            enabled = !isSaving,
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                        ) {
                            Text("Отмена")
                        }
                        Button(
                            onClick = {
                                isSaving = true
                                errorMessage = null
                                coroutineScope.launch {
                                    try {
                                        val saved = repository.createRoute(
                                            title = title,
                                            createdAtEpochMillis = System.currentTimeMillis(),
                                            points = pathPoints,
                                            stops = orderedStops,
                                            routeColorArgb = selectedRouteColor ?: automaticColor,
                                            routeLineWidth = routeLineWidth,
                                            routeMetadataJson = if (directionPoints.size >= 2) {
                                                org.json.JSONObject().put(
                                                    "directionGuide",
                                                    org.json.JSONArray().apply {
                                                        directionPoints.forEach { point ->
                                                            put(org.json.JSONArray().put(point.longitude).put(point.latitude))
                                                        }
                                                    }
                                                ).toString()
                                            } else null
                                        )
                                        onRouteSaved(saved.route.id)
                                    } catch (exception: Exception) {
                                        errorMessage = exception.message
                                            ?: "Не удалось сохранить маршрут."
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            },
                            enabled = !isSaving && title.isNotBlank() && pathPoints.size >= 2,
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                        ) {
                            Text(if (isSaving) "Сохраняем…" else "Сохранить маршрут")
                        }
                    }
                }
            }
        }
    }

    pendingStop?.let { requestedStop ->
        AlertDialog(
            onDismissRequest = { pendingStop = null },
            title = { Text("Добавить остановку") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Остановка будет отмечена на линии маршрута и сохранена отдельно от точек пути."
                    )
                    OutlinedTextField(
                        value = stopName,
                        onValueChange = { stopName = it },
                        label = { Text("Название остановки") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = stopName.isNotBlank(),
                    onClick = {
                        stops = stops + RouteDraftStop(
                            routePointSequence = requestedStop.routePointSequence,
                            name = stopName.trim(),
                            latitude = requestedStop.latitude,
                            longitude = requestedStop.longitude
                        )
                        pendingStop = null
                        inputMode = RouteInputMode.PATH
                    }
                ) {
                    Text("Добавить")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingStop = null }) {
                    Text("Отмена")
                }
            }
        )
    }
}

private enum class RouteInputMode {
    PATH,
    STOP
}

private data class PendingRouteStop(
    val routePointSequence: Int,
    val latitude: Double,
    val longitude: Double
)

private fun directionGuideProgress(
    latitude: Double,
    longitude: Double,
    guide: List<MapRoutePoint>
): Int? = guide.minByOrNull { point ->
    val dx = (point.longitude - longitude) * cos(Math.toRadians(latitude))
    val dy = point.latitude - latitude
    dx * dx + dy * dy
}?.sequence

private const val MAX_STOP_DISTANCE_FROM_ROUTE_METERS = 80.0
private const val EARTH_RADIUS_METERS = 6_371_000.0

private data class NearestRouteSegment(
    val attachmentVertexSequence: Int,
    val distanceMeters: Double
)

private fun findNearestRouteSegment(
    latitude: Double,
    longitude: Double,
    points: List<EditableRoutePoint>
): NearestRouteSegment? {
    if (points.size < 2) return null
    val metersPerDegree = Math.PI * EARTH_RADIUS_METERS / 180.0
    var nearest: NearestRouteSegment? = null

    for (index in 0 until points.lastIndex) {
        val start = points[index]
        val end = points[index + 1]
        val referenceLatitude = Math.toRadians((start.latitude + end.latitude) / 2.0)
        val scaleX = metersPerDegree * cos(referenceLatitude)
        val startToTapX = (longitude - start.longitude) * scaleX
        val startToTapY = (latitude - start.latitude) * metersPerDegree
        val segmentX = (end.longitude - start.longitude) * scaleX
        val segmentY = (end.latitude - start.latitude) * metersPerDegree
        val lengthSquared = segmentX * segmentX + segmentY * segmentY
        if (lengthSquared < 0.01) continue

        val fraction = (
            (startToTapX * segmentX + startToTapY * segmentY) / lengthSquared
            ).coerceIn(0.0, 1.0)
        val distance = hypot(
            startToTapX - segmentX * fraction,
            startToTapY - segmentY * fraction
        )
        if (nearest == null || distance < nearest.distanceMeters) {
            nearest = NearestRouteSegment(
                attachmentVertexSequence = if (fraction < 0.5) index else index + 1,
                distanceMeters = distance
            )
        }
    }
    return nearest
}
