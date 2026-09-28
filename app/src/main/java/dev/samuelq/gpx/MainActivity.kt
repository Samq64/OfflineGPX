package dev.samuelq.gpx

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

    /** State rather than `intent`, so tracks from [onNewIntent] reach the UI. */
    private var incomingTrack by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        incomingTrack = intent?.let(::trackUriOf)
        // Not on a restore, which would re-ask a question already answered.
        if (savedInstanceState == null) intent?.let(::requestStopIfAsked)

        setContent {
            GpxTheme {
                GpxApp(
                    incomingTrack = incomingTrack,
                    onIncomingTrackHandled = { incomingTrack = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        trackUriOf(intent)?.let { incomingTrack = it }
        requestStopIfAsked(intent)
    }

    private fun requestStopIfAsked(intent: Intent) {
        if (intent.action == ACTION_REQUEST_STOP) {
            (application as GpxApplication).container.recordingController.requestStop()
        }
    }

    private fun trackUriOf(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }

    companion object {
        /** From the recording notification's Stop. */
        internal const val ACTION_REQUEST_STOP = "dev.samuelq.gpx.REQUEST_STOP"
    }
}
