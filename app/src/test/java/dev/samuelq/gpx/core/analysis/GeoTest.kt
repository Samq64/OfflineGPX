package dev.samuelq.gpx.core.analysis

import dev.samuelq.gpx.core.model.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals

class GeoTest {

    private fun at(latitude: Double, longitude: Double) = TrackPoint(latitude = latitude, longitude = longitude)

    @Test
    fun `bearings run clockwise from north`() {
        val origin = at(51.5, -0.1)
        assertEquals(0.0, bearingDegrees(origin, at(51.6, -0.1)), 1e-6)
        assertEquals(90.0, bearingDegrees(origin, at(51.5, 0.0)), 0.1)
        assertEquals(180.0, bearingDegrees(origin, at(51.4, -0.1)), 1e-6)
        assertEquals(270.0, bearingDegrees(origin, at(51.5, -0.2)), 0.1)
    }
}
