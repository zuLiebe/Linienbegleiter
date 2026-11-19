package com.example.linienbegleiter

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import java.util.UUID
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.example.linienbegleiter.data.routes.RouteZone
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.linienbegleiter.data.local.RecordingDatabase
import com.example.linienbegleiter.data.recording.RecordingRepository
import com.example.linienbegleiter.data.recording.RecordingStatus
import com.example.linienbegleiter.data.recording.RoomRecordingRepository
import com.example.linienbegleiter.data.routes.EditableRouteDetails
import com.example.linienbegleiter.data.routes.EditableRouteKind
import com.example.linienbegleiter.data.routes.EditableRouteDirection
import com.example.linienbegleiter.data.routes.EditableRoutePoint
import com.example.linienbegleiter.data.routes.EditableRouteRepository
import com.example.linienbegleiter.data.routes.EditableRouteStop
import com.example.linienbegleiter.data.routes.RouteCoordinate
import com.example.linienbegleiter.data.routes.RouteDraftStop
import com.example.linienbegleiter.data.routes.RouteFollowSource
import com.example.linienbegleiter.data.routes.RouteSectionSelection
import com.example.linienbegleiter.data.routes.createRouteDirections
import com.example.linienbegleiter.data.routes.createRouteSectionEditSession
import com.example.linienbegleiter.data.routes.deleteRouteSection
import com.example.linienbegleiter.data.routes.directionDescription
import com.example.linienbegleiter.data.routes.initialBearingDegrees
import com.example.linienbegleiter.data.routes.mergeRouteSections
import com.example.linienbegleiter.data.routes.routeCoordinateAtDistance
import com.example.linienbegleiter.data.routes.routeTotalDistanceMeters
import com.example.linienbegleiter.data.routes.selectRouteSection
import com.example.linienbegleiter.data.routes.RoomEditableRouteRepository
import com.example.linienbegleiter.data.routes.uniqueRouteTitle
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng

private enum class RouteEditorMode {
    SELECT,
    MOVE_POINT,
    INSERT_POINT,
    EDIT_SECTION,
    ADD_SECTION,
    ADD_STOP,
    MOVE_STOP,
    ZONES
}

private data class RouteEditorDraft(
    val title: String,
    val points: List<EditableRoutePoint>,
    val stops: List<RouteDraftStop>,
    val routeColorArgb: Int,
    val routeLineWidth: Float
)

@Composable
private fun RouteEditorDockButton(
    icon: String,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .size(44.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
            }
            .clickable(enabled = enabled, onClick = onClick),
        shape = CircleShape,
        color = when {
            !enabled -> colors.surfaceVariant.copy(alpha = 0.55f)
            selected -> colors.primaryContainer
            else -> colors.surface
        },
        contentColor = when {
            !enabled -> colors.onSurface.copy(alpha = 0.38f)
            selected -> colors.onPrimaryContainer
            else -> colors.onSurface
        },
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(icon, style = MaterialTheme.typography.titleMedium, fontSize = 22.sp)
        }
    }
}

