package com.example.linienbegleiter.data.interchange

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Импортирует GTFS Static ZIP и экспортирует только маршруты с сохранёнными расписаниями.
 */
object GtfsCodec {
    private const val MAX_ENTRY_BYTES = 32 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 96 * 1024 * 1024
    private const val MAX_ENTRIES = 64
    private val supportedFiles = setOf(
        "agency.txt", "routes.txt", "trips.txt", "stops.txt", "stop_times.txt",
        "shapes.txt", "calendar.txt", "calendar_dates.txt"
    )

    fun read(input: InputStream): List<InterchangeRoute> {
        val files = readZip(input)
        val tables = files.mapValues { (_, bytes) ->
            GtfsCsvCodec.parse(bytes.toString(Charsets.UTF_8))
        }
        val agency = tables["agency.txt"] ?: error("В GTFS не найден agency.txt.")
        val routes = tables["routes.txt"] ?: error("В GTFS не найден routes.txt.")
        val trips = tables["trips.txt"] ?: error("В GTFS не найден trips.txt.")
        val stops = tables["stops.txt"] ?: error("В GTFS не найден stops.txt.")
        val stopTimes = tables["stop_times.txt"] ?: error("В GTFS не найден stop_times.txt.")
        requireColumns(agency, "agency_name", "agency_url", "agency_timezone")
        requireColumns(routes, "route_id", "route_type")
        requireColumns(trips, "route_id", "service_id", "trip_id")
        requireColumns(stops, "stop_id", "stop_name", "stop_lat", "stop_lon")
        requireColumns(stopTimes, "trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence")

        val routeRows = routes.rows.associateByRequired("route_id")
        val stopRows = stops.rows.associateByRequired("stop_id")
        val timesByTrip = stopTimes.rows.groupBy { it.value("trip_id") }
        val shapes = tables["shapes.txt"]?.also {
            requireColumns(it, "shape_id", "shape_pt_lat", "shape_pt_lon", "shape_pt_sequence")
        }?.rows.orEmpty().groupBy { it.value("shape_id") }

        val validTrips = trips.rows.filter { trip ->
            routeRows.containsKey(trip.value("route_id")) && timesByTrip[trip.value("trip_id")].orEmpty().isNotEmpty()
        }
        require(validTrips.isNotEmpty()) { "В GTFS нет поездок со связанными остановками." }
        val groups = validTrips.groupBy { trip ->
            val shapeId = trip["shape_id"].orEmpty()
            if (shapeId.isBlank()) {
                listOf(trip.value("route_id"), trip["direction_id"].orEmpty(), trip.value("trip_id"))
            } else {
                listOf(trip.value("route_id"), trip["direction_id"].orEmpty(), shapeId)
            }
        }

        return groups.values.map { groupedTrips ->
            val representativeTrip = groupedTrips.first()
            val routeRow = requireNotNull(routeRows[representativeTrip.value("route_id")])
            val selectedTimes = groupedTrips.flatMap { timesByTrip[it.value("trip_id")].orEmpty() }
            val stopIds = selectedTimes.map { it.value("stop_id") }.toSet()
            require(stopIds.all(stopRows::containsKey)) { "stop_times.txt ссылается на неизвестную остановку." }
            val primaryTripId = representativeTrip.value("trip_id")
            val primaryTimes = timesByTrip[primaryTripId].orEmpty()
                .sortedBy { it.value("stop_sequence").toIntOrNull() ?: Int.MAX_VALUE }
            val routeStops = primaryTimes.mapIndexed { index, time ->
                val stop = requireNotNull(stopRows[time.value("stop_id")])
                val latitude = stop.value("stop_lat").toDoubleOrNull()
                    ?: error("Некорректная широта остановки " + stop.value("stop_id") + ".")
                val longitude = stop.value("stop_lon").toDoubleOrNull()
                    ?: error("Некорректная долгота остановки " + stop.value("stop_id") + ".")
                InterchangeStop(
                    name = stop.value("stop_name").ifBlank { stop.value("stop_id") },
                    latitude = latitude,
                    longitude = longitude,
                    sequence = index
                )
            }
            val shapeId = representativeTrip["shape_id"].orEmpty()
            val shapeRows = shapes[shapeId].orEmpty()
                .sortedBy { it.value("shape_pt_sequence").toIntOrNull() ?: Int.MAX_VALUE }
            val shapePoints = shapeRows.mapNotNull { row ->
                val latitude = row["shape_pt_lat"]?.toDoubleOrNull() ?: return@mapNotNull null
                val longitude = row["shape_pt_lon"]?.toDoubleOrNull() ?: return@mapNotNull null
                runCatching { RouteCoordinate(latitude, longitude) }.getOrNull()
            }
            val serviceIds = groupedTrips.map { it.value("service_id") }.toSet()
            val routeAgencyId = routeRow["agency_id"]
            val selectedAgency = if (routeAgencyId.isNullOrBlank()) {
                agency.rows
            } else {
                agency.rows.filter { it["agency_id"] == routeAgencyId }
            }
            require(selectedAgency.isNotEmpty()) {
                "routes.txt ссылается на неизвестное агентство."
            }
            val calendarRows = tables["calendar.txt"]?.rows.orEmpty()
                .filter { it["service_id"] in serviceIds }
            val exceptionRows = tables["calendar_dates.txt"]?.rows.orEmpty()
                .filter { it["service_id"] in serviceIds }
            val warnings = buildList {
                if (shapePoints.size < 2) add(
                    "Для этого направления нет пригодной геометрии shapes.txt; импортированы остановки."
                )
                if (calendarRows.isEmpty() && exceptionRows.isEmpty()) add(
                    "Нет календаря обслуживания; GTFS экспорт будет недоступен до добавления расписания."
                )
                if (routeAgencyId.isNullOrBlank() && agency.rows.size > 1) add(
                    "У маршрута не указан agency_id при нескольких перевозчиках."
                )
            }
            val shortName = routeRow["route_short_name"].orEmpty()
            val longName = routeRow["route_long_name"].orEmpty()
            val baseName = listOf(shortName, longName).filter(String::isNotBlank).joinToString(" — ")
                .ifBlank { representativeTrip.value("route_id") }
            val directionId = representativeTrip["direction_id"].orEmpty()
            val title = baseName + if (directionId.isNotBlank()) " • Направление " + directionId else ""

            val includedFiles = linkedMapOf(
                "agency.txt" to GtfsCsvCodec.Table(agency.headers, selectedAgency),
                "routes.txt" to GtfsCsvCodec.Table(routes.headers, listOf(routeRow)),
                "trips.txt" to GtfsCsvCodec.Table(
                    trips.headers,
                    groupedTrips
                ),
                "stops.txt" to GtfsCsvCodec.Table(
                    stops.headers,
                    stopIds.mapNotNull(stopRows::get)
                ),
                "stop_times.txt" to GtfsCsvCodec.Table(stopTimes.headers, selectedTimes)
            )
            if (shapeRows.isNotEmpty()) {
                includedFiles["shapes.txt"] = GtfsCsvCodec.Table(
                    tables.getValue("shapes.txt").headers,
                    shapeRows
                )
            }
            if (calendarRows.isNotEmpty()) {
                includedFiles["calendar.txt"] = GtfsCsvCodec.Table(
                    tables.getValue("calendar.txt").headers,
                    calendarRows
                )
            }
            if (exceptionRows.isNotEmpty()) {
                includedFiles["calendar_dates.txt"] = GtfsCsvCodec.Table(
                    tables.getValue("calendar_dates.txt").headers,
                    exceptionRows
                )
            }
            val metadata = linkedMapOf<String, Any?>(
                "format" to "GTFS_STATIC",
                "primary_trip_id" to primaryTripId,
                "primary_stops" to routeStops.map { stop ->
                    mapOf(
                        "name" to stop.name,
                        "latitude" to stop.latitude,
                        "longitude" to stop.longitude
                    )
                },
                "files" to includedFiles.mapValues { (_, table) ->
                    mapOf(
                        "headers" to table.headers,
                        "rows" to table.rows.map { it.toMap(LinkedHashMap()) }
                    )
                }
            )
            val color = routeRow["route_color"]?.let(::parseGtfsColor)
            InterchangeRoute(
                title = title,
                segments = if (shapePoints.size >= 2) listOf(shapePoints) else emptyList(),
                stops = routeStops,
                colorArgb = color,
                metadataJson = JsonCodec.stringify(metadata),
                warnings = warnings
            )
        }
    }

