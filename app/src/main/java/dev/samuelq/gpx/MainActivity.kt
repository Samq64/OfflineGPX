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
    }

    private fun trackUriOf(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }
}
