package dev.samuelq.gpx.ui.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.record.RecordingController
import kotlinx.coroutines.launch

/**
 * Starts a recording, first asking for whatever is missing - on the tap itself, with no
 * screen explaining itself first. [say] explains a refusal.
 */
@Composable
internal fun rememberStartRecording(recorder: RecordingController, say: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val locationOff = stringResource(R.string.record_location_off)
    val locationDenied = stringResource(R.string.record_location_denied)
    val preciseRequired = stringResource(R.string.record_precise_required)

    // Checked here and again in the service: this is the one that can explain itself, and
    // the service's is for location being switched off between the two.
    val start = { if (recorder.isGpsEnabled) recorder.start() else say(locationOff) }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        when {
            // Precise only. Approximate is wifi- and cell-derived and accurate to hundreds
            // of metres at best; a route from it is noise and a speed from it is a wrong
            // number presented as a real one. Refusing beats recording garbage.
            granted[Manifest.permission.ACCESS_FINE_LOCATION] == true -> start()
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true -> say(preciseRequired)
            else -> say(locationDenied)
        }
    }
    return { permissions.requestThenStart(context, start) }
}

/**
 * Asks for what is missing on the tap that starts a recording, nothing before it - no
 * screen explaining itself first. Coarse is listed alongside fine because Android 12+
 * ignores a fine request without it; notifications are requested but not required.
 */
private fun ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>.requestThenStart(
    context: Context,
    onAlreadyGranted: () -> Unit,
) {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    if (fine == PackageManager.PERMISSION_GRANTED) {
        onAlreadyGranted()
        return
    }

    val wanted = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    launch(wanted.toTypedArray())
}
