package dev.samuelq.gpx.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.samuelq.gpx.core.model.Waypoint
import dev.samuelq.gpx.ui.track.TrackRef

/**
 * The map screen's own state and how taps, back and trims move it, for one subject: the loaded
 * track [focusedId] and whether [recording]. A new subject is a new instance, so its selection
 * starts empty without a write during composition; [kept] and, for the same track, [trim]
 * carry over. [focus] asks for a track, or none.
 */
@Stable
class MapScreenState(
    private val focusedId: Long?,
    private val recording: Boolean,
    private val kept: Kept,
    private val trim: MutableState<IntRange?>,
    private val focus: (TrackRef?) -> Unit,
) {
    /** What outlives a change of subject. */
    @Stable
    class Kept {
        var renamingId by mutableStateOf<Long?>(null)
        var framing by mutableStateOf<TrackRef?>(null)
        var centreOnFix by mutableStateOf(false)
        var centreOnRecording by mutableStateOf(false)
    }

    /** Into the charted route's points. */
    var selectedIndex by mutableStateOf<Int?>(null)

    var tappedWaypoint by mutableStateOf<Waypoint?>(null)
        private set

    /** The points a trim being set up keeps; null when not trimming. */
    val trimRange: IntRange? get() = trim.value

    /** By id: the prompt waits for the track to load, by which time the sheet may show another. */
    var renamingId by kept::renamingId

    /** Opened from the list or an intent, so framed once loaded; map taps never move the camera. */
    var framing by kept::framing

    /** On the first fix after a tap; later ones move the dot, not the map. */
    var centreOnFix by kept::centreOnFix

    /** A ride starts where the user is, which may be nowhere near the view. */
    var centreOnRecording by kept::centreOnRecording

    /** From the list or an intent. False while recording, which holds the sheet. */
    fun open(ref: TrackRef): Boolean {
        if (recording) return false
        framing = ref
        focus(ref)
        return true
    }

    fun frames(trackId: Long) = when (val ref = framing) {
        is TrackRef.Saved -> ref.id == trackId
        // Imported as it opened, so its id wasn't known when asked for.
        is TrackRef.Shared -> true
        null -> false
    }

    /** A tap on [trackId]'s line, nearest its point [index]. */
    fun tapLine(trackId: Long, index: Int) {
        when {
            // An open note takes the first tap, so closing it never moves the marker.
            tappedWaypoint != null -> tappedWaypoint = null
            // The slider sets a trim; a tap shouldn't drop it for another track.
            trimRange != null -> Unit
            trackId == LIVE_TRACK_ID || trackId == focusedId -> selectedIndex = index
            // Not while recording, which holds the sheet.
            !recording -> focus(TrackRef.Saved(trackId))
        }
    }

    fun tapNothing() {
        when {
            tappedWaypoint != null -> tappedWaypoint = null
            trimRange != null -> Unit
            recording -> selectedIndex = null
            else -> focus(null)
        }
    }

    /** From a pin tap, or the sheet's screen reader actions. [chartIndex] if it's on the charted route. */
    fun selectWaypoint(waypoint: Waypoint, chartIndex: Int?) {
        tappedWaypoint = waypoint
        chartIndex?.let { selectedIndex = it }
    }

    fun startTrim(pointCount: Int) {
        selectedIndex = null
        trim.value = 0..<pointCount
    }

    fun setTrim(range: IntRange) {
        trim.value = range
    }

    fun cancelTrim() {
        trim.value = null
    }

    /** The range to cut to, ending the trim. */
    fun finishTrim(): IntRange? = trim.value.also { trim.value = null }
}

@Composable
fun rememberMapScreenState(focusedId: Long?, isRecording: Boolean, focus: (TrackRef?) -> Unit): MapScreenState {
    val currentFocus by rememberUpdatedState(focus)
    val kept = remember { MapScreenState.Kept() }
    val trim = remember(focusedId) { mutableStateOf<IntRange?>(null) }
    return remember(focusedId, isRecording, trim) {
        MapScreenState(focusedId, isRecording, kept, trim) { currentFocus(it) }
    }
}

/** Live recording layer id; it has no library row. */
internal const val LIVE_TRACK_ID = Long.MIN_VALUE
