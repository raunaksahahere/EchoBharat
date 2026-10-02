package com.echobharat.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The range rules decide whether a distress call reaches someone, so the failure modes
 * matter more than the happy path: a missing GPS fix must never silently drop traffic,
 * and GPS must never *extend* reach beyond the hop ceiling.
 */
class RangePolicyTest {

    @Test
    fun `within the hop ceiling is in range`() {
        assertTrue(RangePolicy.isInRange(hops = 1, origin = null, here = null))
        assertTrue(RangePolicy.isInRange(hops = RangePolicy.MAX_HOPS, origin = null, here = null))
    }

    @Test
    fun `beyond the hop ceiling is out of range`() {
        val verdict = RangePolicy.evaluate(RangePolicy.MAX_HOPS + 1, null, null)
        assertTrue(verdict is RangePolicy.Verdict.OutOfRange)
    }

    @Test
    fun `unknown hop count does not drop the message`() {
        // We would rather show a message of uncertain distance than lose it.
        assertTrue(RangePolicy.isInRange(hops = null, origin = null, here = null))
    }

    @Test
    fun `origin without a local fix falls back to hop count`() {
        // GPS-denied receiver: hop count alone governs, coordinates are ignored.
        val faraway = RangePolicy.Origin(lat = 0.0, lon = 0.0)
        assertTrue(RangePolicy.isInRange(hops = 2, origin = faraway, here = null))
    }

    @Test
    fun `haversine distance is accurate for a known pair`() {
        // Delhi (28.6139, 77.2090) to Mumbai (19.0760, 72.8777) is ~1150 km.
        val metres = RangePolicy.distanceMeters(28.6139, 77.2090, 19.0760, 72.8777)
        assertEquals(1_150_000.0, metres, 20_000.0)
    }

    @Test
    fun `zero distance for identical coordinates`() {
        assertEquals(0.0, RangePolicy.distanceMeters(28.6139, 77.2090, 28.6139, 77.2090), 1e-6)
    }

    @Test
    fun `formatDistance switches units at a kilometre`() {
        assertEquals("500 m", RangePolicy.formatDistance(500.0))
        assertEquals("1.5 km", RangePolicy.formatDistance(1500.0))
    }
}

class RangePolicyBearingTest {
    @org.junit.Test
    fun `bearing points the right way`() {
        // Due north, east, south, west of the origin.
        org.junit.Assert.assertEquals(0.0, RangePolicy.bearingDegrees(0.0, 0.0, 1.0, 0.0), 0.5)
        org.junit.Assert.assertEquals(90.0, RangePolicy.bearingDegrees(0.0, 0.0, 0.0, 1.0), 0.5)
        org.junit.Assert.assertEquals(180.0, RangePolicy.bearingDegrees(0.0, 0.0, -1.0, 0.0), 0.5)
        org.junit.Assert.assertEquals(270.0, RangePolicy.bearingDegrees(0.0, 0.0, 0.0, -1.0), 0.5)
    }

    @org.junit.Test
    fun `compass names cover the circle and wrap at north`() {
        org.junit.Assert.assertEquals("N", RangePolicy.compassPoint(0.0))
        org.junit.Assert.assertEquals("N", RangePolicy.compassPoint(359.0))
        org.junit.Assert.assertEquals("NE", RangePolicy.compassPoint(45.0))
        org.junit.Assert.assertEquals("S", RangePolicy.compassPoint(180.0))
        org.junit.Assert.assertEquals("W", RangePolicy.compassPoint(271.0))
    }
}
