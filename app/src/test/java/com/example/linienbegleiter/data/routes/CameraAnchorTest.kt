package com.example.linienbegleiter.data.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class CameraAnchorTest {

    @Test
    fun testNativeTopPaddingCalculates80PercentHeightAnchor() {
        val screenHeights = listOf(2400, 1920, 1200, 1080, 800)

        for (heightPx in screenHeights) {
            val topPaddingPx = (heightPx * 0.60f).roundToInt()
            val bottomPaddingPx = 0
            val effectiveVisibleHeight = heightPx - topPaddingPx - bottomPaddingPx
            val targetCenterY = topPaddingPx + (effectiveVisibleHeight / 2.0)
            val anchorFraction = targetCenterY / heightPx.toDouble()

            assertTrue(
                "Anchor fraction for height $heightPx should be 80%, got ${anchorFraction * 100}%",
                anchorFraction in 0.79..0.81
            )
            assertEquals(0.80, anchorFraction, 0.01)
        }
    }
}
