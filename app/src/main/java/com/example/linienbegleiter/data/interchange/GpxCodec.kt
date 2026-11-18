package com.example.linienbegleiter.data.interchange

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Читает и записывает GPX 1.1. Разные track segment возвращаются отдельно.
 */
object GpxCodec {
    fun read(input: InputStream): List<InterchangeRoute> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Android's built-in parser does not implement every optional Xerces/JAXP feature.
            // Apply strict flags when available; the resolver below blocks external XML sources.
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            runCatching {
                setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            runCatching {
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            runCatching { setXIncludeAware(false) }
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ ->
                throw org.xml.sax.SAXException("Внешние XML-сущности запрещены.")
            }
        }
        val document = builder.parse(input)
        val root = document.documentElement
        val title = root.firstElement("name")?.textContent?.trim().takeUnless { it.isNullOrBlank() }
            ?: "Импортированный GPX"
        val waypoints = root.childElements("wpt").mapIndexedNotNull { index, point ->
            val coordinate = point.toCoordinate() ?: return@mapIndexedNotNull null
            InterchangeStop(
                name = point.firstElement("name")?.textContent?.trim()
                    ?.takeUnless(String::isBlank) ?: "Точка " + (index + 1),
                latitude = coordinate.latitude,
                longitude = coordinate.longitude,
                sequence = index
            )
        }
        val tracks = root.childElements("trk")
        val trackRoutes = tracks.flatMapIndexed { trackIndex, track ->
            val trackTitle = track.firstElement("name")?.textContent?.trim()
                ?.takeUnless(String::isBlank) ?: title
            track.childElements("trkseg").mapNotNull { segment ->
                val points = segment.childElements("trkpt").mapNotNull { it.toCoordinate() }
                points.takeIf { it.isNotEmpty() }?.let {
                    InterchangeRoute(
                        title = if (tracks.size > 1) trackTitle + " (" + (trackIndex + 1) + ")" else trackTitle,
                        segments = listOf(it)
                    )
                }
            }
        }
        if (trackRoutes.isNotEmpty()) {
            return trackRoutes.mapIndexed { index, route ->
                if (index == 0) route.copy(stops = waypoints) else route
            }
        }
        val sourceRoutes = root.childElements("rte")
        val routes = sourceRoutes.mapIndexedNotNull { index, route ->
            val points = route.childElements("rtept").mapNotNull { it.toCoordinate() }
            if (points.isEmpty()) return@mapIndexedNotNull null
            val routeTitle = route.firstElement("name")?.textContent?.trim()
                ?.takeUnless(String::isBlank) ?: title
            InterchangeRoute(
                title = if (sourceRoutes.size > 1) routeTitle + " (" + (index + 1) + ")" else routeTitle,
                segments = listOf(points)
            )
        }
        require(routes.isNotEmpty()) { "В GPX не найдены track points или route points." }
        return routes.mapIndexed { index, route ->
            if (index == 0) route.copy(stops = waypoints) else route
        }
    }

    fun write(route: InterchangeRoute, output: OutputStream) {
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<gpx version=\"1.1\" creator=\"Linienbegleiter\" ")
            append("xmlns=\"http://www.topografix.com/GPX/1/1\">")
            append("<metadata><name>").append(xmlEscape(route.title)).append("</name></metadata>")
            route.stops.sortedBy(InterchangeStop::sequence).forEach { stop ->
                append("<wpt lat=\"").append(stop.latitude).append("\" lon=\"")
                    .append(stop.longitude).append("\"><name>")
                    .append(xmlEscape(stop.name)).append("</name></wpt>")
            }
            append("<trk><name>").append(xmlEscape(route.title)).append("</name>")
            route.segments.forEach { segment ->
                append("<trkseg>")
                segment.forEach { point ->
                    append("<trkpt lat=\"").append(point.latitude).append("\" lon=\"")
                        .append(point.longitude).append("\">")
                    point.elevationMeters?.let { append("<ele>").append(it).append("</ele>") }
                    point.timeEpochMillis?.let {
                        append("<time>").append(Instant.ofEpochMilli(it)).append("</time>")
                    }
                    append("</trkpt>")
                }
                append("</trkseg>")
            }
            append("</trk></gpx>")
        }
        output.write(xml.toByteArray(Charsets.UTF_8))
    }

    private fun Element.toCoordinate(): RouteCoordinate? {
        val latitude = getAttribute("lat").toDoubleOrNull() ?: return null
        val longitude = getAttribute("lon").toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        val elevation = firstElement("ele")?.textContent?.trim()?.toDoubleOrNull()
        val time = firstElement("time")?.textContent?.trim()?.let { value ->
            runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        }
        return RouteCoordinate(latitude, longitude, time, elevation)
    }

    private fun Element.firstElement(localName: String): Element? =
        childElements(localName).firstOrNull()

    private fun Element.childElements(localName: String): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
            .filter { elementLocalName(it) == localName }

    private fun elementLocalName(node: Node): String =
        node.localName ?: node.nodeName.substringAfter(':')

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
