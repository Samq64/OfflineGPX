package dev.samuelq.gpx.ui.track

import androidx.annotation.StringRes
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.track.LoadedTrack
import dev.samuelq.gpx.data.track.TrackLoadException

/** Which track to show, and therefore how the repository should fetch it. */
sealed interface TrackRef {
    /** A row in the library. */
    data class Saved(val id: Long) : TrackRef

    /** A one-shot URI from a VIEW or SEND intent, deliberately not indexed. */
    data class Transient(val uri: String) : TrackRef
}

/**
 * The track the map is showing details for.
 *
 * [None] is a real state and the common one: the map's job without a focus is still the
 * overlay, so this is not a screen waiting to load.
 */
sealed interface FocusedTrack {
    data object None : FocusedTrack
    data object Loading : FocusedTrack
    data class Ready(val track: LoadedTrack) : FocusedTrack
    data class Failed(@StringRes val messageRes: Int) : FocusedTrack
}

/** Maps the read failures onto the three the UI has messages for. */
@StringRes
fun Throwable.toTrackMessageRes(): Int = when (this) {
    is TrackLoadException.Invalid -> R.string.track_error_invalid
    is TrackLoadException.Empty -> R.string.track_error_empty
    else -> R.string.track_error_unreadable
}
