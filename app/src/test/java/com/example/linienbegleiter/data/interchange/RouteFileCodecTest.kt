package com.example.linienbegleiter.data.interchange

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class RouteFileCodecTest {
    @Test
    fun gpxRoundTripPreservesCoordinatesStopsAndTimestamps() {
        val route = InterchangeRoute(
            title = "Тестовый маршрут",
            segments = listOf(
                listOf(
                    RouteCoordinate(53.55, 9.99, 1_700_000_000_000, 12.5),
                    RouteCoordinate(53.56, 10.01, 1_700_000_005_000)
                )
            ),
            stops = listOf(InterchangeStop("Hauptbahnhof", 53.55, 9.99, 0))
        )
        val output = ByteArrayOutputStream()
        GpxCodec.write(route, output)

        val imported = GpxCodec.read(ByteArrayInputStream(output.toByteArray())).single()

        assertEquals(route.title, imported.title)
        assertEquals(route.points.map(RouteCoordinate::latitude), imported.points.map(RouteCoordinate::latitude))
        assertEquals(1_700_000_000_000, imported.points.first().timeEpochMillis)
        assertEquals("Hauptbahnhof", imported.stops.single().name)
        assertEquals(12.5, imported.points.first().elevationMeters!!, 0.001)
    }

    @Test
    fun gpxSegmentsRemainSeparateRoutes() {
        val gpx = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><name>Тест</name>
                <trkseg><trkpt lat="53.5" lon="10.0"/><trkpt lat="53.6" lon="10.1"/></trkseg>
                <trkseg><trkpt lat="54.0" lon="11.0"/><trkpt lat="54.1" lon="11.1"/></trkseg>
              </trk>
            </gpx>
        """.trimIndent().toByteArray()
        val routes = GpxCodec.read(ByteArrayInputStream(gpx))
        assertEquals(2, routes.size)
        assertEquals(2, routes[0].points.size)
        assertEquals(53.5, routes[0].points.first().latitude, 0.0001)
        assertEquals(54.0, routes[1].points.first().latitude, 0.0001)
    }

    @Test
    fun gpxDoctypeIsRejected() {
        val gpx = """
            <!DOCTYPE gpx [<!ENTITY external SYSTEM "file:///etc/passwd">]>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata><name>&external;</name></metadata>
              <trk><trkseg>
                <trkpt lat="53.5" lon="10.0"/>
                <trkpt lat="53.6" lon="10.1"/>
              </trkseg></trk>
            </gpx>
        """.trimIndent()
        val result = runCatching {
            GpxCodec.read(ByteArrayInputStream(gpx.toByteArray()))
        }

        assertTrue("GPX with a DOCTYPE must be rejected", result.isFailure)
    }

    @Test
    fun geoJsonRoundTripUsesLongitudeLatitudeAndStopSequence() {
        val route = InterchangeRoute(
            title = "Ring",
            segments = listOf(
                listOf(RouteCoordinate(53.5, 10.0), RouteCoordinate(53.6, 10.1))
            ),
            stops = listOf(InterchangeStop("ZOB", 53.6, 10.1, 0)),
            colorArgb = 0xFF123456.toInt()
        )
        val output = ByteArrayOutputStream()
        GeoJsonCodec.write(route, output)
        val json = output.toString(Charsets.UTF_8.name())

        assertTrue(json.contains("[10.0,53.5]"))
        val imported = GeoJsonCodec.read(ByteArrayInputStream(output.toByteArray())).single()
        assertEquals(route.title, imported.title)
        assertEquals(route.points.first(), imported.points.first())
        assertEquals(0xFF123456.toInt(), imported.colorArgb)
        assertEquals("ZOB", imported.stops.single().name)
    }

    @Test
    fun multiLineStringCreatesIndependentCandidates() {
        val json = """
            {"type":"Feature","properties":{"name":"Zwei Teile"},"geometry":{"type":"MultiLineString",
            "coordinates":[[[10.0,53.5],[10.1,53.6]],[[11.0,54.0],[11.1,54.1]]]}}
        """.trimIndent()
        val routes = GeoJsonCodec.read(ByteArrayInputStream(json.toByteArray()))
        assertEquals(2, routes.size)
        assertEquals(53.5, routes[0].points.first().latitude, 0.0001)
        assertEquals(54.0, routes[1].points.first().latitude, 0.0001)
    }
}
