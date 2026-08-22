package com.js8call.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaidenheadTest {

    @Test
    fun validatesStandardLocators() {
        assertTrue(Maidenhead.isValid("FN"))
        assertTrue(Maidenhead.isValid("EM73"))
        assertTrue(Maidenhead.isValid("FN31pr"))
        assertTrue(Maidenhead.isValid("FN31PR21"))
        assertFalse(Maidenhead.isValid(""))
        assertFalse(Maidenhead.isValid("EM7"))
        assertFalse(Maidenhead.isValid("EM735"))
        assertFalse(Maidenhead.isValid("ZZ99"))
        assertFalse(Maidenhead.isValid("E1"))
        assertFalse(Maidenhead.isValid("FN31YZ"))
        assertFalse(Maidenhead.isValid("RR73!"))
    }

    @Test
    fun locatorCenterMatchesKnownCoordinates() {
        // W1AW's subsquare. Center values computed by hand from the
        // standard: field F/N, square 3/1, subsquare P/R.
        val w1aw = Maidenhead.toLatLon("FN31PR")!!
        assertEquals(41.729167, w1aw.lat, 1e-6)
        assertEquals(-72.708333, w1aw.lon, 1e-6)

        val em73 = Maidenhead.toLatLon("EM73")!!
        assertEquals(33.5, em73.lat, 1e-9)
        assertEquals(-85.0, em73.lon, 1e-9)

        // Lower case is the older QRA convention and must parse the same.
        assertEquals(w1aw, Maidenhead.toLatLon("fn31pr"))
    }

    @Test
    fun invalidLocatorHasNoCoordinates() {
        assertNull(Maidenhead.toLatLon("EM7"))
        assertNull(Maidenhead.toLatLon("not a grid"))
    }

    @Test
    fun coordinatesRoundTripThroughTheLocator() {
        // fromLatLon names the square; the square's center re-encodes to
        // the same square. DFW, Sydney, Reykjavik, and a south-west
        // hemisphere point cover the sign quadrants.
        for (grid in listOf("EM12FV42", "QF56OD55", "HP94AD11", "GF15VC00")) {
            val c = Maidenhead.toLatLon(grid)!!
            assertEquals(grid, Maidenhead.fromLatLon(c.lat, c.lon))
        }
    }

    @Test
    fun distanceAndBearingMatchIndependentComputation() {
        // Reference values computed independently with the same published
        // haversine and bearing formulas.
        val em12 = Maidenhead.toLatLon("EM12")!!
        val em73 = Maidenhead.toLatLon("EM73")!!
        assertEquals(1123.9, Maidenhead.distanceKm(em12, em73), 0.1)
        assertEquals(81.1, Maidenhead.bearingDegrees(em12, em73), 0.1)

        val fn31 = Maidenhead.toLatLon("FN31")!!
        val io91 = Maidenhead.toLatLon("IO91")!!
        assertEquals(5392.7, Maidenhead.distanceKm(fn31, io91), 0.1)
        assertEquals(52.2, Maidenhead.bearingDegrees(fn31, io91), 0.1)
    }

    @Test
    fun cardinalBearings() {
        val origin = Maidenhead.LatLon(0.0, 0.0)
        assertEquals(0.0, Maidenhead.bearingDegrees(origin, Maidenhead.LatLon(10.0, 0.0)), 1e-9)
        assertEquals(90.0, Maidenhead.bearingDegrees(origin, Maidenhead.LatLon(0.0, 10.0)), 1e-9)
        assertEquals(180.0, Maidenhead.bearingDegrees(origin, Maidenhead.LatLon(-10.0, 0.0)), 1e-9)
        assertEquals(270.0, Maidenhead.bearingDegrees(origin, Maidenhead.LatLon(0.0, -10.0)), 1e-9)
    }

    @Test
    fun compassPointsCoverTheCircle() {
        assertEquals("N", Maidenhead.compassPoint(0.0))
        assertEquals("N", Maidenhead.compassPoint(359.0))
        assertEquals("NE", Maidenhead.compassPoint(45.0))
        assertEquals("E", Maidenhead.compassPoint(81.1))
        assertEquals("S", Maidenhead.compassPoint(180.0))
        assertEquals("NW", Maidenhead.compassPoint(303.0))
    }

    @Test
    fun describePathFormatsBothUnitsAndDirection() {
        assertEquals("1,124 km / 698 mi · E 81°", Maidenhead.describePath("EM12", "EM73"))
        assertNull(Maidenhead.describePath(null, "EM73"))
        assertNull(Maidenhead.describePath("EM12", ""))
        assertNull(Maidenhead.describePath("EM12", "bogus"))
    }
}
