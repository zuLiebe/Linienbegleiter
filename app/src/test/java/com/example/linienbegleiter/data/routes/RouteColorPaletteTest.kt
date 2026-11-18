package com.example.linienbegleiter.data.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteColorPaletteTest {
    @Test
    fun automaticColorsAreStableAndWithinPalette() {
        val routeId = "route-123"

        assertEquals(
            RouteColorPalette.automaticFor(routeId),
            RouteColorPalette.automaticFor(routeId)
        )
        assertTrue(RouteColorPalette.colors.contains(RouteColorPalette.automaticFor(routeId)))
    }

    @Test
    fun mapLibreColorOmitsAlphaAndUsesHexRgb() {
        assertEquals("#C62835", RouteColorPalette.toMapLibreColor(0xFFC62835.toInt()))
    }

    @Test
    fun distinctRouteIdsCanReceiveDifferentColors() {
        assertNotEquals(
            RouteColorPalette.automaticFor("manual-1"),
            RouteColorPalette.automaticFor("manual-2")
        )
    }
}
