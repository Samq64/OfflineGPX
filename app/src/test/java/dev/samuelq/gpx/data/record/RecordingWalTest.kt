package dev.samuelq.gpx.data.record

import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.Waypoint
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingWalTest {

    private val dir = Files.createTempDirectory("wal").toFile().apply { deleteOnExit() }
    private val at = Instant.parse("2026-09-18T09:00:00Z")

    private fun walFile() = File(dir, "nested/recording.wal")

    @Test
    fun `a log round-trips points, breaks and waypoints`() {
        val file = walFile()
        RecordingWal.open(file).use { wal ->
            wal.append(TrackPoint(51.5, -0.1, 12.5, at, 4.0))
            wal.append(TrackPoint(51.6, -0.2))
            wal.appendBreak()
            wal.append(TrackPoint(51.7, -0.3, time = at.plusSeconds(60)))
            wal.appendWaypoint(Waypoint(TrackPoint(51.55, -0.15, 20.0, at.plusSeconds(5)), "Café, \"top\"\nview"))
            wal.appendWaypoint(Waypoint(TrackPoint(51.56, -0.16)))
        }

        val track = assertNotNull(RecordingWal.recover(file))
        assertNull(track.name)
        assertEquals(3, track.points.size)
        assertContentEquals(intArrayOf(0, 2), track.points.segmentStarts())
        assertEquals(TrackPoint(51.5, -0.1, 12.5, at, 4.0), track.points[0])
        // No time is logged as 0 and read back as none.
        assertEquals(TrackPoint(51.6, -0.2), track.points[1])
        assertEquals(
            listOf(
                Waypoint(TrackPoint(51.55, -0.15, 20.0, at.plusSeconds(5)), "Café, \"top\"\nview"),
                Waypoint(TrackPoint(51.56, -0.16)),
            ),
            track.waypoints,
        )
    }

    @Test
    fun `reopening appends rather than truncating`() {
        val file = walFile()
        RecordingWal.open(file).use { it.append(TrackPoint(1.0, 2.0, time = at)) }
        RecordingWal.open(file).use {
            assertEquals(file, it.file)
            it.append(TrackPoint(1.1, 2.1, time = at.plusSeconds(1)))
        }
        assertEquals(2, RecordingWal.recover(file)?.points?.size)
    }

    @Test
    fun `each line is on disk before the next fix, without closing`() {
        val file = walFile()
        val wal = RecordingWal.open(file)
        wal.append(TrackPoint(1.0, 2.0, time = at))
        // As after a crash: the writer is never closed.
        assertEquals(1, RecordingWal.recover(file)?.points?.size)
        wal.close()
    }

    /** A log from before checksums: fields are checked, so a line cut short is dropped. */
    @Test
    fun `legacy lines are read, and malformed ones dropped`() {
        val file = File(dir, "legacy.wal").apply {
            writeText(
                listOf(
                    "1000,51.5,-0.1,,",
                    "",
                    "  ",
                    "2000,51.6", // too few fields
                    "x,51.6,-0.1,", // bad time
                    "3000,north,-0.1,", // bad latitude
                    "3000,51.6,east,", // bad longitude
                    "3000,95.0,-0.1,", // off the globe
                    "4000,51.7,-0.3,abc,def", // bad optional fields are just absent
                    "W,1000,51.5,-0.1,", // too few fields
                    "W,x,51.5,-0.1,,",
                    "W,1000,north,-0.1,,",
                    "W,1000,51.5,east,,",
                    "W,1000,51.5,200.0,,",
                    "W,1000,51.5,-0.1,,!!not base64!!",
                    "W,0,51.5,-0.1,10.0,",
                    "4500,51.75,-0.35,", // the oldest format, without accuracy
                    "W,2000,51.6,-0.2,,",
                    "5000,51.8,-0.4,1", // cut in the elevation: can't tell, so kept
                    "6000,51.9,-0.4", // cut in the longitude
                ).joinToString("\n"),
            )
        }

        val track = assertNotNull(RecordingWal.recover(file))
        assertEquals(4, track.points.size)
        val second = track.points[1]
        assertNull(second.elevation)
        assertNull(second.accuracyMeters)
        assertEquals(TrackPoint(51.75, -0.35, time = Instant.ofEpochMilli(4500)), track.points[2])
        assertEquals(1.0, track.points[3].elevation)
        assertEquals(
            listOf(
                Waypoint(TrackPoint(51.5, -0.1, time = Instant.ofEpochMilli(1000)), null),
                Waypoint(TrackPoint(51.5, -0.1, 10.0), null),
                Waypoint(TrackPoint(51.6, -0.2, time = Instant.ofEpochMilli(2000)), null),
            ),
            track.waypoints,
        )
    }

    private fun writeThree(file: File) = RecordingWal.open(file).use { wal ->
        wal.append(TrackPoint(51.5, -0.1, 12.5, at, 4.0))
        wal.append(TrackPoint(51.6, -0.2, 13.5, at.plusSeconds(1), 4.0))
        wal.append(TrackPoint(51.7, -0.3, 14.5, at.plusSeconds(2), 4.0))
    }

    @Test
    fun `a last line cut short by a power cut is dropped`() {
        val file = walFile()
        writeThree(file)
        // Every cut into the last line; without just its newline it's whole, so kept.
        val whole = file.readText()
        val lastStart = whole.dropLast(1).lastIndexOf('\n') + 1
        for (end in lastStart + 1 until whole.length - 1) {
            file.writeText(whole.substring(0, end))
            val track = assertNotNull(RecordingWal.recover(file), "cut at $end")
            assertEquals(2, track.points.size, "cut at $end")
        }
        file.writeText(whole)
        assertEquals(3, RecordingWal.recover(file)?.points?.size)
    }

    @Test
    fun `a corrupted line in the middle is dropped`() {
        val file = walFile()
        writeThree(file)
        val lines = file.readLines().toMutableList()
        // One digit of the latitude changed, as a bad sector might: still a valid number.
        lines[2] = lines[2].replaceFirst("51.6", "51.9")
        file.writeText(lines.joinToString("\n", postfix = "\n"))

        val track = assertNotNull(RecordingWal.recover(file))
        assertEquals(listOf(51.5, 51.7), (0 until track.points.size).map { track.points[it].latitude })
    }

    @Test
    fun `no log, or one with no points, recovers nothing`() {
        assertNull(RecordingWal.recover(File(dir, "missing.wal")))
        val waypointsOnly = File(dir, "waypoints.wal")
        RecordingWal.open(waypointsOnly).use {
            it.appendBreak()
            it.appendWaypoint(Waypoint(TrackPoint(1.0, 2.0), "x"))
        }
        assertTrue(waypointsOnly.exists())
        assertNull(RecordingWal.recover(waypointsOnly))
    }
}
