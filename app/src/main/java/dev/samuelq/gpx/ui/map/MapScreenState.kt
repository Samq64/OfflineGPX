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
        var following by mutableStateOf(false)
        var snapping by mutableStateOf(false)
        /** Null until a subject is first seen, e.g. after process death mid-recording. */
        var wasRecording: Boolean? = null
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

    /** The camera keeps the position, or the recording's latest point, centred until a drag. */
    val following: Boolean get() = kept.following

    /** The next centring may also zoom in: following was just asked for, maybe from far out. */
    val snapping: Boolean get() = kept.snapping

    /**
     * The location button. Outside a recording, a tap while following is the only way to
     * stop showing the position; during one, the puck is the recording, so it does nothing.
     */
    fun tapLocation(locating: Boolean): LocationTap = when {
        following && recording -> LocationTap.Nothing
        following -> {
            stopFollowing()
            LocationTap.Stop
        }
        recording || locating -> {
            follow()
            LocationTap.Follow
        }
        // The caller asks for location, then calls follow.
        else -> LocationTap.Start
    }

    fun follow() {
        kept.following = true
        kept.snapping = true
    }

    /** By a drag, the position leaving every map, or location going off. */
    fun stopFollowing() {
        kept.following = false
        kept.snapping = false
    }

    fun centred() {
        kept.snapping = false
    }

    /**
     * Seen once per subject. A recording that starts is followed; one that ends, however it
     * ends, leaves following and location off: true then, for the caller to stop location.
     */
    fun recordingSeen(): Boolean {
        val was = kept.wasRecording
        kept.wasRecording = recording
        when {
            was == false && recording -> follow()
            was == true && !recording -> {
                stopFollowing()
                return true
            }
        }
        return false
    }

    /** From the list or an intent. False while recording, which holds the sheet. */
    fun open(ref: TrackRef): Boolean {
        if (recording) return false
        // Else the next fix pulls the camera back off the track.
        stopFollowing()
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

/** What a tap on the location button asks of location. */
enum class LocationTap { Start, Follow, Stop, Nothing }

/** [kept] from the view model: following outlives leaving the screen and rotation, not the process. */
@Composable
fun rememberMapScreenState(
    focusedId: Long?,
    isRecording: Boolean,
    kept: MapScreenState.Kept,
    focus: (TrackRef?) -> Unit,
): MapScreenState {
    val currentFocus by rememberUpdatedState(focus)
    val trim = remember(focusedId) { mutableStateOf<IntRange?>(null) }
    return remember(focusedId, isRecording, trim) {
        MapScreenState(focusedId, isRecording, kept, trim) { currentFocus(it) }
    }
}

/** Live recording layer id; it has no library row. */
internal const val LIVE_TRACK_ID = Long.MIN_VALUE
