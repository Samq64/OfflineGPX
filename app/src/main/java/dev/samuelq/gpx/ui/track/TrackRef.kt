package dev.samuelq.gpx.ui.track

import androidx.annotation.StringRes
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.track.LoadedTrack
import dev.samuelq.gpx.data.track.TrackLoadException

sealed interface TrackRef {
    data class Saved(val id: Long) : TrackRef

    /** A URI from a VIEW or SEND intent, deliberately not indexed. */
    data class Transient(val uri: String) : TrackRef
}

sealed interface FocusedTrack {
    data object None : FocusedTrack
    data object Loading : FocusedTrack
    data class Ready(val track: LoadedTrack) : FocusedTrack
    data class Failed(@StringRes val messageRes: Int) : FocusedTrack
}

@StringRes
fun Throwable.toTrackMessageRes(): Int = when (this) {
    is TrackLoadException.Invalid -> R.string.track_error_invalid
    is TrackLoadException.Empty -> R.string.track_error_empty
    else -> R.string.track_error_unreadable
}
