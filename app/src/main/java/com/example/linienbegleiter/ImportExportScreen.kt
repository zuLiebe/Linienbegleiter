package com.example.linienbegleiter

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.linienbegleiter.data.interchange.GeoJsonCodec
import com.example.linienbegleiter.data.interchange.GpxCodec
import com.example.linienbegleiter.data.interchange.GtfsCodec
import com.example.linienbegleiter.data.interchange.InterchangeRoute
import com.example.linienbegleiter.data.interchange.LinienbegleiterArchiveCodec
import com.example.linienbegleiter.data.interchange.InterchangeStop
import com.example.linienbegleiter.data.interchange.RouteCoordinate
import com.example.linienbegleiter.data.local.ArchivePreview
import com.example.linienbegleiter.data.local.RecordingDatabase
import com.example.linienbegleiter.data.local.RoomArchiveRepository
import com.example.linienbegleiter.data.recording.RecordingSession
import com.example.linienbegleiter.data.recording.RecordingStatus
import com.example.linienbegleiter.data.recording.RoomRecordingRepository
import com.example.linienbegleiter.data.routes.EditableRouteKind
import com.example.linienbegleiter.data.routes.EditableRoutePoint
import com.example.linienbegleiter.data.routes.RouteDraftStop
import com.example.linienbegleiter.data.routes.RoomEditableRouteRepository
import com.example.linienbegleiter.data.routes.uniqueRouteTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class FileExchangeFormat(val label: String, val extension: String) {
    GPX("GPX", ".gpx"),
    GEOJSON("GeoJSON", ".geojson"),
    GTFS("GTFS Static", ".zip"),
    ARCHIVE("Резервная копия", ".linienbegleiter.zip")
}

private data class ExportRouteChoice(
    val key: String,
    val title: String,
    val isRecording: Boolean,
    val recording: RecordingSession? = null
)

private data class ImportParseResult(
    val routes: List<InterchangeRoute> = emptyList(),
    val archiveBundle: LinienbegleiterArchiveCodec.Bundle? = null,
    val archivePreview: ArchivePreview? = null
)

/**
 * Локальный импорт и экспорт маршрутов через системный Document Picker.
 */
