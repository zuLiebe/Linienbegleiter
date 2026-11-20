package com.example.linienbegleiter.data.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OffRouteDetectionTest {

    @Test
    fun testRouteProgressCalculatorCalculatesDeviation() {
        val points = listOf(
            FollowingRoutePoint(0, 53.5500, 10.0000),
            FollowingRoutePoint(1, 53.5500, 10.0100)
        )
        // Position directly on line
        val onLineSnapshot = RouteProgressCalculator.calculate(
            points = points,
            stops = emptyList(),
            latitude = 53.5500,
            longitude = 10.0050,
            previousProgressMeters = 0.0
        )
        assertNotNull(onLineSnapshot.distanceToRouteMeters)
        assertEquals(0.0, onLineSnapshot.distanceToRouteMeters!!, 1.0)

        // Position 100 meters north of line
        // 0.0009 degrees latitude is ~100m
        val offLineSnapshot = RouteProgressCalculator.calculate(
            points = points,
            stops = emptyList(),
            latitude = 53.5509,
            longitude = 10.0050,
            previousProgressMeters = 0.0
        )
        assertNotNull(offLineSnapshot.distanceToRouteMeters)
        assertTrue(
            "Deviation should be around 100 meters, got: ${offLineSnapshot.distanceToRouteMeters}",
            offLineSnapshot.distanceToRouteMeters!! in 90.0..110.0
        )
    }

    @Test
    fun testOffRouteThresholdValues() {
        val defaultThreshold = 25f
        val minThreshold = 10f
        val maxThreshold = 100f
        val step = 5f

        assertTrue(defaultThreshold in minThreshold..maxThreshold)
        assertEquals(0f, (defaultThreshold - minThreshold) % step, 0.001f)
    }
}
