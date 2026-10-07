package dev.samuelq.gpx.ui.track

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.gpx.GPX_MIME_TYPE
import dev.samuelq.gpx.data.track.TrackFiles
import dev.samuelq.gpx.data.track.exportFileName

/** Shares the stored file via a [FileProvider] grant scoped to it. */
fun Context.shareTrack(track: TrackEntity) {
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", TrackFiles.file(this, track.location))
    val send = Intent(Intent.ACTION_SEND).apply {
        type = GPX_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, exportFileName(track.trackName, track.displayName))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(send, null))
}
