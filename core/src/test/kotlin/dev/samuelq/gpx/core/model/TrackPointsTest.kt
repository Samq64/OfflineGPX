package dev.samuelq.gpx.core.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TrackPointsTest {

    @Test
    fun `a snapshot is unchanged by later appends, including past a regrow`() {
        val builder = TrackPointsBuilder(capacity = 2)
        builder.add(1.0, 1.0)
        val before = builder.snapshot()

        builder.startSegment()
        repeat(10) { builder.add(2.0, 2.0) }

        assertEquals(1, before.size)
        assertEquals(1, before.segmentCount)
        assertEquals(1.0, before.latitude(0))
        assertFailsWith<IndexOutOfBoundsException> { before.latitude(1) }
        assertEquals(11, builder.snapshot().size)
        assertContentEquals(intArrayOf(0, 1), builder.snapshot().segmentStarts())
    }

    @Test
    fun `a build keeps appending into fresh arrays, leaving it unchanged`() {
        val builder = TrackPointsBuilder(capacity = 8)
        builder.add(1.0, 1.0)
        val built = builder.build()
        builder.add(2.0, 2.0)

        assertEquals(1, built.size)
        assertEquals(2, builder.snapshot().size)
        assertEquals(2.0, builder.snapshot().latitude(1))
    }

    @Test
    fun `a builder still grows after an empty build`() {
        val builder = TrackPointsBuilder()
        builder.build()
        builder.add(1.0, 1.0)
        assertEquals(1, builder.build().segmentCount)
    }

    @Test
    fun `empty and repeated segment starts add no segment`() {
        val points = TrackPoints.of(emptyList(), listOf(TrackPoint(1.0, 1.0)), emptyList(), listOf(TrackPoint(2.0, 2.0)))

        assertContentEquals(intArrayOf(0, 1), points.segmentStarts())
        assertEquals(1, points.segmentEnd(0))
        assertEquals(2, points.segmentEnd(1))
    }

    @Test
    fun `absent values read back as absent`() {
        val at = Instant.parse("2026-05-01T08:00:00Z")
        val points = TrackPoints.of(
            listOf(TrackPoint(1.0, 2.0), TrackPoint(3.0, 4.0, elevation = 5.0, time = at, accuracyMeters = 6.0)),
        )

        assertEquals(TrackPoint(1.0, 2.0), points[0])
        assertEquals(TrackPoint(3.0, 4.0, 5.0, at, 6.0), points[1])
        assertNull(points.getOrNull(2))
    }
}
