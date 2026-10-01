package dev.samuelq.gpx.ui.track

import android.net.Uri
import androidx.annotation.StringRes
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.track.LoadedTrack
import dev.samuelq.gpx.data.track.TrackLoadException

sealed interface TrackRef {
    data class Saved(val id: Long) : TrackRef

    /** From a VIEW or SEND intent; added to the library as it opens. */
    data class Shared(val uri: Uri) : TrackRef
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
