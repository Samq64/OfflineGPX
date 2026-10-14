package dev.samuelq.gpx

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import dev.samuelq.gpx.ui.GpxApp
import dev.samuelq.gpx.ui.theme.GpxTheme

class MainActivity : ComponentActivity() {

    /** State rather than `intent`, so files from [onNewIntent] reach the UI. */
    private var incomingFiles by mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        incomingFiles = intent?.let(::filesOf).orEmpty()
        // Not on a restore, which would re-ask a question already answered.
        if (savedInstanceState == null) intent?.let(::requestStopIfAsked)

        setContent {
            GpxTheme {
                GpxApp(
                    incomingFiles = incomingFiles,
                    onIncomingFilesHandled = { incomingFiles = emptyList() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        filesOf(intent).takeIf { it.isNotEmpty() }?.let { incomingFiles = it }
        requestStopIfAsked(intent)
    }

    private fun requestStopIfAsked(intent: Intent) {
        if (intent.action == ACTION_REQUEST_STOP) {
            (application as GpxApplication).container.recordingController.requestStop()
        }
    }

    private fun filesOf(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        Intent.ACTION_SEND -> listOfNotNull(
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java),
        )
        // Some senders put one file as a lone extra, or only in the data, as CoMaps also does.
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: listOfNotNull(
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: intent.data,
                )
        else -> emptyList()
    }.filter(::isFromElsewhere)

    /**
     * Only another app's content: an explicit intent skips the filters, and a `file://` path or
     * one of our own providers would be read with this app's access to its private files.
     */
    private fun isFromElsewhere(uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val authority = uri.authority ?: return false
        return packageManager.resolveContentProvider(authority, 0)?.packageName != packageName
    }

    companion object {
        /** From the recording notification's Stop. */
        internal const val ACTION_REQUEST_STOP = "dev.samuelq.gpx.REQUEST_STOP"
    }
}