    fun write(route: InterchangeRoute, output: OutputStream) {
        val metadata = route.metadataJson?.let(JsonCodec::parse)?.jsonObject()
            ?: error("У маршрута нет метаданных GTFS с расписанием.")
        require(metadata["format"].jsonString() == "GTFS_STATIC") {
            "Маршрут не был импортирован из GTFS."
        }
        val files = metadata["files"].jsonObject()
            ?: error("В метаданных не найдены таблицы GTFS.")
        val tables = files.mapValues { (_, rawTable) ->
            val table = rawTable.jsonObject() ?: error("Некорректная таблица GTFS.")
            val headers = table["headers"].jsonArray()?.map {
                it.jsonString() ?: error("Некорректный заголовок GTFS.")
            } ?: error("В таблице GTFS отсутствуют заголовки.")
            val rows = table["rows"].jsonArray()?.map { rawRow ->
                rawRow.jsonObject()?.mapValues { (_, value) ->
                    value.jsonString() ?: value?.toString().orEmpty()
                } ?: error("Некорректная строка GTFS.")
            } ?: error("В таблице GTFS отсутствуют строки.")
            GtfsCsvCodec.Table(headers, rows)
        }
        val mutableTables = tables.toMutableMap()
        validateRouteStops(metadata, route)
        applyGeometryEdits(mutableTables, route)
        val agency = requireNotNull(mutableTables["agency.txt"]) { "Не сохранён agency.txt." }
        val routes = requireNotNull(mutableTables["routes.txt"]) { "Не сохранён routes.txt." }
        val trips = requireNotNull(mutableTables["trips.txt"]) { "Не сохранён trips.txt." }
        val stops = requireNotNull(mutableTables["stops.txt"]) { "Не сохранён stops.txt." }
        val stopTimes = requireNotNull(mutableTables["stop_times.txt"]) { "Не сохранён stop_times.txt." }
        require(agency.rows.isNotEmpty() && routes.rows.isNotEmpty() && trips.rows.isNotEmpty()) {
            "Нельзя создать GTFS без перевозчика, маршрута и поездки."
        }
        require(stops.rows.isNotEmpty() && stopTimes.rows.isNotEmpty()) {
            "Нельзя создать GTFS без остановок и stop_times."
        }
        require(mutableTables["calendar.txt"]?.rows.orEmpty().isNotEmpty() ||
            mutableTables["calendar_dates.txt"]?.rows.orEmpty().isNotEmpty()
        ) { "Расписание обслуживания отсутствует; фиктивный календарь не создаётся." }
        validateReferences(mutableTables)

        val routeHeaders = if (route.colorArgb != null && "route_color" !in routes.headers) {
            routes.headers + "route_color"
        } else {
            routes.headers
        }
        val routeTable = routes.copy(
            headers = routeHeaders,
            rows = routes.rows.map { row ->
                route.colorArgb?.let {
                    row + ("route_color" to "%06X".format(it and 0x00FFFFFF))
                } ?: row
            }
        )
        val exportTables = mutableTables.apply { this["routes.txt"] = routeTable }
        val zip = ZipOutputStream(output)
        exportTables.forEach { (name, table) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(GtfsCsvCodec.write(table).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        zip.finish()
    }

    private fun validateRouteStops(
        metadata: Map<String, Any?>,
        route: InterchangeRoute
    ) {
        val sourceStops = metadata["primary_stops"].jsonArray()
            ?: error("В метаданных GTFS нет исходного порядка остановок.")
        val expected = sourceStops.map { raw ->
            raw.jsonObject() ?: error("Некорректная исходная остановка GTFS.")
        }
        val actual = route.stops.sortedBy(InterchangeStop::sequence)
        require(actual.size == expected.size) {
            "Изменился состав остановок. GTFS экспорт не меняет расписание; для этого маршрута используйте GeoJSON или резервный архив."
        }
        val unchanged = expected.zip(actual).all { (source, current) ->
            source["name"].jsonString() == current.name &&
                source["latitude"].jsonDouble()?.let { kotlin.math.abs(it - current.latitude) < 1e-7 } == true &&
                source["longitude"].jsonDouble()?.let { kotlin.math.abs(it - current.longitude) < 1e-7 } == true
        }
        require(unchanged) {
            "Остановки изменены или переставлены. GTFS экспорт не может безопасно перенести расписание на новый порядок."
        }
    }

    private fun applyGeometryEdits(
        files: MutableMap<String, GtfsCsvCodec.Table>,
        route: InterchangeRoute
    ) {
        val points = route.points
        require(points.isEmpty() || points.size >= 2) {
            "GTFS shape должен содержать минимум две точки."
        }
        if (points.isEmpty()) return

        val trips = files.getValue("trips.txt")
        val existingShapeIds = trips.rows.mapNotNull { row ->
            row["shape_id"]?.takeIf(String::isNotBlank)
        }.distinct()
        require(existingShapeIds.size <= 1) {
            "В экспорте смешаны поездки с разными shape_id."
        }
        val routeId = files.getValue("routes.txt").rows.firstOrNull()?.get("route_id").orEmpty()
        val shapeId = existingShapeIds.singleOrNull()
            ?: "lb" + Integer.toUnsignedString(routeId.hashCode(), 16) +
                Integer.toUnsignedString(route.title.hashCode(), 16)
        val tripHeaders = if ("shape_id" in trips.headers) trips.headers else trips.headers + "shape_id"
        files["trips.txt"] = trips.copy(
            headers = tripHeaders,
            rows = trips.rows.map { it + ("shape_id" to shapeId) }
        )

        val oldShapes = files["shapes.txt"]
        val shapeHeaders = oldShapes?.headers
            ?.filterNot { it == "shape_dist_traveled" }
            ?: listOf("shape_id", "shape_pt_lat", "shape_pt_lon", "shape_pt_sequence")
        require(listOf("shape_id", "shape_pt_lat", "shape_pt_lon", "shape_pt_sequence")
            .all(shapeHeaders::contains)
        ) { "В shapes.txt отсутствуют обязательные столбцы." }
        val shapeRows = points.mapIndexed { index, point ->
            mapOf(
                "shape_id" to shapeId,
                "shape_pt_lat" to point.latitude.toString(),
                "shape_pt_lon" to point.longitude.toString(),
                "shape_pt_sequence" to index.toString()
            )
        }
        files["shapes.txt"] = GtfsCsvCodec.Table(shapeHeaders, shapeRows)

        files["stop_times.txt"]?.let { stopTimes ->
            if ("shape_dist_traveled" in stopTimes.headers) {
                val headers = stopTimes.headers.filterNot { it == "shape_dist_traveled" }
                files["stop_times.txt"] = GtfsCsvCodec.Table(
                    headers,
                    stopTimes.rows.map { it - "shape_dist_traveled" }
                )
            }
        }
    }

    private fun validateReferences(files: Map<String, GtfsCsvCodec.Table>) {
        val agency = files.getValue("agency.txt")
        val routeRows = files.getValue("routes.txt").rows
        val routeIds = routeRows.map { it.value("route_id") }.toSet()
        val agencyIds = agency.rows.mapNotNull { it["agency_id"]?.takeIf(String::isNotBlank) }.toSet()
        if (agency.rows.size > 1) {
            require(routeRows.all { it["agency_id"] in agencyIds }) {
                "При нескольких перевозчиках каждый маршрут должен ссылаться на agency_id."
            }
        } else {
            require(routeRows.all { row ->
                row["agency_id"].isNullOrBlank() || row["agency_id"] in agencyIds
            }) { "routes.txt содержит неизвестный agency_id." }
        }

        val trips = files.getValue("trips.txt").rows
        val tripIds = trips.map { it.value("trip_id") }.toSet()
        require(tripIds.size == trips.size) { "trip_id должен быть уникальным." }
        require(trips.all { it.value("route_id") in routeIds }) {
            "trips.txt содержит неизвестный route_id."
        }
        val serviceIds = (files["calendar.txt"]?.rows.orEmpty() +
            files["calendar_dates.txt"]?.rows.orEmpty()).mapNotNull { it["service_id"] }.toSet()
        require(trips.all { it.value("service_id") in serviceIds }) {
            "Для одной или нескольких поездок отсутствует calendar или calendar_dates."
        }
        val stopIds = files.getValue("stops.txt").rows.map { it.value("stop_id") }.toSet()
        val times = files.getValue("stop_times.txt").rows
        require(times.all { it.value("trip_id") in tripIds && it.value("stop_id") in stopIds }) {
            "stop_times.txt содержит неизвестный trip_id или stop_id."
        }
        require(times.groupBy { it.value("trip_id") }.values.all { rows ->
            val sequences = rows.mapNotNull { it["stop_sequence"]?.toIntOrNull() }
            sequences.size == rows.size && sequences.distinct().size == sequences.size
        }) { "stop_sequence должен быть числом и не повторяться внутри поездки." }

        val shapeIds = files["shapes.txt"]?.rows.orEmpty()
            .mapNotNull { it["shape_id"]?.takeIf(String::isNotBlank) }.toSet()
        require(trips.all { row ->
            val shapeId = row["shape_id"].orEmpty()
            shapeId.isBlank() || shapeId in shapeIds
        }) { "trips.txt ссылается на shape_id без соответствующей геометрии." }
    }

    private fun readZip(input: InputStream): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        var totalBytes = 0
        var entryCount = 0
        ZipInputStream(input, Charsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_ENTRIES) { "В ZIP слишком много файлов." }
                if (!entry.isDirectory) {
                    require(!entry.name.contains('/') && !entry.name.contains('\\')) {
                        "В GTFS ZIP файлы должны находиться в корне архива."
                    }
                    val name = entry.name.lowercase()
                    val keep = name in supportedFiles
                    if (keep) {
                        require(!result.containsKey(name)) { "В GTFS ZIP повторяется " + name + "." }
                    }
                    val output = if (keep) ByteArrayOutputStream() else null
                    val buffer = ByteArray(8192)
                    var entryBytes = 0
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        entryBytes += count
                        totalBytes += count
                        require(entryBytes <= MAX_ENTRY_BYTES && totalBytes <= MAX_TOTAL_BYTES) {
                            "GTFS ZIP превышает допустимый размер."
                        }
                        output?.write(buffer, 0, count)
                    }
                    if (output != null) result[name] = output.toByteArray()
                }
                zip.closeEntry()
            }
        }
        return result
    }

    private fun requireColumns(table: GtfsCsvCodec.Table, vararg required: String) {
        val missing = required.filterNot(table.headers::contains)
        require(missing.isEmpty()) { "В GTFS отсутствуют столбцы: " + missing.joinToString(", ") }
    }

    private fun List<Map<String, String>>.associateByRequired(key: String): Map<String, Map<String, String>> {
        val result = linkedMapOf<String, Map<String, String>>()
        forEach { row ->
            val id = row.value(key)
            require(id.isNotBlank()) { "Пустой идентификатор " + key + "." }
            require(result.put(id, row) == null) { "Повторяется идентификатор " + id + "." }
        }
        return result
    }

    private fun Map<String, String>.value(key: String): String =
        this[key] ?: error("В таблице отсутствует столбец " + key + ".")

    private fun parseGtfsColor(value: String): Int? {
        val normalized = value.removePrefix("#")
        if (!normalized.matches(Regex("[0-9a-fA-F]{6}"))) return null
        return runCatching { (0xFF000000L or normalized.toLong(16)).toInt() }.getOrNull()
    }
}
