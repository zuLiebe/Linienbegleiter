package com.example.linienbegleiter

import com.example.linienbegleiter.data.recording.RecordingStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStopOverlayTest {
    @Test
    fun mapsEveryEditorAndRecordingStopToOneGeographicMarker() {
        val items = buildMapStopOverlayItems(
            recordingStops = listOf(RecordingStop(0, "Depot", 53.5, 10.0, 1L)),
            routeStops = listOf(
                MapRouteStop(0, "Start", 53.51, 10.01, isStart = true),
                MapRouteStop(1, "", 53.52, 10.02, isNext = true)
            )
        )

        assertEquals(3, items.size)
        assertEquals("Depot", items[0].name)
        assertEquals(53.5, items[0].latitude, 0.0)
        assertEquals(10.0, items[0].longitude, 0.0)
        assertEquals("Start", items[1].name)
        assertEquals(53.51, items[1].latitude, 0.0)
        assertEquals(10.01, items[1].longitude, 0.0)
        assertTrue(items[2].isNext)
        assertFalse(items[2].name.isNotBlank())
    }

    @Test
    fun excludesInvalidCoordinatesWithoutChangingValidStops() {
        val items = buildMapStopOverlayItems(
            recordingStops = emptyList(),
            routeStops = listOf(
                MapRouteStop(0, "Valid", 53.5, 10.0),
                MapRouteStop(1, "Invalid latitude", 91.0, 10.0),
                MapRouteStop(2, "Invalid longitude", 53.5, Double.NaN)
            )
        )

        assertEquals(1, items.size)
        assertEquals("Valid", items.single().name)
    }
}
