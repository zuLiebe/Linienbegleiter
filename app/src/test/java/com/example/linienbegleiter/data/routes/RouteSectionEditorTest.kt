package com.example.linienbegleiter.data.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSectionEditorTest {
    @Test
    fun movingSparseLineAddsLocalShapeWithoutMovingRouteEndpoints() {
        val points = listOf(
            EditableRoutePoint(0, 53.5500, 10.0000),
            EditableRoutePoint(1, 53.5500, 10.0030)
        )
        val selection = requireNotNull(selectRouteSection(points, 0, 0.5, radiusMeters = 55.0))

        val moved = moveRouteSection(
            points,
            selection,
            RouteCoordinate(latitude = 53.5507, longitude = 10.0015)
        )

        assertTrue("Sparse route should gain editable geometry", moved.size > points.size)
        assertEquals(points.first().latitude, moved.first().latitude, 0.0)
        assertEquals(points.first().longitude, moved.first().longitude, 0.0)
        assertEquals(points.last().latitude, moved.last().latitude, 0.0)
        assertEquals(points.last().longitude, moved.last().longitude, 0.0)
        assertTrue("The dragged center should move toward the target", moved.maxOf { it.latitude } > 53.5501)
        assertEquals(moved.indices.toList(), moved.map { it.sequence })
    }

    @Test
    fun movingDenseGpsSectionLeavesGeometryOutsideWindowUntouched() {
        val points = (0..100).map { index ->
            EditableRoutePoint(index, 53.55, 10.0 + index * 0.0001)
        }
        val selection = requireNotNull(selectRouteSection(points, 50, 0.5, radiusMeters = 85.0))
        val moved = moveRouteSection(
            points,
            selection,
            RouteCoordinate(latitude = 53.5504, longitude = 10.00505)
        )

        assertEquals(points[0].latitude, moved[0].latitude, 0.0)
        assertEquals(points[0].longitude, moved[0].longitude, 0.0)
        assertTrue(
            "Geometry before the edit window should stay unchanged",
            moved.any { it.latitude == points[20].latitude && it.longitude == points[20].longitude }
        )
        assertTrue(
            "Geometry after the edit window should stay unchanged",
            moved.any { it.latitude == points[80].latitude && it.longitude == points[80].longitude }
        )
        assertEquals(points.last().latitude, moved.last().latitude, 0.0)
        assertEquals(points.last().longitude, moved.last().longitude, 0.0)
        assertTrue(moved.any { it.latitude > 53.55001 })
        assertEquals(moved.indices.toList(), moved.map { it.sequence })
    }

    @Test
    fun selectedSectionIncludesBoundariesAndCenter() {
        val points = (0..30).map { index ->
            EditableRoutePoint(index, 53.55, 10.0 + index * 0.0001)
        }
        val selection = requireNotNull(selectRouteSection(points, 15, 0.5, radiusMeters = 60.0))

        val section = routeSectionPoints(points, selection)

        assertTrue(section.size >= 3)
        assertEquals(section.indices.toList(), section.map { it.sequence })
        assertNotEquals(points.first().longitude, section.first().longitude)
        assertNotEquals(points.last().longitude, section.last().longitude)
    }

    @Test
    fun deletingMiddleSectionShortensDetourAndKeepsRouteEndpoints() {
        val points = listOf(
            EditableRoutePoint(0, 53.5500, 10.0000),
            EditableRoutePoint(1, 53.5550, 10.0150),
            EditableRoutePoint(2, 53.5500, 10.0300)
        )
        val selection = requireNotNull(selectRouteSection(points, 0, 1.0, radiusMeters = 400.0))

        val shortened = requireNotNull(deleteRouteSection(points, selection))

        assertEquals(4, shortened.size)
        assertEquals(points.first().latitude, shortened.first().latitude, 0.0)
        assertEquals(points.first().longitude, shortened.first().longitude, 0.0)
        assertEquals(points.last().latitude, shortened.last().latitude, 0.0)
        assertEquals(points.last().longitude, shortened.last().longitude, 0.0)
        assertTrue(approximateLength(shortened) < approximateLength(points))
        assertEquals(shortened.indices.toList(), shortened.map { it.sequence })
    }

    private fun approximateLength(points: List<EditableRoutePoint>): Double =
        points.zipWithNext().sumOf { (first, second) ->
            val meanLatitudeRadians = Math.toRadians((first.latitude + second.latitude) / 2.0)
            val northMeters = (second.latitude - first.latitude) * 111_000.0
            val eastMeters = (second.longitude - first.longitude) *
                111_000.0 * kotlin.math.cos(meanLatitudeRadians)
            kotlin.math.hypot(northMeters, eastMeters)
        }

    @Test
    fun deletingWholeShortRouteIsRejected() {
        val points = listOf(
            EditableRoutePoint(0, 53.5500, 10.0000),
            EditableRoutePoint(1, 53.5500, 10.0002)
        )
        val selection = requireNotNull(selectRouteSection(points, 0, 0.5, radiusMeters = 100.0))

        assertEquals(null, deleteRouteSection(points, selection))
    }

    @Test
    fun invalidSegmentDoesNotCreateSelection() {
        val points = listOf(
            EditableRoutePoint(0, 53.55, 10.0),
            EditableRoutePoint(1, 53.56, 10.01)
        )

        assertEquals(null, selectRouteSection(points, segmentSequence = 2, segmentFraction = 0.5))
        assertEquals(null, selectRouteSection(points, segmentSequence = 0, segmentFraction = Double.NaN))
    }

    @Test
    fun mergingRouteSectionsExpandsSelectionToCoverAllTappedSegments() {
        val points = (0..100).map { index ->
            EditableRoutePoint(index, 53.55, 10.0 + index * 0.0001)
        }
        val totalDist = routeTotalDistanceMeters(points)
        val selection1 = requireNotNull(selectRouteSection(points, 10, 0.5, radiusMeters = 50.0))
        val selection2 = requireNotNull(selectRouteSection(points, 80, 0.5, radiusMeters = 50.0))

        val merged = mergeRouteSections(selection1, selection2, totalDist)

        assertEquals(selection1.startDistanceMeters, merged.startDistanceMeters, 0.001)
        assertEquals(selection2.endDistanceMeters, merged.endDistanceMeters, 0.001)
        assertTrue("Merged section length should cover from start of first selection to end of second", merged.lengthMeters > selection1.lengthMeters)

        val shortened = requireNotNull(deleteRouteSection(points, merged))
        assertTrue("Deleting merged section removes all selected segments at once", shortened.size < points.size)
    }
}
