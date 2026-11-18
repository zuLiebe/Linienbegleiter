package com.example.linienbegleiter.data.interchange

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GtfsCodecTest {
    @Test
    fun csvSupportsBomQuotedCommasAndEscapedQuotes() {
        val table = GtfsCsvCodec.parse(
            "\uFEFFname,description\r\n\"Route, \"\"A\"\"\",\"first line\nsecond line\"\r\n"
        )
        assertEquals("Route, \"A\"", table.rows.single()["name"])
        assertEquals("first line\nsecond line", table.rows.single()["description"])
        assertEquals(
            "name,description\r\n\"Route, \"\"A\"\"\",\"first line\nsecond line\"\r\n",
            GtfsCsvCodec.write(table)
        )
    }

    @Test
    fun importAndExportGtfsKeepsTripStopsShapeScheduleAndRouteColor() {
        val files = linkedMapOf(
            "agency.txt" to "agency_id,agency_name,agency_url,agency_timezone\r\nhvv,HVV,https://example.test,Europe/Berlin\r\n",
            "routes.txt" to "route_id,agency_id,route_short_name,route_long_name,route_type,route_color\r\nr1,hvv,5,\"City, South\",3,123456\r\n",
            "trips.txt" to "route_id,service_id,trip_id,direction_id,shape_id\r\nr1,wk,t1,0,s1\r\n",
            "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\r\nsA,\"Central, Station\",53.5,10.0\r\nsB,Harbor,53.6,10.1\r\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\r\nt1,08:00:00,08:00:00,sA,1\r\nt1,08:10:00,08:10:00,sB,2\r\n",
            "shapes.txt" to "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\r\ns1,53.51,10.01,1\r\ns1,53.59,10.09,2\r\n",
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\r\nwk,1,1,1,1,1,0,0,20260101,20261231\r\n"
        )
        val input = zip(files)
        val imported = GtfsCodec.read(ByteArrayInputStream(input)).single()

        assertEquals("5 — City, South • Направление 0", imported.title)
        assertEquals(2, imported.points.size)
        assertEquals(2, imported.stops.size)
        assertEquals("Central, Station", imported.stops.first().name)
        assertEquals(0xFF123456.toInt(), imported.colorArgb)
        assertTrue(imported.metadataJson.orEmpty().contains("calendar.txt"))

        val exportedBytes = ByteArrayOutputStream()
        GtfsCodec.write(imported, exportedBytes)
        val reimported = GtfsCodec.read(ByteArrayInputStream(exportedBytes.toByteArray())).single()
        assertEquals(imported.points, reimported.points)
        assertEquals(imported.stops, reimported.stops)
        assertEquals(imported.colorArgb, reimported.colorArgb)
    }

    @Test
    fun editedGeometryIsWrittenIntoShapesWithoutChangingScheduleRows() {
        val source = gtfsFeedWithShape()
        val imported = GtfsCodec.read(ByteArrayInputStream(source)).single()
        val editedPoints = listOf(
            RouteCoordinate(53.52, 10.02),
            RouteCoordinate(53.57, 10.04),
            RouteCoordinate(53.60, 10.08)
        )
        val output = ByteArrayOutputStream()
        GtfsCodec.write(imported.copy(segments = listOf(editedPoints)), output)
        val result = GtfsCodec.read(ByteArrayInputStream(output.toByteArray())).single()

        assertEquals(editedPoints, result.points)
        assertEquals(imported.stops, result.stops)
    }

    @Test(expected = IllegalArgumentException::class)
    fun gtfsExportRejectsEditedStopsInsteadOfKeepingStaleTimetableLinks() {
        val imported = GtfsCodec.read(ByteArrayInputStream(gtfsFeedWithShape())).single()
        val changedStops = imported.stops.mapIndexed { index, stop ->
            if (index == 0) stop.copy(name = "Перенесённая остановка") else stop
        }
        GtfsCodec.write(imported.copy(stops = changedStops), ByteArrayOutputStream())
    }

    @Test
    fun importWithoutShapesKeepsStopsAndWarnsInsteadOfCreatingGeometry() {
        val files = linkedMapOf(
            "agency.txt" to "agency_name,agency_url,agency_timezone\r\nAgency,https://example.test,Europe/Berlin\r\n",
            "routes.txt" to "route_id,route_type,route_short_name\r\nr1,3,7\r\n",
            "trips.txt" to "route_id,service_id,trip_id,direction_id\r\nr1,wk,t1,1\r\n",
            "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\r\ns1,Start,53.5,10.0\r\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\r\nt1,08:00:00,08:00:00,s1,1\r\n",
            "calendar_dates.txt" to "service_id,date,exception_type\r\nwk,20260101,1\r\n"
        )
        val route = GtfsCodec.read(ByteArrayInputStream(zip(files))).single()
        assertTrue(route.points.isEmpty())
        assertEquals(1, route.stops.size)
        assertTrue(route.warnings.any { it.contains("shapes.txt") })
    }

    @Test(expected = IllegalStateException::class)
    fun exportWithoutOriginalScheduleMetadataIsRejected() {
        GtfsCodec.write(
            InterchangeRoute(
                title = "No timetable",
                segments = listOf(
                    listOf(RouteCoordinate(53.5, 10.0), RouteCoordinate(53.6, 10.1))
                )
            ),
            ByteArrayOutputStream()
        )
    }

    private fun gtfsFeedWithShape(): ByteArray = zip(
        linkedMapOf(
            "agency.txt" to "agency_id,agency_name,agency_url,agency_timezone\r\nhvv,HVV,https://example.test,Europe/Berlin\r\n",
            "routes.txt" to "route_id,agency_id,route_short_name,route_long_name,route_type,route_color\r\nr1,hvv,5,\"City, South\",3,123456\r\n",
            "trips.txt" to "route_id,service_id,trip_id,direction_id,shape_id\r\nr1,wk,t1,0,s1\r\n",
            "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\r\nsA,\"Central, Station\",53.5,10.0\r\nsB,Harbor,53.6,10.1\r\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\r\nt1,08:00:00,08:00:00,sA,1\r\nt1,08:10:00,08:10:00,sB,2\r\n",
            "shapes.txt" to "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\r\ns1,53.51,10.01,1\r\ns1,53.59,10.09,2\r\n",
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\r\nwk,1,1,1,1,1,0,0,20260101,20261231\r\n"
        )
    )

    private fun zip(files: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, contents) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
