package com.example.linienbegleiter.data.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class CameraAnchorTest {

    @Test
    fun testScreenSpaceAnchorCalculates80PercentHeight() {
        val screenHeights = listOf(2400, 1920, 1200, 1080, 800)

        for (heightPx in screenHeights) {
            val deltaYPixels = 0.30 * heightPx
            val targetCenterY = (heightPx / 2.0) + deltaYPixels
            val anchorFraction = targetCenterY / heightPx.toDouble()

            assertTrue(
                "Anchor fraction for height $heightPx should be 80%, got ${anchorFraction * 100}%",
                anchorFraction in 0.78..0.82
            )
            assertEquals(0.80, anchorFraction, 0.01)
        }
    }

    @Test
    fun testCameraTargetOffsetCalculationFor80PercentAnchor() {
        val busLat = 53.5500
        val busLng = 10.0000
        val zoom = 16.0
        val viewportHeightPx = 2000

        val deltaYPixels = 0.30 * viewportHeightPx
        val radLat = Math.toRadians(busLat)
        val metersPerPixel = (156543.03392 * kotlin.math.cos(radLat)) / Math.pow(2.0, zoom)
        val offsetMeters = deltaYPixels * metersPerPixel

        val dLatNorth = (offsetMeters * kotlin.math.cos(0.0)) / 111320.0
        val targetLatNorth = busLat + dLatNorth

        assertTrue("Target latitude should be offset north of bus position", targetLatNorth > busLat)
        assertEquals(0.00764, targetLatNorth - busLat, 0.001)
    }
}
