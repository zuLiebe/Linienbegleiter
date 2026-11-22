package com.example.linienbegleiter.data.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StreetTrackerTest {

    @Test
    fun testStreetConfirmationRequires60Seconds() {
        val tracker = StreetTracker(confirmationDurationMillis = 60_000L)
        val startTime = 100_000L

        // Initial location on Street A
        var result = tracker.onLocationUpdate("Street A", startTime)
        assertNull("Should not confirm immediately on first fix", result.confirmedStreet)
        assertNull(result.newAnnouncement)

        // 30 seconds on Street A
        result = tracker.onLocationUpdate("Street A", startTime + 30_000L)
        assertNull("Should not confirm before 60 seconds", result.confirmedStreet)
        assertNull(result.newAnnouncement)

        // 60 seconds on Street A -> Confirmed & Announced!
        result = tracker.onLocationUpdate("Street A", startTime + 60_000L)
        assertEquals("Street A", result.confirmedStreet)
        assertNotNull("Should create announcement event on confirmation", result.newAnnouncement)
        assertEquals("Street A", result.newAnnouncement?.streetName)
    }

    @Test
    fun testGPSJitterDoesNotTriggerPrematureAnnouncement() {
        val tracker = StreetTracker(confirmationDurationMillis = 60_000L)
        val startTime = 100_000L

        // Confirmed on Street A
        tracker.onLocationUpdate("Street A", startTime)
        tracker.onLocationUpdate("Street A", startTime + 60_000L)

        // GPS jumps to Parallel Street B for 20 seconds, then returns to Street A
        tracker.onLocationUpdate("Street B", startTime + 70_000L)
        tracker.onLocationUpdate("Street B", startTime + 80_000L)

        // Returns to Street A before 60 seconds on Street B elapsed
        val result = tracker.onLocationUpdate("Street A", startTime + 90_000L)
        assertEquals("Street A", result.confirmedStreet)
        assertNull("Should not announce Parallel Street B due to brief jitter", result.newAnnouncement)
    }

    @Test
    fun testReenteringPreviousStreetAnnouncesAgainAfter60Seconds() {
        val tracker = StreetTracker(confirmationDurationMillis = 60_000L)
        var time = 100_000L

        // Confirmed on Street A
        tracker.onLocationUpdate("Street A", time)
        tracker.onLocationUpdate("Street A", time + 60_000L)
        time += 60_000L

        // Moves to Street B and stays for 60s
        tracker.onLocationUpdate("Street B", time)
        var result = tracker.onLocationUpdate("Street B", time + 60_000L)
        assertEquals("Street B", result.confirmedStreet)
        assertEquals("Street B", result.newAnnouncement?.streetName)
        time += 60_000L

        // Returns to Street A and stays for 60s -> Should announce Street A again!
        tracker.onLocationUpdate("Street A", time)
        result = tracker.onLocationUpdate("Street A", time + 60_000L)
        assertEquals("Street A", result.confirmedStreet)
        assertEquals("Street A", result.newAnnouncement?.streetName)
    }
}
