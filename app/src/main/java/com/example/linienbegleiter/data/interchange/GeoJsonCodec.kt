package com.example.linienbegleiter.data.interchange

import java.io.InputStream
import java.io.OutputStream

/**
 * Преобразует GeoJSON LineString, MultiLineString и именованные Point features.
 */
object GeoJsonCodec {
    fun read(input: InputStream): List<InterchangeRoute> {
        val root = JsonCodec.parse(input.readBytes().toString(Charsets.UTF_8)).jsonObject()
            ?: error("GeoJSON должен быть JSON-объектом.")
        val features = when (root["type"].jsonString()) {
            "FeatureCollection" -> root["features"].jsonArray()
                ?: error("В FeatureCollection нет списка features.")
            "Feature" -> listOf(root)
            "LineString", "MultiLineString", "Point" -> listOf(
                mapOf("type" to "Feature", "geometry" to root, "properties" to emptyMap<String, Any?>())
            )
            else -> error("Неподдерживаемый тип GeoJSON.")
        }
        val routeCandidates = mutableListOf<InterchangeRoute>()
        val stopFeatures = mutableListOf<InterchangeStop>()
        features.forEach { rawFeature ->
            val feature = rawFeature.jsonObject() ?: error("Некорректный feature GeoJSON.")
            val geometry = feature["geometry"].jsonObject() ?: return@forEach
            val properties = feature["properties"].jsonObject().orEmpty()
            val name = properties["name"].jsonString()?.takeIf(String::isNotBlank)
            when (geometry["type"].jsonString()) {
                "LineString" -> {
                    val points = parseCoordinates(geometry["coordinates"])
                    require(points.size >= 2) { "LineString должен содержать минимум две точки." }
                    routeCandidates += InterchangeRoute(
                        title = name ?: "Импортированный GeoJSON",
                        segments = listOf(points),
                        colorArgb = properties["route_color_argb"].jsonDouble()?.toInt(),
                        lineWidth = properties["route_line_width"].jsonDouble()?.toFloat() ?: 5f,
                        metadataJson = JsonCodec.stringify(properties)
                    )
                }
                "MultiLineString" -> {
                    val lines = geometry["coordinates"].jsonArray()
                        ?: error("У MultiLineString отсутствует массив линий.")
                    lines.forEachIndexed { index, line ->
                        val points = parseCoordinates(line)
                        require(points.size >= 2) {
                            "Часть MultiLineString должна содержать минимум две точки."
                        }
                        routeCandidates += InterchangeRoute(
                            title = if (lines.size > 1) {
                                (name ?: "Импортированный маршрут") + " (" + (index + 1) + ")"
                            } else {
                                name ?: "Импортированный GeoJSON"
                            },
                            segments = listOf(points),
                            colorArgb = properties["route_color_argb"].jsonDouble()?.toInt(),
                            lineWidth = properties["route_line_width"].jsonDouble()?.toFloat() ?: 5f,
                            metadataJson = JsonCodec.stringify(properties)
                        )
                    }
                }
                "Point" -> if (name != null || properties["stop_sequence"] != null) {
                    val point = parseCoordinate(geometry["coordinates"])
                    stopFeatures += InterchangeStop(
                        name = name ?: "Остановка " + (stopFeatures.size + 1),
                        latitude = point.latitude,
                        longitude = point.longitude,
                        sequence = (properties["stop_sequence"].jsonDouble()?.toInt()
                            ?: stopFeatures.size).coerceAtLeast(0)
                    )
                }
            }
        }
        require(routeCandidates.isNotEmpty()) { "В GeoJSON не найдена геометрия LineString." }
        val sortedStops = stopFeatures.sortedBy(InterchangeStop::sequence)
            .mapIndexed { index, stop -> stop.copy(sequence = index) }
        return routeCandidates.mapIndexed { index, route ->
            if (index == 0) route.copy(stops = sortedStops) else route
        }
    }

    fun write(route: InterchangeRoute, output: OutputStream) {
        val features = mutableListOf<Any?>()
        val geometryCoordinates: Any = if (route.segments.size == 1) {
            route.segments.single()
        } else {
            route.segments
        }
        val geometryType = if (route.segments.size == 1) "LineString" else "MultiLineString"
        features += mapOf(
            "type" to "Feature",
            "geometry" to mapOf(
                "type" to geometryType,
                "coordinates" to coordinatesJson(geometryCoordinates)
            ),
            "properties" to buildMap {
                put("name", route.title)
                route.colorArgb?.let { put("route_color_argb", it) }
                put("route_line_width", route.lineWidth)
            }
        )
        route.stops.sortedBy(InterchangeStop::sequence).forEach { stop ->
            features += mapOf(
                "type" to "Feature",
                "geometry" to mapOf(
                    "type" to "Point",
                    "coordinates" to listOf(stop.longitude, stop.latitude)
                ),
                "properties" to mapOf(
                    "name" to stop.name,
                    "stop_sequence" to stop.sequence
                )
            )
        }
        val collection = mapOf("type" to "FeatureCollection", "features" to features)
        output.write(JsonCodec.stringify(collection).toByteArray(Charsets.UTF_8))
    }

    private fun coordinatesJson(value: Any): Any = when (value) {
        is List<*> -> value.map { item ->
            when (item) {
                is RouteCoordinate -> listOf(item.longitude, item.latitude)
                is List<*> -> coordinatesJson(item as Any)
                else -> item
            }
        }
        else -> value
    }

    private fun parseCoordinates(raw: Any?): List<RouteCoordinate> =
        raw.jsonArray()?.map(::parseCoordinate) ?: error("Ожидался массив координат.")

    private fun parseCoordinate(raw: Any?): RouteCoordinate {
        val values = raw.jsonArray() ?: error("Координата должна быть массивом.")
        require(values.size >= 2)
        val longitude = values[0].jsonDouble() ?: error("Некорректная долгота.")
        val latitude = values[1].jsonDouble() ?: error("Некорректная широта.")
        val elevation = values.getOrNull(2).jsonDouble()
        return RouteCoordinate(latitude, longitude, elevationMeters = elevation)
    }
}