@Composable
fun ImportExportScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember(context) { RecordingDatabase.getInstance(context) }
    val editableRepository = remember(database) { RoomEditableRouteRepository(database) }
    val recordingRepository = remember(database) { RoomRecordingRepository(database) }
    val archiveRepository = remember(database) { RoomArchiveRepository(database) }
    val editableRoutes by editableRepository.observeRoutes().collectAsState(initial = emptyList())
    val recordings by recordingRepository.observeSessions().collectAsState(initial = emptyList())
    val routeChoices = remember(editableRoutes, recordings) {
        editableRoutes.map {
            ExportRouteChoice("editable:" + it.id, it.title, false)
        } + recordings.filter { it.status == RecordingStatus.FINISHED }.map {
            ExportRouteChoice("recording:" + it.id, it.title, true, it)
        }
    }

    var selectedRouteKey by remember { mutableStateOf<String?>(null) }
    var format by remember { mutableStateOf(FileExchangeFormat.GPX) }
    var pendingExport by remember { mutableStateOf<InterchangeRoute?>(null) }
    var pendingArchiveExport by remember { mutableStateOf(false) }
    var archiveBundle by remember {
        mutableStateOf<LinienbegleiterArchiveCodec.Bundle?>(null)
    }
    var archivePreview by remember { mutableStateOf<ArchivePreview?>(null) }
    var importedCandidates by remember { mutableStateOf<List<InterchangeRoute>>(emptyList()) }
    var selectedCandidateIndices by remember { mutableStateOf(setOf<Int>()) }
    var previewCandidateIndex by remember { mutableStateOf<Int?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    val handleExportResult: (android.net.Uri?, FileExchangeFormat) -> Unit = { uri, selectedFormat ->
        val route = pendingExport
        val exportArchive = pendingArchiveExport
        if (uri != null && (route != null || exportArchive)) {
            scope.launch {
                working = true
                statusMessage = null
                runCatching {
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri)
                            ?: error("Не удалось открыть выбранный файл для записи.")
                        stream.use {
                            if (exportArchive) {
                                archiveRepository.export(it)
                            } else {
                                val selectedRoute = requireNotNull(route)
                                when (selectedFormat) {
                                    FileExchangeFormat.GPX -> GpxCodec.write(selectedRoute, it)
                                    FileExchangeFormat.GEOJSON -> GeoJsonCodec.write(selectedRoute, it)
                                    FileExchangeFormat.GTFS -> GtfsCodec.write(selectedRoute, it)
                                    FileExchangeFormat.ARCHIVE -> error("Не выбран архив данных.")
                                }
                            }
                        }
                    }
                }.onSuccess {
                    statusMessage = if (exportArchive) {
                        "Резервная копия Linienbegleiter сохранена."
                    } else {
                        "Маршрут экспортирован."
                    }
                }.onFailure {
                    statusMessage = "Ошибка экспорта: " + (it.message ?: "неизвестная ошибка")
                }
                working = false
            }
        }
        pendingExport = null
        pendingArchiveExport = false
    }
    val gpxExportDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri -> handleExportResult(uri, FileExchangeFormat.GPX) }
    val geoJsonExportDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/geo+json")
    ) { uri -> handleExportResult(uri, FileExchangeFormat.GEOJSON) }
    val gtfsExportDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> handleExportResult(uri, FileExchangeFormat.GTFS) }
    val archiveExportDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> handleExportResult(uri, FileExchangeFormat.ARCHIVE) }

    fun launchExportDocument(fileName: String) {
        when (format) {
            FileExchangeFormat.GPX -> gpxExportDocument.launch(fileName)
            FileExchangeFormat.GEOJSON -> geoJsonExportDocument.launch(fileName)
            FileExchangeFormat.GTFS -> gtfsExportDocument.launch(fileName)
            FileExchangeFormat.ARCHIVE -> archiveExportDocument.launch(fileName)
        }
    }

    val importDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                working = true
                statusMessage = null
                runCatching {
                    withContext(Dispatchers.IO) {
                        val displayName = queryDisplayName(context, uri)
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val bytes = stream.readBounded(MAX_IMPORT_BYTES)
                            when {
                                displayName.endsWith(".gpx", ignoreCase = true) ||
                                    context.contentResolver.getType(uri) == "application/gpx+xml" ->
                                    ImportParseResult(
                                        routes = GpxCodec.read(
                                            java.io.ByteArrayInputStream(bytes)
                                        )
                                    )
                                displayName.endsWith(".geojson", ignoreCase = true) ||
                                    displayName.endsWith(".json", ignoreCase = true) ||
                                    context.contentResolver.getType(uri) == "application/geo+json" ||
                                    context.contentResolver.getType(uri) == "application/json" ->
                                    ImportParseResult(
                                        routes = GeoJsonCodec.read(
                                            java.io.ByteArrayInputStream(bytes)
                                        )
                                    )
                                displayName.endsWith(".zip", ignoreCase = true) ||
                                    context.contentResolver.getType(uri) == "application/zip" -> {
                                    if (bytes.hasZipEntry("manifest.json")) {
                                        val bundle = LinienbegleiterArchiveCodec.read(
                                            java.io.ByteArrayInputStream(bytes)
                                        )
                                        ImportParseResult(
                                            archiveBundle = bundle,
                                            archivePreview = archiveRepository.inspect(bundle)
                                        )
                                    } else {
                                        ImportParseResult(
                                            routes = GtfsCodec.read(
                                                java.io.ByteArrayInputStream(bytes)
                                            )
                                        )
                                    }
                                }
                                else -> error(
                                    "Поддерживаются GPX, GeoJSON, GTFS Static ZIP и архив Linienbegleiter."
                                )
                            }
                        } ?: error("Не удалось открыть выбранный файл.")
                    }
                }.onSuccess { parsed ->
                    importedCandidates = parsed.routes
                    selectedCandidateIndices = parsed.routes.indices.toSet()
                    previewCandidateIndex = parsed.routes.indices.firstOrNull()
                    archiveBundle = parsed.archiveBundle
                    archivePreview = parsed.archivePreview
                    statusMessage = if (parsed.archiveBundle != null) {
                        "Проверьте состав резервной копии и подтвердите добавление."
                    } else {
                        "Найдено маршрутов: " + parsed.routes.size +
                            ". Проверьте данные и подтвердите импорт."
                    }
                }.onFailure {
                    importedCandidates = emptyList()
                    selectedCandidateIndices = emptySet()
                    previewCandidateIndex = null
                    archiveBundle = null
                    archivePreview = null
                    statusMessage = "Ошибка чтения файла: " + (it.message ?: "неизвестная ошибка")
                }
                working = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Импорт и экспорт", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Файлы открываются и сохраняются через системный выбор документов. Доступ ко всему хранилищу не нужен.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = onBack, enabled = !working) { Text("Назад") }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (format == FileExchangeFormat.ARCHIVE) "Экспорт резервной копии"
                    else "Экспорт маршрута",
                    style = MaterialTheme.typography.titleMedium
                )
                if (format == FileExchangeFormat.ARCHIVE) {
                    Text("Архив включает сохранённые маршруты, GPS-данные, остановки, направления и оформление.")
                } else if (routeChoices.isEmpty()) {
                    Text("Сначала создайте или запишите маршрут.")
                } else {
                    routeChoices.forEach { choice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedRouteKey == choice.key,
                                    onClick = { selectedRouteKey = choice.key }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedRouteKey == choice.key,
                                onClick = { selectedRouteKey = choice.key }
                            )
                            Column {
                                Text(choice.title)
                                Text(
                                    "Маршрут",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FileExchangeFormat.entries.forEach { option ->
                        if (format == option) {
                            Button(onClick = { format = option }, enabled = !working) {
                                Text(option.label)
                            }
                        } else {
                            OutlinedButton(onClick = { format = option }, enabled = !working) {
                                Text(option.label)
                            }
                        }
                    }
                }
                Button(
                    onClick = {
                        if (format == FileExchangeFormat.ARCHIVE) {
                            pendingExport = null
                            pendingArchiveExport = true
                            launchExportDocument("backup" + format.extension)
                        } else {
                            val choice = routeChoices.firstOrNull { it.key == selectedRouteKey }
                            if (choice == null) {
                                statusMessage = "Выберите маршрут для экспорта."
                            } else {
                                scope.launch {
                                    working = true
                                    runCatching {
                                        val routeResult = withContext(Dispatchers.IO) {
                                            if (choice.isRecording) {
                                                val details = recordingRepository.getDetails(choice.recording!!.id)
                                                    ?: error("Запись не найдена.")
                                                InterchangeRoute(
                                                    title = details.session.title,
                                                    segments = listOf(details.points.map {
                                                        RouteCoordinate(
                                                            it.latitude,
                                                            it.longitude,
                                                            it.timestampEpochMillis
                                                        )
                                                    }).filter(List<RouteCoordinate>::isNotEmpty),
                                                    stops = details.stops.map {
                                                        InterchangeStop(
                                                            it.name,
                                                            it.latitude,
                                                            it.longitude,
                                                            it.sequence
                                                        )
                                                    },
                                                    colorArgb = details.session.routeColorArgb,
                                                    lineWidth = details.session.routeLineWidth
                                                )
                                            } else {
                                                val routeId = choice.key.removePrefix("editable:")
                                                val details = editableRepository.getDetails(routeId)
                                                    ?: error("Маршрут не найден.")
                                                InterchangeRoute(
                                                    title = details.route.title,
                                                    segments = listOf(details.points.map {
                                                        RouteCoordinate(it.latitude, it.longitude)
                                                    }).filter(List<RouteCoordinate>::isNotEmpty),
                                                    stops = details.stops.map {
                                                        InterchangeStop(
                                                            it.name,
                                                            it.latitude,
                                                            it.longitude,
                                                            it.sequence
                                                        )
                                                    },
                                                    colorArgb = details.route.routeColorArgb,
                                                    lineWidth = details.route.routeLineWidth,
                                                    metadataJson = details.route.routeMetadataJson
                                                )
                                            }
                                        }
                                        if (format != FileExchangeFormat.GTFS &&
                                            routeResult.points.size < 2
                                        ) {
                                            error("Для GPX и GeoJSON требуется не менее двух точек пути.")
                                        }
                                        pendingExport = routeResult
                                        launchExportDocument(choice.title + format.extension)
                                    }.onFailure {
                                        statusMessage = "Ошибка подготовки экспорта: " +
                                            (it.message ?: "неизвестная ошибка")
                                    }
                                    working = false
                                }
                            }
                        }
                    },
                    enabled = !working &&
                        (format == FileExchangeFormat.ARCHIVE || routeChoices.isNotEmpty())
                ) {
                    Text(if (working) "Подождите…" else "Экспортировать")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Импорт маршрута или архива", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Поддерживаются GPX, GeoJSON, GTFS Static ZIP и резервный архив Linienbegleiter. Сегменты GPX/MultiLineString импортируются отдельно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        importDocument.launch(arrayOf("*/*"))
                    },
                    enabled = !working
                ) { Text("Выбрать файл") }

                val pendingArchive = archiveBundle
                val archiveSummary = archivePreview
                if (pendingArchive != null && archiveSummary != null) {
                    Text(
                        "Резервная копия Linienbegleiter",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "GPS-записей: ${archiveSummary.recordings} • маршрутов: ${archiveSummary.routes}\n" +
                            "Точек: ${archiveSummary.points} • остановок: ${archiveSummary.stops}"
                    )
                    if (archiveSummary.unfinishedRecordings > 0) {
                        Text(
                            "Незавершённые записи будут добавлены на паузе; уведомление и GPS-служба не запускаются.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                working = true
                                val result = runCatching {
                                    withContext(Dispatchers.IO) {
                                        archiveRepository.import(pendingArchive)
                                    }
                                }
                                statusMessage = result.fold(
                                    onSuccess = { summary ->
                                        archiveBundle = null
                                        archivePreview = null
                                        "Добавлено: ${summary.recordingsAdded} GPS-записей, " +
                                            "${summary.routesAdded} маршрутов, " +
                                            "${summary.pointsAdded} точек, " +
                                            "${summary.stopsAdded} остановок."
                                    },
                                    onFailure = {
                                        "Архив не импортирован: " +
                                            (it.message ?: "неизвестная ошибка")
                                    }
                                )
                                working = false
                            }
                        },
                        enabled = !working
                    ) { Text("Подтвердить импорт архива") }
                }

                val previewIndex = previewCandidateIndex
                val candidate = previewIndex?.let(importedCandidates::getOrNull)
                if (candidate != null) {
                    Text(candidate.title, fontWeight = FontWeight.SemiBold)
                    Text("Точек пути: " + candidate.points.size + " • Остановок: " + candidate.stops.size)
                    candidate.warnings.forEach { warning ->
                        Text(
                            warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    MapScreen(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp),
                        initialCameraPosition = null,
                        onCameraPositionChanged = {},
                        onBack = {},
                        routePoints = candidate.points.mapIndexed { index, point ->
                            MapRoutePoint(index, point.latitude, point.longitude, point.timeEpochMillis)
                        },
                        routeStops = candidate.stops.mapIndexed { index, stop ->
                            MapRouteStop(
                                sequence = index,
                                name = stop.name,
                                latitude = stop.latitude,
                                longitude = stop.longitude,
                                isStart = index == 0,
                                isEnd = index == candidate.stops.lastIndex
                            )
                        },
                        routeColorArgb = candidate.colorArgb,
                        routeLineWidth = candidate.lineWidth,
                        showChrome = false,
                        requestLocationOnOpen = false
                    )
                    importedCandidates.forEachIndexed { index, route ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = index in selectedCandidateIndices,
                                onCheckedChange = { checked ->
                                    selectedCandidateIndices = if (checked) {
                                        selectedCandidateIndices + index
                                    } else {
                                        selectedCandidateIndices - index
                                    }
                                    previewCandidateIndex = selectedCandidateIndices.firstOrNull()
                                        ?: if (checked) index else null
                                }
                            )
                            Text(
                                text = route.title + " • " + route.points.size + " точек • " +
                                    route.stops.size + " остановок",
                                modifier = Modifier.weight(1f)
                            )
                            RadioButton(
                                selected = previewCandidateIndex == index,
                                onClick = { previewCandidateIndex = index }
                            )
                        }
                    }
                    Button(
                        onClick = {
                            val toImport = importedCandidates.filterIndexed { index, _ ->
                                index in selectedCandidateIndices
                            }
                            scope.launch {
                                working = true
                                val result = runCatching {
                                    withContext(Dispatchers.IO) {
                                        toImport.forEach { route ->
                                            val isGtfs = route.metadataJson?.contains(
                                                "\"format\":\"GTFS_STATIC\""
                                            ) == true
                                            if (!isGtfs) {
                                                require(route.points.size >= 2) {
                                                    "Маршрут «" + route.title +
                                                        "» содержит меньше двух точек пути."
                                                }
                                            }
                                            editableRepository.createRoute(
                                                title = database.uniqueRouteTitle(route.title),
                                                createdAtEpochMillis = System.currentTimeMillis(),
                                                points = route.points.mapIndexed { index, point ->
                                                    EditableRoutePoint(
                                                        sequence = index,
                                                        latitude = point.latitude,
                                                        longitude = point.longitude
                                                    )
                                                },
                                                stops = route.stops.map {
                                                    RouteDraftStop(
                                                        routePointSequence = null,
                                                        name = it.name,
                                                        latitude = it.latitude,
                                                        longitude = it.longitude
                                                    )
                                                },
                                                routeKind = if (isGtfs) {
                                                    EditableRouteKind.GTFS_IMPORT
                                                } else {
                                                    EditableRouteKind.FILE_IMPORT
                                                },
                                                routeColorArgb = route.colorArgb,
                                                routeLineWidth = route.lineWidth,
                                                routeMetadataJson = route.metadataJson
                                            )
                                        }
                                    }
                                }
                                statusMessage = result.fold(
                                    onSuccess = {
                                        importedCandidates = emptyList()
                                        selectedCandidateIndices = emptySet()
                                        previewCandidateIndex = null
                                        "Импортированные маршруты добавлены в общий список как самостоятельные маршруты."
                                    },
                                    onFailure = {
                                        "Импорт не завершён: " + (it.message ?: "неизвестная ошибка")
                                    }
                                )
                                working = false
                            }
                        },
                        enabled = !working && selectedCandidateIndices.isNotEmpty()
                    ) { Text("Подтвердить импорт") }
                }
            }
        }

        statusMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(8.dp))
    }
}

private const val MAX_IMPORT_BYTES = 128 * 1024 * 1024

private fun java.io.InputStream.readBounded(maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes) { "Файл превышает допустимый размер." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun ByteArray.hasZipEntry(expectedName: String): Boolean =
    java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(this)).use { zip ->
        var found = false
        var totalBytes = 0
        val buffer = ByteArray(8192)
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.name == expectedName) found = true
            while (true) {
                val count = zip.read(buffer)
                if (count < 0) break
                totalBytes += count
                require(totalBytes <= MAX_IMPORT_BYTES) {
                    "ZIP-превышает допустимый размер распакованных данных."
                }
            }
        }
        found
    }

private fun queryDisplayName(
    context: android.content.Context,
    uri: android.net.Uri
): String {
    context.contentResolver.query(
        uri,
        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) return cursor.getString(column).orEmpty()
    }
    return uri.lastPathSegment.orEmpty()
}
