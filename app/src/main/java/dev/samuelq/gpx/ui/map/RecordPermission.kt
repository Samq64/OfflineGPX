package dev.samuelq.gpx.ui.map

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.samuelq.gpx.R

/** What location is asked for, which words a refusal gets, and whether notifications come too. */
internal enum class LocationUse(
    @param:StringRes val off: Int,
    @param:StringRes val denied: Int,
    @param:StringRes val preciseRequired: Int,
    val withNotifications: Boolean,
) {
    // Notifications are requested but not required.
    Record(R.string.record_location_off, R.string.record_location_denied, R.string.record_precise_required, true),
    Show(R.string.map_location_off, R.string.map_location_denied, R.string.map_precise_required, false),
}

/**
 * Runs [onGranted] once location is on and precise, asking for it on the tap. [say] explains a
 * refusal, with a way to the app's settings once Android has stopped asking.
 */
@Composable
internal fun rememberLocationRequest(
    use: LocationUse,
    isGpsEnabled: () -> Boolean,
    onGranted: () -> Unit,
    say: (message: String, openSettings: (() -> Unit)?) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    // Not stringResource: read when the answer comes, in the locale of the moment.
    val resources = LocalResources.current

    // Also checked in the service, for location switched off in between; only this one can explain.
    val start = { if (isGpsEnabled()) onGranted() else say(resources.getString(use.off), null) }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        // False after a refusal means "don't ask again": the request returns without a dialog.
        val settled = activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
        val openSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            )
        }.takeIf { settled }
        when {
            // Precise only: GPS_PROVIDER needs it, and approximate is hundreds of metres off.
            granted[Manifest.permission.ACCESS_FINE_LOCATION] == true -> start()
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true ->
                say(resources.getString(use.preciseRequired), openSettings)
            else -> say(resources.getString(use.denied), openSettings)
        }
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            start()
        } else {
            // Coarse with fine, because Android 12+ ignores fine alone.
            val wanted = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (use.withNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            permissions.launch(wanted.toTypedArray())
        }
    }
}
