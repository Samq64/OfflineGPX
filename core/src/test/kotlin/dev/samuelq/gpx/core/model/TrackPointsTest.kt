package dev.samuelq.gpx.core.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        val points = TrackPoints.of(
            emptyList(),
            listOf(TrackPoint(1.0, 1.0)),
            emptyList(),
            listOf(TrackPoint(2.0, 2.0)),
        )

        assertContentEquals(intArrayOf(0, 1), points.segmentStarts())
        assertEquals(1, points.segmentEnd(0))
        assertEquals(2, points.segmentEnd(1))
    }

    @Test
    fun `a slice keeps the segment breaks inside it`() {
        val points = TrackPoints.of(
            listOf(TrackPoint(1.0, 0.0), TrackPoint(2.0, 0.0), TrackPoint(3.0, 0.0)),
            listOf(TrackPoint(4.0, 0.0), TrackPoint(5.0, 0.0)),
        )
        val slice = points.slice(1..3)

        assertEquals(listOf(2.0, 3.0, 4.0), slice.indices.map(slice::latitude))
        assertContentEquals(intArrayOf(0, 2), slice.segmentStarts())
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

    @Test
    fun `reads are bounds-checked on both sides`() {
        val points = TrackPoints.of(listOf(TrackPoint(1.0, 2.0), TrackPoint(3.0, 4.0)))

        assertFailsWith<IndexOutOfBoundsException> { points.longitude(-1) }
        assertFailsWith<IndexOutOfBoundsException> { points.elevation(2) }
        assertNull(points.getOrNull(-1))
        assertEquals(TrackPoint(1.0, 2.0), points.first())
        assertEquals(TrackPoint(3.0, 4.0), points.last())
        assertEquals(points.last(), points.lastOrNull())
        assertFalse(points.hasTime(0))
    }

    @Test
    fun `the empty track has nothing to read`() {
        assertEquals(0, TrackPoints.EMPTY.size)
        assertEquals(0, TrackPoints.EMPTY.segmentCount)
        assertNull(TrackPoints.EMPTY.lastOrNull())
        assertTrue(Track(name = null, points = TrackPoints.EMPTY).isEmpty)
        assertFalse(Track(name = null, points = TrackPoints.of(listOf(TrackPoint(0.0, 0.0)))).isEmpty)
    }

    @Test
    fun `segment starts can be replaced without copying points`() {
        val points = TrackPoints.of(listOf(TrackPoint(1.0, 0.0), TrackPoint(2.0, 0.0), TrackPoint(3.0, 0.0)))
        val cut = points.withSegmentStarts(intArrayOf(0, 2))

        assertEquals(2, cut.segmentCount)
        assertEquals(2, cut.segmentStart(1))
        assertEquals(2, cut.segmentEnd(0))
        assertEquals(3.0, cut.latitude(2))
    }

    @Test
    fun `many segments grow the start table`() {
        val builder = TrackPointsBuilder()
        repeat(20) {
            builder.startSegment()
            builder.add(it.toDouble(), 0.0)
        }
        val points = builder.build()

        assertEquals(20, points.segmentCount)
        assertContentEquals(IntArray(20) { it }, points.segmentStarts())
    }

    @Test
    fun `coordinates are valid only within the globe`() {
        assertTrue(isValidCoordinate(90.0, -180.0))
        assertTrue(isValidCoordinate(-90.0, 180.0))
        assertFalse(isValidCoordinate(90.1, 0.0))
        assertFalse(isValidCoordinate(-90.1, 0.0))
        assertFalse(isValidCoordinate(0.0, 180.1))
        assertFalse(isValidCoordinate(0.0, -180.1))
        assertFalse(isValidCoordinate(Double.NaN, 0.0))
    }

    @Test
    fun `bounds span every point, and none for no points`() {
        val points = TrackPoints.of(
            listOf(TrackPoint(2.0, 5.0), TrackPoint(-1.0, 7.0)),
            listOf(TrackPoint(3.0, -4.0)),
        )

        assertEquals(GeoBounds(-1.0, -4.0, 3.0, 7.0), points.bounds())
        assertNull(TrackPoints.EMPTY.bounds())
    }
}
