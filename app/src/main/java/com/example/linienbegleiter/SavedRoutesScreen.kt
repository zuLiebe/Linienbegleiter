package com.example.linienbegleiter

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.linienbegleiter.data.local.RecordingDatabase
import com.example.linienbegleiter.data.recording.RecordingRepository
import com.example.linienbegleiter.data.recording.RecordingSession
import com.example.linienbegleiter.data.recording.RecordingStatus
import com.example.linienbegleiter.data.recording.RoomRecordingRepository
import com.example.linienbegleiter.data.routes.EditableRoute
import com.example.linienbegleiter.data.routes.EditableRouteRepository
import com.example.linienbegleiter.data.routes.RouteFollowSource
import com.example.linienbegleiter.data.routes.RoomEditableRouteRepository
import com.example.linienbegleiter.data.routes.repairDuplicateRouteTitles
import com.example.linienbegleiter.service.RouteFollowingService
import com.example.linienbegleiter.service.RouteRecordingService
import com.example.linienbegleiter.ui.theme.HamburgTransitPalette
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class RouteCatalogItem(
    val title: String,
    val createdAtEpochMillis: Long,
    val session: RecordingSession? = null,
    val route: EditableRoute? = null
)

private data class RouteAppearanceTarget(
    val id: String,
    val title: String,
    val isRecording: Boolean,
    val colorArgb: Int,
    val lineWidth: Float
)

