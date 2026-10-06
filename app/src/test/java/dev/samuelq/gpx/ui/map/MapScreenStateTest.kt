package dev.samuelq.gpx.ui.map

import androidx.compose.runtime.mutableStateOf
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.Waypoint
import dev.samuelq.gpx.ui.track.TrackRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapScreenStateTest {

    private val focused = mutableListOf<TrackRef?>()
    private val waypoint = Waypoint(TrackPoint(1.0, 2.0))
    private val kept = MapScreenState.Kept()
    private var trim = mutableStateOf<IntRange?>(null)
    private var subject: Pair<Long?, Boolean>? = null
    private lateinit var screen: MapScreenState

    /** Keyed as rememberMapScreenState keys it. */
    private fun showing(trackId: Long?, recording: Boolean = false): MapScreenState {
        if (subject == trackId to recording) return screen
        if (subject?.first != trackId) trim = mutableStateOf(null)
        subject = trackId to recording
        screen = MapScreenState(trackId, recording, kept, trim) { focused += it }
        return screen
    }

    @Test
    fun `a tap on the focused line selects its point`() {
        showing(7).tapLine(7, 12)
        assertEquals(12, screen.selectedIndex)
        assertTrue(focused.isEmpty())
    }

    @Test
    fun `a tap on another line focuses it`() {
        showing(7).tapLine(8, 3)
        assertEquals(listOf<TrackRef?>(TrackRef.Saved(8)), focused)
        assertNull(screen.selectedIndex)
    }

    @Test
    fun `while recording, other lines are ignored and the recording's selected`() {
        showing(null, recording = true).tapLine(8, 3)
        assertTrue(focused.isEmpty())
        screen.tapLine(LIVE_TRACK_ID, 5)
        assertEquals(5, screen.selectedIndex)
        screen.tapNothing()
        assertNull(screen.selectedIndex)
        assertTrue(focused.isEmpty())
    }

    @Test
    fun `a tap on nothing clears focus`() {
        showing(7).tapNothing()
        assertEquals(listOf<TrackRef?>(null), focused)
    }

    @Test
    fun `an open waypoint note takes the first tap`() {
        showing(7).selectWaypoint(waypoint, chartIndex = 4)
        assertEquals(4, screen.selectedIndex)
        screen.tapLine(7, 9)
        assertNull(screen.tappedWaypoint)
        assertEquals(4, screen.selectedIndex)
        screen.tapNothing()
        assertEquals(listOf<TrackRef?>(null), focused)
    }

    @Test
    fun `a waypoint off the chart leaves the selection`() {
        showing(7).selectedIndex = 2
        screen.selectWaypoint(waypoint, chartIndex = null)
        assertEquals(waypoint, screen.tappedWaypoint)
        assertEquals(2, screen.selectedIndex)
    }

    @Test
    fun `taps don't drop a trim`() {
        showing(7).selectedIndex = 3
        screen.startTrim(10)
        assertNull(screen.selectedIndex)
        assertEquals(0..9, screen.trimRange)
        screen.tapLine(8, 1)
        screen.tapLine(7, 1)
        screen.tapNothing()
        assertTrue(focused.isEmpty())
        assertNull(screen.selectedIndex)
        assertEquals(0..9, screen.trimRange)
    }

    @Test
    fun `finishing a trim hands back its range once`() {
        showing(7).startTrim(10)
        screen.setTrim(2..5)
        assertEquals(2..5, screen.finishTrim())
        assertNull(screen.trimRange)
        assertNull(screen.finishTrim())
    }

    @Test
    fun `a new subject drops the selection, and a new track the trim`() {
        showing(7).startTrim(10)
        screen.renamingId = 7
        screen.selectWaypoint(waypoint, chartIndex = 4)
        showing(7, recording = true)
        assertNull(screen.selectedIndex)
        assertNull(screen.tappedWaypoint)
        assertEquals(0..9, screen.trimRange)
        showing(8)
        assertNull(screen.trimRange)
        assertEquals(7L, screen.renamingId)
    }

    @Test
    fun `the same subject keeps the selection`() {
        showing(7).selectedIndex = 4
        showing(7)
        assertEquals(4, screen.selectedIndex)
    }

    @Test
    fun `opening frames the track, except while recording`() {
        assertTrue(showing(null).open(TrackRef.Saved(7)))
        assertEquals(listOf<TrackRef?>(TrackRef.Saved(7)), focused)
        assertTrue(screen.frames(7))
        assertFalse(screen.frames(8))

        screen.framing = null
        assertFalse(showing(null, recording = true).open(TrackRef.Saved(9)))
        assertNull(screen.framing)
        assertEquals(1, focused.size)
    }

    @Test
    fun `cancelling a trim drops it`() {
        showing(7).startTrim(10)
        screen.cancelTrim()
        assertNull(screen.trimRange)
        assertNull(screen.finishTrim())
    }

    @Test
    fun `camera requests outlive a change of subject`() {
        showing(7).centreOnFix = true
        screen.centreOnRecording = true
        screen.framing = TrackRef.Saved(7)

        showing(null, recording = true)
        assertTrue(screen.centreOnFix)
        assertTrue(screen.centreOnRecording)
        assertTrue(screen.frames(7))
    }

    @Test
    fun `nothing framed frames nothing`() {
        assertFalse(showing(7).frames(7))
    }
}