@Composable
fun RouteEditorScreen(
    modifier: Modifier = Modifier,
    routeId: String,
    source: RouteFollowSource,
    onCameraPositionChanged: (CameraPosition) -> Unit,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val database = remember(context) { RecordingDatabase.getInstance(context) }
    val recordingRepository: RecordingRepository = remember(database) {
        RoomRecordingRepository(database)
    }
    val routeRepository: EditableRouteRepository = remember(database) {
        RoomEditableRouteRepository(database)
    }
    val scope = rememberCoroutineScope()

    var draft by remember(routeId, source) { mutableStateOf<RouteEditorDraft?>(null) }
    var baselineDraft by remember(routeId, source) { mutableStateOf<RouteEditorDraft?>(null) }
    var editableRouteId by remember(routeId, source) {
        mutableStateOf<String?>(null)
    }
    var originalTrackId by remember(routeId, source) { mutableStateOf<String?>(null) }
    var originalComparisonPoints by remember(routeId, source) {
        mutableStateOf<List<MapRoutePoint>>(emptyList())
    }
    var routeKind by remember(routeId, source) { mutableStateOf(EditableRouteKind.MANUAL) }
    var routeMetadataJson by remember(routeId, source) { mutableStateOf<String?>(null) }
    val details by routeRepository.observeDetails(routeId).collectAsState(initial = null)
    var isLoading by remember(routeId, source) { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember(routeId, source) { mutableStateOf<String?>(null) }
    var editorStatusMessage by remember(routeId, source) { mutableStateOf<String?>(null) }
    var showExitConfirmation by remember { mutableStateOf(false) }
    var showRouteDetails by remember(routeId, source) { mutableStateOf(false) }
    var mode by remember(routeId, source) { mutableStateOf(RouteEditorMode.SELECT) }
    var selectedPointSequence by remember { mutableStateOf<Int?>(null) }
    var selectedRouteSection by remember(routeId, source) {
        mutableStateOf<RouteSectionSelection?>(null)
    }
    var previewSectionPoints by remember(routeId, source) {
        mutableStateOf<List<EditableRoutePoint>?>(null)
    }
    var lastSectionDragTarget by remember(routeId, source) {
        mutableStateOf<RouteCoordinate?>(null)
    }
    var selectedStopIndex by remember { mutableStateOf<Int?>(null) }
    var stopNameDialog by remember { mutableStateOf(false) }
    var stopName by remember { mutableStateOf("") }
    var pendingStopPosition by remember { mutableStateOf<LatLng?>(null) }
    var editingStopIndex by remember { mutableStateOf<Int?>(null) }
    var routeDirections by remember(routeId, source) {
        mutableStateOf<List<EditableRouteDirection>>(emptyList())
    }
    var selectedDirectionStartIndex by remember(routeId, source) { mutableIntStateOf(0) }
    var includeReverseDirection by remember(routeId, source) { mutableStateOf(true) }
    var hasConfirmedDirection by remember(routeId, source) { mutableStateOf(false) }
    var showDirectionDialog by remember { mutableStateOf(false) }
    var isDirectionDirty by remember(routeId, source) { mutableStateOf(false) }

    var showZoneDialog by remember { mutableStateOf(false) }
    var zoneTitle by remember { mutableStateOf("") }
    var zoneTargetZoom by remember { mutableFloatStateOf(18.0f) }
    var zoneRadiusMeters by remember { mutableDoubleStateOf(80.0) }
    var pendingZoneCenter by remember { mutableStateOf<LatLng?>(null) }
    var editingZoneId by remember { mutableStateOf<String?>(null) }

    val undoHistory = remember(routeId, source) { mutableStateListOf<RouteEditorDraft>() }
    val redoHistory = remember(routeId, source) { mutableStateListOf<RouteEditorDraft>() }
    val isDirty = (draft != null && baselineDraft != null && draft != baselineDraft) ||
        isDirectionDirty

    LaunchedEffect(routeId, source, recordingRepository, routeRepository) {
        isLoading = true
        errorMessage = null
        try {
            val initialDraft = when (source) {
                RouteFollowSource.ORIGINAL_RECORDING -> {
                    routeDirections = emptyList()
                    hasConfirmedDirection = false
                    selectedDirectionStartIndex = 0
                    includeReverseDirection = true
                    val recording = requireNotNull(recordingRepository.getDetails(routeId)) {
                        "GPS-запись не найдена."
                    }
                    check(recording.session.status == RecordingStatus.FINISHED) {
                        "Сначала завершите GPS-запись, затем её можно будет редактировать."
                    }
                    originalTrackId = null
                    originalComparisonPoints = recording.points.mapIndexed { index, point ->
                        MapRoutePoint(index, point.latitude, point.longitude)
                    }
                    routeKind = EditableRouteKind.MANUAL
                    routeMetadataJson = null
                    RouteEditorDraft(
                        title = database.uniqueRouteTitle(recording.session.title),
                        points = recording.points.mapIndexed { index, point ->
                            EditableRoutePoint(index, point.latitude, point.longitude)
                        },
                        stops = recording.stops.map { stop ->
                            RouteDraftStop(
                                routePointSequence = null,
                                name = stop.name,
                                latitude = stop.latitude,
                                longitude = stop.longitude
                            )
                        },
                        routeColorArgb = recording.session.routeColorArgb,
                        routeLineWidth = recording.session.routeLineWidth
                    )
                }

                RouteFollowSource.EDITABLE_ROUTE -> {
                    val route = requireNotNull(routeRepository.getDetails(routeId)) {
                        "Маршрут не найден."
                    }
                    originalTrackId = null
                    originalComparisonPoints = route.points.mapIndexed { index, point ->
                        MapRoutePoint(index, point.latitude, point.longitude)
                    }
                    routeKind = when (route.route.routeKind) {
                        EditableRouteKind.GTFS_IMPORT -> EditableRouteKind.GTFS_IMPORT
                        EditableRouteKind.FILE_IMPORT -> EditableRouteKind.FILE_IMPORT
                        else -> EditableRouteKind.MANUAL
                    }
                    routeMetadataJson = route.route.routeMetadataJson
                    routeDirections = route.directions
                    hasConfirmedDirection = route.directions.isNotEmpty()
                    includeReverseDirection = route.directions.size > 1
                    selectedDirectionStartIndex = route.directions.firstOrNull()
                        ?.startStopId
                        ?.let { startId ->
                            route.stops.indexOfFirst { it.id == startId }
                        }
                        ?.coerceAtLeast(0)
                        ?: 0
                    fromDetails(route).copy(
                        title = database.uniqueRouteTitle(route.route.title)
                    )
                }
            }
            draft = initialDraft
            baselineDraft = initialDraft
            if (routeDirections.isEmpty() && initialDraft.stops.size >= 2) {
                showDirectionDialog = true
            }
            selectedPointSequence = null
            selectedRouteSection = null
            previewSectionPoints = null
            selectedStopIndex = null
            undoHistory.clear()
            redoHistory.clear()
        } catch (exception: Exception) {
            errorMessage = exception.message ?: "Не удалось открыть маршрут."
        } finally {
            isLoading = false
        }
    }

    fun applyEdit(transform: (RouteEditorDraft) -> RouteEditorDraft) {
        val current = draft ?: return
        undoHistory += current
        if (undoHistory.size > 50) undoHistory.removeAt(0)
        redoHistory.clear()
        val updated = transform(current).normalized()
        if (updated.stops != current.stops) hasConfirmedDirection = false
        draft = updated
        errorMessage = null
        editorStatusMessage = null
    }

    fun requestExit() {
        if (isDirty) showExitConfirmation = true else onBack()
    }

    fun saveRoute() {
        val current = draft ?: return
        if (isSaving) return
        if (current.stops.size >= 2 && !hasConfirmedDirection) {
            showDirectionDialog = true
            return
        }
        scope.launch {
            isSaving = true
            try {
                val existingId = editableRouteId
                val initiallySaved = if (existingId == null) {
                    routeRepository.createRoute(
                        title = current.title,
                        createdAtEpochMillis = System.currentTimeMillis(),
                        points = current.points,
                        stops = current.stops,
                        routeKind = routeKind,
                        routeColorArgb = current.routeColorArgb,
                        routeLineWidth = current.routeLineWidth,
                        routeMetadataJson = routeMetadataJson
                    )
                } else {
                    routeRepository.updateRoute(
                        routeId = existingId,
                        title = current.title,
                        points = current.points,
                        stops = current.stops,
                        routeColorArgb = current.routeColorArgb,
                        routeLineWidth = current.routeLineWidth,
                        routeMetadataJson = routeMetadataJson
                    )
                }
                val saved = if (initiallySaved.stops.size >= 2 && hasConfirmedDirection) {
                    val directionStops = initiallySaved.stops.mapIndexed { index, stop ->
                        EditableRouteStop(
                            sequence = index,
                            routePointSequence = stop.routePointSequence,
                            name = stop.name,
                            latitude = stop.latitude,
                            longitude = stop.longitude,
                            id = stop.id
                        )
                    }
                    val directions = createRouteDirections(
                        routeId = initiallySaved.route.id,
                        stops = directionStops,
                        startStopIndex = selectedDirectionStartIndex
                            .coerceIn(directionStops.indices),
                        includeReverse = includeReverseDirection
                    )
                    routeRepository.saveDirections(initiallySaved.route.id, directions)
                } else {
                    initiallySaved
                }
                routeDirections = saved.directions
                editableRouteId = saved.route.id
                routeKind = saved.route.routeKind
                val savedDraft = fromDetails(saved)
                draft = savedDraft
                baselineDraft = savedDraft
                routeDirections = saved.directions
                isDirectionDirty = false
                undoHistory.clear()
                redoHistory.clear()
                onSaved()
            } catch (exception: Exception) {
                errorMessage = exception.message ?: "Не удалось сохранить изменения."
            } finally {
                isSaving = false
            }
        }
    }

    BackHandler(enabled = true) { requestExit() }

    val currentDraft = draft
    if (currentDraft != null) {
        val routePoints = currentDraft.points.mapIndexed { index, point ->
            MapRoutePoint(index, point.latitude, point.longitude)
        }
        val selectedSectionEditSession = remember(currentDraft.points, selectedRouteSection) {
            selectedRouteSection?.let {
                createRouteSectionEditSession(currentDraft.points, it)
            }
        }
        val selectedSectionCoordinates = selectedSectionEditSession
            ?.originalSectionPoints
            .orEmpty()
            .mapIndexed { index, point ->
                MapRoutePoint(index, point.latitude, point.longitude)
            }
        val previewSectionCoordinates = previewSectionPoints
            .orEmpty()
            .mapIndexed { index, point ->
                MapRoutePoint(index, point.latitude, point.longitude)
            }
        val selectedSectionCenter = selectedRouteSection
            ?.let { routeCoordinateAtDistance(currentDraft.points, it.centerDistanceMeters) }
            ?.let { LatLng(it.latitude, it.longitude) }
        val directionEndStopIndex = if (currentDraft.stops.isEmpty()) {
            null
        } else if (selectedDirectionStartIndex == 0) {
            currentDraft.stops.lastIndex
        } else {
            (selectedDirectionStartIndex - 1).coerceAtLeast(0)
        }
        val routeStops = currentDraft.stops.mapIndexed { index, stop ->
            MapRouteStop(
                sequence = index,
                name = stop.name,
                latitude = stop.latitude,
                longitude = stop.longitude,
                id = stop.id.takeIf { it > 0L },
                isStart = currentDraft.stops.size >= 2 &&
                    index == selectedDirectionStartIndex,
                isEnd = currentDraft.stops.size >= 2 && index == directionEndStopIndex,
                isSelected = index == selectedStopIndex
            )
        }
        val camera = currentDraft.points.firstOrNull()?.let { point ->
            CameraPosition.Builder()
                .target(LatLng(point.latitude, point.longitude))
                .zoom(14.5)
                .build()
        }

        Column(modifier = modifier.fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = ::requestExit) { Text("‹") }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Редактор маршрута", style = MaterialTheme.typography.labelMedium)
                        Text(
                            currentDraft.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    TextButton(
                        onClick = ::saveRoute,
                        enabled = !isSaving && currentDraft.points.size >= 2
                    ) { Text(if (isSaving) "Сохр…" else "Сохранить") }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                MapScreen(
                    modifier = Modifier.fillMaxSize(),
                    initialCameraPosition = camera,
                    onCameraPositionChanged = onCameraPositionChanged,
                    onBack = ::requestExit,
                    routePoints = routePoints,
                    routeStops = routeStops,
                    routeZones = details?.zones ?: emptyList(),
                    routeColorArgb = currentDraft.routeColorArgb,
                    routeLineWidth = currentDraft.routeLineWidth,
                    originalComparisonPoints = originalComparisonPoints,
                    selectedRoutePointSequence = selectedPointSequence,
                    showRoutePointMarkers = mode != RouteEditorMode.EDIT_SECTION,
                    onRoutePointTap = if (mode == RouteEditorMode.SELECT) {
                        { sequence -> selectedPointSequence = sequence }
                    } else {
                        null
                    },
                    onRouteSegmentTap = when (mode) {
                        RouteEditorMode.INSERT_POINT -> { hit ->
                            applyEdit { value ->
                                val insertedSequence = hit.sequence + 1
                                val inserted = value.points.toMutableList().apply {
                                    add(
                                        insertedSequence.coerceIn(0, size),
                                        EditableRoutePoint(
                                            sequence = insertedSequence,
                                            latitude = hit.position.latitude,
                                            longitude = hit.position.longitude
                                        )
                                    )
                                }
                                value.copy(points = inserted)
                            }
                            selectedPointSequence = hit.sequence + 1
                            mode = RouteEditorMode.SELECT
                        }
                        RouteEditorMode.EDIT_SECTION -> { hit ->
                            val newSelection = selectRouteSection(
                                points = currentDraft.points,
                                segmentSequence = hit.sequence,
                                segmentFraction = hit.fraction
                            )
                            if (newSelection != null) {
                                val totalDistance = routeTotalDistanceMeters(currentDraft.points)
                                selectedRouteSection = mergeRouteSections(
                                    existing = selectedRouteSection,
                                    newSelection = newSelection,
                                    totalDistanceMeters = totalDistance
                                )
                            }
                            previewSectionPoints = null
                            lastSectionDragTarget = null
                            errorMessage = null
                        }
                        else -> null
                    },
                    selectedRouteSectionCenter = if (mode == RouteEditorMode.EDIT_SECTION) {
                        selectedSectionCenter
                    } else {
                        null
                    },
                    selectedRouteSectionPoints = if (mode == RouteEditorMode.EDIT_SECTION) {
                        selectedSectionCoordinates
                    } else {
                        emptyList()
                    },
                    previewRouteSectionPoints = if (mode == RouteEditorMode.EDIT_SECTION) {
                        previewSectionCoordinates
                    } else {
                        emptyList()
                    },
                    onRouteSectionDrag = if (
                        mode == RouteEditorMode.EDIT_SECTION && selectedSectionEditSession != null
                    ) {
                        { position ->
                            val target = RouteCoordinate(position.latitude, position.longitude)
                            lastSectionDragTarget = target
                            previewSectionPoints = selectedSectionEditSession.preview(target)
                        }
                    } else {
                        null
                    },
                    onRouteSectionDragEnd = if (mode == RouteEditorMode.EDIT_SECTION) {
                        {
                            val movedPoints = lastSectionDragTarget
                                ?.let { target -> selectedSectionEditSession?.commit(target) }
                            previewSectionPoints = null
                            lastSectionDragTarget = null
                            if (movedPoints != null && movedPoints != currentDraft.points) {
                                applyEdit { value -> value.copy(points = movedPoints) }
                            }
                        }
                    } else {
                        null
                    },
                    onRouteSectionDragCancel = if (mode == RouteEditorMode.EDIT_SECTION) {
                        {
                            previewSectionPoints = null
                            lastSectionDragTarget = null
                        }
                    } else {
                        null
                    },
                    onMapTap = { position ->
                        when (mode) {
                            RouteEditorMode.MOVE_POINT -> {
                                val selected = selectedPointSequence
                                if (selected == null) {
                                    errorMessage = "Сначала выберите точку маршрута."
                                } else {
                                    applyEdit { value ->
                                        value.copy(
                                            points = value.points.mapIndexed { index, point ->
                                                if (index == selected) {
                                                    point.copy(
                                                        latitude = position.latitude,
                                                        longitude = position.longitude
                                                    )
                                                } else point
                                            }
                                        )
                                    }
                                    mode = RouteEditorMode.SELECT
                                }
                            }

                            RouteEditorMode.INSERT_POINT -> {
                                errorMessage = "Коснитесь линии маршрута, чтобы добавить точку."
                            }

                            RouteEditorMode.EDIT_SECTION -> {
                                errorMessage = "Коснитесь линии маршрута, чтобы выбрать участок."
                            }

                            RouteEditorMode.ADD_SECTION -> {
                                applyEdit { value ->
                                    value.copy(
                                        points = value.points + EditableRoutePoint(
                                            sequence = value.points.size,
                                            latitude = position.latitude,
                                            longitude = position.longitude
                                        )
                                    )
                                }
                            }

                            RouteEditorMode.ADD_STOP -> {
                                pendingStopPosition = position
                                stopName = ""
                                editingStopIndex = null
                                stopNameDialog = true
                                mode = RouteEditorMode.SELECT
                            }

                            RouteEditorMode.MOVE_STOP -> {
                                val index = selectedStopIndex
                                if (index == null || index !in currentDraft.stops.indices) {
                                    errorMessage = "Сначала выберите остановку из списка."
                                } else {
                                    applyEdit { value ->
                                        value.copy(
                                            stops = value.stops.mapIndexed { stopIndex, stop ->
                                                if (stopIndex == index) {
                                                    stop.copy(
                                                        latitude = position.latitude,
                                                        longitude = position.longitude
                                                    )
                                                } else stop
                                            }
                                        )
                                    }
                                    mode = RouteEditorMode.SELECT
                                }
                            }

                            RouteEditorMode.ZONES -> {
                                pendingZoneCenter = position
                                editingZoneId = null
                                val currentDetails = details
                                zoneTitle = "Детализированная зона " + ((currentDetails?.zones?.size ?: 0) + 1)
                                zoneTargetZoom = 18.0f
                                zoneRadiusMeters = 80.0
                                showZoneDialog = true
                            }

                            RouteEditorMode.SELECT -> Unit
                        }
                    },
                    showChrome = false
                )

                Surface(
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    tonalElevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier.padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        RouteEditorDockButton(
                            icon = "↔",
                            label = if (mode == RouteEditorMode.EDIT_SECTION) {
                                "Завершить изменение участка"
                            } else {
                                "Изменить участок"
                            },
                            selected = mode == RouteEditorMode.EDIT_SECTION
                        ) {
                            if (mode == RouteEditorMode.EDIT_SECTION) {
                                mode = RouteEditorMode.SELECT
                                selectedRouteSection = null
                                previewSectionPoints = null
                                lastSectionDragTarget = null
                            } else {
                                mode = RouteEditorMode.EDIT_SECTION
                                selectedPointSequence = null
                                selectedRouteSection = null
                                previewSectionPoints = null
                                lastSectionDragTarget = null
                            }
                            errorMessage = null
                        }
                        RouteEditorDockButton(
                            icon = "−",
                            label = "Удалить выбранный участок",
                            enabled = mode == RouteEditorMode.EDIT_SECTION &&
                                selectedRouteSection != null
                        ) {
                            val shortenedPoints = selectedRouteSection?.let { selection ->
                                deleteRouteSection(currentDraft.points, selection)
                            }
                            if (shortenedPoints == null) {
                                errorMessage = "Нельзя удалить участок: маршрут должен содержать минимум две точки."
                            } else if (shortenedPoints == currentDraft.points) {
                                errorMessage = "Выбранный участок нельзя сократить."
                            } else {
                                applyEdit { value -> value.copy(points = shortenedPoints) }
                                selectedRouteSection = null
                                previewSectionPoints = null
                                lastSectionDragTarget = null
                                selectedPointSequence = null
                                errorMessage = null
                                editorStatusMessage =
                                    "Участок удалён. «Отменить» вернёт прежнюю геометрию."
                            }
                        }
                        if (mode == RouteEditorMode.EDIT_SECTION && selectedRouteSection != null) {
                            RouteEditorDockButton(
                                icon = "✕",
                                label = "Снять выделение участка"
                            ) {
                                selectedRouteSection = null
                                previewSectionPoints = null
                                lastSectionDragTarget = null
                                errorMessage = null
                            }
                        }
                        RouteEditorDockButton(
                            icon = "+",
                            label = "Вставить точку в линию",
                            selected = mode == RouteEditorMode.INSERT_POINT
                        ) {
                            mode = RouteEditorMode.INSERT_POINT
                            selectedRouteSection = null
                            previewSectionPoints = null
                            errorMessage = null
                        }
                        RouteEditorDockButton(
                            icon = "→",
                            label = "Продолжить маршрут",
                            selected = mode == RouteEditorMode.ADD_SECTION
                        ) {
                            mode = RouteEditorMode.ADD_SECTION
                            selectedRouteSection = null
                            previewSectionPoints = null
                            errorMessage = null
                        }
                        RouteEditorDockButton(
                            icon = "H",
                            label = "Добавить остановку",
                            selected = mode == RouteEditorMode.ADD_STOP
                        ) {
                            mode = RouteEditorMode.ADD_STOP
                            selectedRouteSection = null
                            previewSectionPoints = null
                            errorMessage = null
                        }
                        RouteEditorDockButton(
                            icon = "🔍",
                            label = "Детализированные зоны",
                            selected = mode == RouteEditorMode.ZONES
                        ) {
                            mode = RouteEditorMode.ZONES
                            selectedRouteSection = null
                            previewSectionPoints = null
                            errorMessage = null
                        }
                        RouteEditorDockButton(
                            icon = "⌖",
                            label = "Переместить выбранную точку",
                            selected = mode == RouteEditorMode.MOVE_POINT,
                            enabled = selectedPointSequence != null
                        ) {
                            mode = RouteEditorMode.MOVE_POINT
                            selectedRouteSection = null
                            previewSectionPoints = null
                            errorMessage = null
                        }
                    }
                }

                Surface(
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    tonalElevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier.padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        RouteEditorDockButton(
                            icon = "↶",
                            label = "Отменить последнее действие",
                            enabled = undoHistory.isNotEmpty()
                        ) {
                            if (undoHistory.isNotEmpty()) {
                                redoHistory += currentDraft
                                draft = undoHistory.removeAt(undoHistory.lastIndex)
                                selectedPointSequence = null
                                selectedRouteSection = null
                                previewSectionPoints = null
                                lastSectionDragTarget = null
                                errorMessage = null
                                editorStatusMessage = null
                            }
                        }
                        RouteEditorDockButton(
                            icon = "↷",
                            label = "Повторить действие",
                            enabled = redoHistory.isNotEmpty()
                        ) {
                            if (redoHistory.isNotEmpty()) {
                                undoHistory += currentDraft
                                draft = redoHistory.removeAt(redoHistory.lastIndex)
                                selectedPointSequence = null
                                selectedRouteSection = null
                                previewSectionPoints = null
                                lastSectionDragTarget = null
                                errorMessage = null
                                editorStatusMessage = null
                            }
                        }
                        RouteEditorDockButton(
                            icon = "⌫",
                            label = "Удалить выбранную промежуточную точку",
                            enabled = selectedPointSequence != null &&
                                selectedPointSequence != 0 &&
                                selectedPointSequence != currentDraft.points.lastIndex
                        ) {
                            val selected = selectedPointSequence
                            if (selected == null || selected == 0 ||
                                selected == currentDraft.points.lastIndex
                            ) {
                                errorMessage = "Можно удалить только промежуточную точку."
                            } else {
                                applyEdit { value ->
                                    value.copy(
                                        points = value.points.filterIndexed { index, _ ->
                                            index != selected
                                        }
                                    )
                                }
                                selectedPointSequence = null
                            }
                        }
                        RouteEditorDockButton(
                            icon = "⋯",
                            label = "Параметры маршрута и остановки"
                        ) { showRouteDetails = true }
                    }
                }

                val editorHint = errorMessage ?: editorStatusMessage ?: when (mode) {
                    RouteEditorMode.EDIT_SECTION -> if (selectedRouteSection == null) {
                        "Участок: коснитесь линии маршрута для выделения."
                    } else {
                        val length = selectedRouteSection?.let {
                            (it.endDistanceMeters - it.startDistanceMeters).toInt()
                        } ?: 0
                        "Участок выбран ($length м) · Нажмите на другие участки для объединения, перетащите ручку или удалите."
                    }
                    RouteEditorMode.MOVE_POINT -> "Коснитесь карты, чтобы перенести выбранную точку."
                    RouteEditorMode.INSERT_POINT -> "Коснитесь линии, чтобы вставить точку."
                    RouteEditorMode.ADD_SECTION -> "Коснитесь карты, чтобы продолжить маршрут."
                    RouteEditorMode.ADD_STOP -> "Коснитесь карты, чтобы поставить остановку."
                    RouteEditorMode.MOVE_STOP -> "Коснитесь карты, чтобы перенести остановку."
                    RouteEditorMode.ZONES -> "Коснитесь карты, чтобы добавить детализированную зону."
                    RouteEditorMode.SELECT -> if (selectedPointSequence == null) {
                        "Выберите инструмент на боковой панели."
                    } else {
                        "Выбрана точка " + (selectedPointSequence!! + 1) + "."
                    }
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 62.dp, end = 62.dp, bottom = 8.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    tonalElevation = 3.dp
                ) {
                    Text(
                        editorHint,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (errorMessage != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (showRouteDetails) {
                AlertDialog(
                    onDismissRequest = { showRouteDetails = false },
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 12.dp,
                    shape = RoundedCornerShape(28.dp),
                    title = { Text("Маршрут и остановки") },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 580.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                OutlinedTextField(
                    value = currentDraft.title,
                    onValueChange = { title -> applyEdit { it.copy(title = title) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Название маршрута") }
                )
                Text("Цвет и толщина линии", style = MaterialTheme.typography.titleSmall)
                RouteAppearanceControls(
                    colorArgb = currentDraft.routeColorArgb,
                    lineWidth = currentDraft.routeLineWidth,
                    onColorSelected = { color ->
                        draft = currentDraft.copy(routeColorArgb = color)
                    },
                    onLineWidthChanged = { width ->
                        draft = currentDraft.copy(routeLineWidth = width)
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Направление движения",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (currentDraft.stops.size >= 2) {
                        TextButton(onClick = {
                            selectedDirectionStartIndex = selectedDirectionStartIndex
                                .coerceIn(currentDraft.stops.indices)
                            showDirectionDialog = true
                        }) { Text("Настроить") }
                    }
                }
                val displayedDirection = routeDirections.firstOrNull()
                if (displayedDirection != null) {
                    Text(
                        directionDescription(displayedDirection.initialBearingDegrees),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (currentDraft.stops.size >= 2) {
                    val startIndex = selectedDirectionStartIndex
                        .coerceIn(currentDraft.stops.indices)
                    val endIndex = if (startIndex == 0) {
                        currentDraft.stops.lastIndex
                    } else {
                        startIndex - 1
                    }
                    val start = currentDraft.stops[startIndex]
                    val end = currentDraft.stops[endIndex]
                    val bearing = initialBearingDegrees(
                        start.latitude,
                        start.longitude,
                        end.latitude,
                        end.longitude
                    )
                    Text(
                        directionDescription(bearing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Остановки (" + currentDraft.stops.size + ")",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium
                    )
                    TextButton(
                        onClick = {
                            mode = RouteEditorMode.ADD_STOP
                            showRouteDetails = false
                        }
                    ) {
                        Text("＋ Добавить")
                    }
                }

                if (currentDraft.stops.isEmpty()) {
                    Text(
                        "Остановок пока нет. Добавьте остановку и укажите её место на карте.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                currentDraft.stops.forEachIndexed { index, stop ->
                    val selected = selectedStopIndex == index
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable { selectedStopIndex = index },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            }
                        ),
                        border = BorderStroke(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    },
                                    contentColor = if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = (index + 1).toString(),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Text(
                                    text = stop.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )

                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            applyEdit { value ->
                                                value.copy(
                                                    stops = value.stops.toMutableList().apply {
                                                        add(index - 1, removeAt(index))
                                                    }
                                                )
                                            }
                                            selectedStopIndex = index - 1
                                        }
                                    },
                                    enabled = index > 0,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text("↑", style = MaterialTheme.typography.titleMedium)
                                }

                                IconButton(
                                    onClick = {
                                        if (index < currentDraft.stops.lastIndex) {
                                            applyEdit { value ->
                                                value.copy(
                                                    stops = value.stops.toMutableList().apply {
                                                        add(index + 1, removeAt(index))
                                                    }
                                                )
                                            }
                                            selectedStopIndex = index + 1
                                        }
                                    },
                                    enabled = index < currentDraft.stops.lastIndex,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text("↓", style = MaterialTheme.typography.titleMedium)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        stopName = stop.name
                                        selectedStopIndex = index
                                        editingStopIndex = index
                                        pendingStopPosition = null
                                        stopNameDialog = true
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("✏️ Имя", fontSize = 12.sp)
                                }

                                OutlinedButton(
                                    onClick = {
                                        selectedStopIndex = index
                                        mode = RouteEditorMode.MOVE_STOP
                                        showRouteDetails = false
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("📍 Перенести", fontSize = 12.sp)
                                }

                                OutlinedButton(
                                    onClick = {
                                        applyEdit { value ->
                                            value.copy(
                                                stops = value.stops.filterIndexed { stopIndex, _ ->
                                                    stopIndex != index
                                                }
                                            )
                                        }
                                        selectedStopIndex = null
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("🗑 Удалить", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                if (errorMessage != null) {
                    Text(
                        errorMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showRouteDetails = false }) {
                            Text("Готово")
                        }
                    }
                )
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(if (isLoading) "Загрузка маршрута…" else "Не удалось открыть редактор")
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onBack) { Text("Назад") }
        }
    }

    if (showExitConfirmation) {
        AlertDialog(
            onDismissRequest = { showExitConfirmation = false },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 12.dp,
            shape = RoundedCornerShape(28.dp),
            title = { Text("Несохранённые изменения") },
            text = { Text("Сохранить изменения перед выходом из редактора?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirmation = false
                        saveRoute()
                    }
                ) { Text("Сохранить") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            showExitConfirmation = false
                            onBack()
                        }
                    ) { Text("Выйти без сохранения") }
                    TextButton(onClick = { showExitConfirmation = false }) {
                        Text("Остаться")
                    }
                }
            }
        )
    }

    val directionDraft = draft
    if (showDirectionDialog && directionDraft != null && directionDraft.stops.size >= 2) {
        val stopsForDirection = directionDraft.stops
        val terminalIndex = if (selectedDirectionStartIndex == 0) {
            stopsForDirection.lastIndex
        } else {
            (selectedDirectionStartIndex - 1).coerceAtLeast(0)
        }
        val startStop = stopsForDirection.getOrNull(
            selectedDirectionStartIndex.coerceIn(stopsForDirection.indices)
        )
        val endStop = stopsForDirection.getOrNull(terminalIndex)
        val previewBearing = if (startStop != null && endStop != null) {
            initialBearingDegrees(
                startStop.latitude,
                startStop.longitude,
                endStop.latitude,
                endStop.longitude
            )
        } else null

        AlertDialog(
            onDismissRequest = { showDirectionDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 12.dp,
            shape = RoundedCornerShape(28.dp),
            title = { Text("С какой остановки начинается маршрут?") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    stopsForDirection.forEachIndexed { index, stop ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedDirectionStartIndex == index,
                                onClick = { selectedDirectionStartIndex = index }
                            )
                            TextButton(
                                onClick = { selectedDirectionStartIndex = index }
                            ) {
                                Text((index + 1).toString() + ". " + stop.name)
                            }
                        }
                    }
                    if (previewBearing != null) {
                        Text(directionDescription(previewBearing))
                    }
                    Checkbox(
                        checked = includeReverseDirection,
                        onCheckedChange = { includeReverseDirection = it }
                    )
                    Text("Сохранить также обратное направление")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        hasConfirmedDirection = true
                        isDirectionDirty = true
                        showDirectionDialog = false
                    }
                ) { Text("Подтвердить") }
            },
            dismissButton = {
                TextButton(onClick = { showDirectionDialog = false }) {
                    Text("Позже")
                }
            }
        )
    }

    if (stopNameDialog) {
        AlertDialog(
            onDismissRequest = {
                stopNameDialog = false
                pendingStopPosition = null
            },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 12.dp,
            shape = RoundedCornerShape(28.dp),
            title = {
                Text(if (pendingStopPosition != null) "Новая остановка" else "Название остановки")
            },
            text = {
                OutlinedTextField(
                    value = stopName,
                    onValueChange = { stopName = it },
                    singleLine = true,
                    label = { Text("Название") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val position = pendingStopPosition
                        val editIndex = editingStopIndex
                        val cleanName = stopName.trim()
                        if (cleanName.isEmpty()) {
                            errorMessage = "Введите название остановки."
                        } else if (position != null) {
                            applyEdit { value ->
                                value.copy(
                                    stops = value.stops + RouteDraftStop(
                                        routePointSequence = null,
                                        name = cleanName,
                                        latitude = position.latitude,
                                        longitude = position.longitude
                                    )
                                )
                            }
                            selectedStopIndex = draft?.stops?.lastIndex
                            stopNameDialog = false
                            pendingStopPosition = null
                        } else if (editIndex != null) {
                            applyEdit { value ->
                                value.copy(
                                    stops = value.stops.mapIndexed { index, stop ->
                                        if (index == editIndex) stop.copy(name = cleanName)
                                        else stop
                                    }
                                )
                            }
                            stopNameDialog = false
                        } else {
                            errorMessage = "Выберите остановку из списка."
                        }
                    }
                ) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        stopNameDialog = false
                        pendingStopPosition = null
                    }
                ) { Text("Отмена") }
            }
        )
    }

    if (showZoneDialog) {
        AlertDialog(
            onDismissRequest = { showZoneDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 12.dp,
            shape = RoundedCornerShape(28.dp),
            title = { Text("Детализированная зона") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Настройка крупного масштаба для сложного участка (вокзалы, развязки).")
                    OutlinedTextField(
                        value = zoneTitle,
                        onValueChange = { zoneTitle = it },
                        label = { Text("Название зоны") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Целевой масштаб (Zoom): ${"%.1f".format(zoneTargetZoom)}")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { zoneTargetZoom = (zoneTargetZoom - 0.5f).coerceAtLeast(17.0f) }) {
                            Text("−0.5", fontWeight = FontWeight.Bold)
                        }
                        Text("${"%.1f".format(zoneTargetZoom)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { zoneTargetZoom = (zoneTargetZoom + 0.5f).coerceAtMost(19.5f) }) {
                            Text("+0.5", fontWeight = FontWeight.Bold)
                        }
                    }
                    Text("Радиус охвата зоны: ${zoneRadiusMeters.toInt()} м")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { zoneRadiusMeters = (zoneRadiusMeters - 20.0).coerceAtLeast(30.0) }) {
                            Text("−20м", fontWeight = FontWeight.Bold)
                        }
                        Text("${zoneRadiusMeters.toInt()} м", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { zoneRadiusMeters = (zoneRadiusMeters + 20.0).coerceAtMost(300.0) }) {
                            Text("+20м", fontWeight = FontWeight.Bold)
                        }
                    }

                    val currentDetails = details
                    if (currentDetails?.zones?.isNotEmpty() == true) {
                        Text("Существующие зоны:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        currentDetails.zones.forEach { existingZone ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🔍 ${existingZone.title} (${existingZone.targetZoom}x)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    scope.launch {
                                        routeRepository.deleteZone(routeId, existingZone.id)
                                    }
                                }) {
                                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = zoneTitle.isNotBlank() && pendingZoneCenter != null,
                    onClick = {
                        val center = pendingZoneCenter
                        if (center != null) {
                            val zone = RouteZone(
                                id = editingZoneId ?: UUID.randomUUID().toString(),
                                routeId = routeId,
                                title = zoneTitle.trim(),
                                centerLatitude = center.latitude,
                                centerLongitude = center.longitude,
                                radiusMeters = zoneRadiusMeters,
                                targetZoom = zoneTargetZoom
                            )
                            scope.launch {
                                routeRepository.saveZone(routeId, zone)
                                showZoneDialog = false
                                pendingZoneCenter = null
                                mode = RouteEditorMode.SELECT
                            }
                        }
                    }
                ) {
                    Text("Сохранить зону")
                }
            },
            dismissButton = {
                TextButton(onClick = { showZoneDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

private fun fromDetails(details: EditableRouteDetails) = RouteEditorDraft(
    title = details.route.title,
    points = details.points,
    stops = details.stops.map { stop ->
        RouteDraftStop(
            routePointSequence = stop.routePointSequence,
            name = stop.name,
            latitude = stop.latitude,
            longitude = stop.longitude,
            id = stop.id
        )
    },
    routeColorArgb = details.route.routeColorArgb,
    routeLineWidth = details.route.routeLineWidth
)

private fun RouteEditorDraft.normalized() = copy(
    points = points.mapIndexed { index, point -> point.copy(sequence = index) },
    stops = stops.map { stop -> stop.copy(routePointSequence = null) }
)