@Composable
fun SavedRoutesScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenImportExport: () -> Unit,
    onCreateRoute: () -> Unit,
    onOpenRecording: (String) -> Unit,
    onFollowRoute: (String, RouteFollowSource) -> Unit,
    onEditRoute: (String, RouteFollowSource) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val database = remember(context) { RecordingDatabase.getInstance(context) }
    val repository: RecordingRepository = remember(database) {
        RoomRecordingRepository(database)
    }
    val sessions by remember(repository) {
        repository.observeSessions()
    }.collectAsState(initial = emptyList())
    val editableRouteRepository: EditableRouteRepository = remember(database) {
        RoomEditableRouteRepository(database)
    }
    val editableRoutes by remember(editableRouteRepository) {
        editableRouteRepository.observeRoutes()
    }.collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<EditableRoute?>(null) }
    var recordingDeleteTarget by remember { mutableStateOf<RecordingSession?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var appearanceTarget by remember { mutableStateOf<RouteAppearanceTarget?>(null) }
    var appearanceColor by remember { mutableIntStateOf(0xFFC62835.toInt()) }
    var appearanceLineWidth by remember { mutableStateOf(5f) }
    var searchQuery by remember { mutableStateOf("") }
    var newestFirst by remember { mutableStateOf(true) }
    val normalizedQuery = searchQuery.trim().lowercase(Locale.getDefault())
    LaunchedEffect(database) {
        database.repairDuplicateRouteTitles()
    }
    val catalogItems = (
        sessions.map { RouteCatalogItem(it.title, it.createdAtEpochMillis, session = it) } +
            editableRoutes.map { RouteCatalogItem(it.title, it.createdAtEpochMillis, route = it) }
        )
        .filter { normalizedQuery.isEmpty() || it.title.lowercase(Locale.getDefault()).contains(normalizedQuery) }
        .let { items ->
            if (newestFirst) items.sortedByDescending(RouteCatalogItem::createdAtEpochMillis)
            else items.sortedBy { it.title.lowercase(Locale.getDefault()) }
        }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(HamburgTransitPalette.LightSurface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
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
                Text("‹", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Мои маршруты",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    "${catalogItems.size} маршрутов",
                    style = MaterialTheme.typography.bodySmall,
                    color = HamburgTransitPalette.NightMuted
                )
            }
        }

        Button(
            onClick = onOpenImportExport,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = HamburgTransitPalette.ElbeBlue,
                contentColor = Color.White
            )
        ) {
            Text("Импорт / экспорт маршрутов", fontWeight = FontWeight.SemiBold)
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            label = { Text("Поиск маршрута") },
            leadingIcon = { Text("⌕", style = MaterialTheme.typography.titleLarge) }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Маршруты",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = HamburgTransitPalette.HarborNavy
            )
            TextButton(onClick = { newestFirst = !newestFirst }) {
                Text(if (newestFirst) "Сначала новые" else "По названию")
            }
        }

        if (sessions.isEmpty() && editableRoutes.isEmpty()) {
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
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Пока нет маршрутов",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Запишите маршрут по GPS или создайте его точками на карте.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        actionMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.primary)
        }

        catalogItems.forEach { item ->
            val session = item.session
            val route = item.route
            SavedRouteCard(
                item = item,
                onOpen = {
                    if (session != null) {
                        onOpenRecording(session.id)
                    } else if (route != null) {
                        onEditRoute(route.id, RouteFollowSource.EDITABLE_ROUTE)
                    }
                },
                onAppearance = {
                    val id = session?.id ?: route!!.id
                    appearanceTarget = RouteAppearanceTarget(
                        id = id,
                        title = item.title,
                        isRecording = session != null,
                        colorArgb = session?.routeColorArgb ?: route!!.routeColorArgb,
                        lineWidth = session?.routeLineWidth ?: route!!.routeLineWidth
                    )
                    appearanceColor = session?.routeColorArgb ?: route!!.routeColorArgb
                    appearanceLineWidth = session?.routeLineWidth ?: route!!.routeLineWidth
                },
                onFollow = {
                    if (session != null) {
                        onFollowRoute(session.id, RouteFollowSource.ORIGINAL_RECORDING)
                    } else if (route != null) {
                        onFollowRoute(route.id, RouteFollowSource.EDITABLE_ROUTE)
                    }
                },
                onEdit = {
                    if (session != null) {
                        onEditRoute(session.id, RouteFollowSource.ORIGINAL_RECORDING)
                    } else if (route != null) {
                        onEditRoute(route.id, RouteFollowSource.EDITABLE_ROUTE)
                    }
                },
                onDelete = {
                    if (session != null) recordingDeleteTarget = session
                    else deleteTarget = route
                }
            )
        }

        Button(
            onClick = onCreateRoute,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text("＋   Создать маршрут", fontWeight = FontWeight.SemiBold)
        }
    }

    recordingDeleteTarget?.let { session ->
        val isActive = session.status == RecordingStatus.RECORDING ||
            session.status == RecordingStatus.PAUSED
        AlertDialog(
            onDismissRequest = { recordingDeleteTarget = null },
            title = { Text("Удалить маршрут?") },
            text = {
                Text(
                    "Удалить «${session.title}» и все его данные?" +
                        if (isActive) " Запись будет безопасно остановлена перед удалением." else ""
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            try {
                                if (RouteFollowingService.savedFollowingRouteId(context) == session.id) {
                                    RouteFollowingService.stop(context)
                                    repeat(50) {
                                        if (RouteFollowingService.savedFollowingRouteId(context) == session.id) {
                                            delay(100L)
                                        }
                                    }
                                    check(RouteFollowingService.savedFollowingRouteId(context) != session.id) {
                                        "Не удалось остановить следование. Маршрут не удалён."
                                    }
                                }
                                if (isActive) {
                                    RouteRecordingService.stop(context)
                                    repeat(50) {
                                        val current = repository.getSession(session.id)
                                        if (current?.status == RecordingStatus.RECORDING ||
                                            current?.status == RecordingStatus.PAUSED
                                        ) delay(100L)
                                    }
                                    check(repository.getSession(session.id)?.status !in setOf(
                                        RecordingStatus.RECORDING,
                                        RecordingStatus.PAUSED
                                    )) { "Не удалось безопасно остановить запись. Маршрут не удалён." }
                                }
                                repository.deleteSession(session.id)
                                actionMessage = "Маршрут «${session.title}» удалён."
                            } catch (exception: Exception) {
                                actionMessage = exception.message ?: "Не удалось удалить маршрут."
                            }
                            recordingDeleteTarget = null
                        }
                    }
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { recordingDeleteTarget = null }) { Text("Отмена") }
            }
        )
    }

    deleteTarget?.let { route ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить маршрут?") },
            text = {
                Text("Удалить «${route.title}» и все данные этого маршрута?")
            },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch {
                        try {
                            if (RouteFollowingService.savedFollowingRouteId(context) == route.id) {
                                RouteFollowingService.stop(context)
                                repeat(50) {
                                    if (RouteFollowingService.savedFollowingRouteId(context) == route.id) {
                                        delay(100L)
                                    }
                                }
                                check(RouteFollowingService.savedFollowingRouteId(context) != route.id) {
                                    "Не удалось остановить следование. Маршрут не удалён."
                                }
                            }
                            editableRouteRepository.deleteRoute(route.id)
                            actionMessage = "Маршрут «${route.title}» удалён."
                        } catch (exception: Exception) {
                            actionMessage = exception.message ?: "Не удалось удалить маршрут."
                        }
                        deleteTarget = null
                    }
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Отмена") }
            }
        )
    }

    appearanceTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { appearanceTarget = null },
            title = { Text("Оформление маршрута") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(target.title)
                    RouteAppearanceControls(
                        colorArgb = appearanceColor,
                        lineWidth = appearanceLineWidth,
                        onColorSelected = { appearanceColor = it },
                        onLineWidthChanged = { appearanceLineWidth = it }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            try {
                                if (target.isRecording) {
                                    repository.updateAppearance(
                                        target.id,
                                        appearanceColor,
                                        appearanceLineWidth
                                    )
                                } else {
                                    editableRouteRepository.updateAppearance(
                                        target.id,
                                        appearanceColor,
                                        appearanceLineWidth
                                    )
                                }
                                actionMessage = "Оформление маршрута обновлено."
                                appearanceTarget = null
                            } catch (exception: Exception) {
                                actionMessage = exception.message ?: "Не удалось изменить оформление."
                            }
                        }
                    }
                ) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { appearanceTarget = null }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun SavedRouteCard(
    item: RouteCatalogItem,
    onOpen: () -> Unit,
    onAppearance: () -> Unit,
    onFollow: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val session = item.session
    val route = item.route
    val colorArgb = session?.routeColorArgb ?: requireNotNull(route).routeColorArgb
    val distanceMeters = session?.totalDistanceMeters ?: requireNotNull(route).distanceMeters
    val isRecordingActive = session?.status == RecordingStatus.RECORDING ||
        session?.status == RecordingStatus.PAUSED
    val canFollow = session?.status == RecordingStatus.FINISHED ||
        (route?.distanceMeters ?: 0.0) > 0.0
    val createdAt = remember(item.createdAtEpochMillis) {
        DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            Locale.getDefault()
        ).format(Date(item.createdAtEpochMillis))
    }

    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0xFFCCD6E2)),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color(colorArgb))
                )
                Text(
                    item.title,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = HamburgTransitPalette.HarborNavy
                )
                if (isRecordingActive) {
                    Text(
                        if (session?.status == RecordingStatus.PAUSED) "Пауза" else "Запись",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                createdAt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                String.format(Locale.getDefault(), "≈ %.2f км", distanceMeters / 1_000.0),
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (canFollow) {
                    OutlinedButton(
                        onClick = onFollow,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFCCD6E2)),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                    ) { Text("Следовать", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                }
                if (!isRecordingActive) {
                    OutlinedButton(
                        onClick = onEdit,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFCCD6E2)),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                    ) { Text("Изменить", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, HamburgTransitPalette.HamburgRed.copy(alpha = 0.45f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HamburgTransitPalette.HamburgRed),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                ) { Text("Удалить", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
            }
            TextButton(onClick = onAppearance) {
                Text("Цвет и толщина линии", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
