package dev.samuelq.gpx.ui.map

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import dev.samuelq.gpx.R

/** The system's location switch. Not every build has the screen. */
internal fun Context.openLocationSettings() {
    runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
}

/** What location is asked for, why, which words a refusal gets, and whether notifications come too. */
internal enum class LocationUse(
    @param:StringRes val why: Int,
    @param:StringRes val off: Int,
    @param:StringRes val denied: Int,
    @param:StringRes val preciseRequired: Int,
    val withNotifications: Boolean,
) {
    // Notifications are requested but not required.
    Record(
        R.string.record_location_why,
        R.string.record_location_off,
        R.string.record_location_denied,
        R.string.record_precise_required,
        true,
    ),
    Show(
        R.string.map_location_why,
        R.string.map_location_off,
        R.string.map_location_denied,
        R.string.map_precise_required,
        false,
    ),
    ;

    /** Only 13+ has the permission to ask for. */
    val asksNotifications: Boolean get() = withNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

/**
 * Invoked like the lambda it replaced. Without permission it first says why, through
 * [LocationRationale], then asks.
 */
internal class LocationRequest(
    val use: LocationUse,
    private val ask: () -> Unit,
    private val start: () -> Unit,
    private val granted: () -> Boolean,
) : () -> Unit {
    var explaining by mutableStateOf(false)
        private set

    override fun invoke() {
        if (granted()) start() else explaining = true
    }

    fun proceed() {
        explaining = false
        ask()
    }

    fun dismiss() {
        explaining = false
    }
}

/** Why location, and for a recording notifications, are wanted, before Android asks. */
@Composable
internal fun LocationRationale(request: LocationRequest) {
    if (!request.explaining) return
    val notifications = request.use.asksNotifications
    AlertDialog(
        onDismissRequest = request::dismiss,
        title = { Text(stringResource(R.string.location_why_title)) },
        text = {
            Text(
                stringResource(request.use.why) +
                    if (notifications) "\n\n" + stringResource(R.string.record_notifications_why) else "",
            )
        },
        confirmButton = { TextButton(onClick = request::proceed) { Text(stringResource(R.string.action_continue)) } },
        dismissButton = { TextButton(onClick = request::dismiss) { Text(stringResource(R.string.action_not_now)) } },
    )
}

/**
 * Runs [onGranted] once location is on and precise, asking for it on the tap after
 * [LocationRationale]. [say] explains a refusal, with a way to the app's settings once Android
 * has stopped asking.
 */
@Composable
internal fun rememberLocationRequest(
    use: LocationUse,
    isGpsEnabled: () -> Boolean,
    onGranted: () -> Unit,
    say: (message: String, openSettings: (() -> Unit)?) -> Unit,
): LocationRequest {
    val context = LocalContext.current
    val activity = LocalActivity.current
    // Not stringResource: read when the answer comes, in the locale of the moment.
    val resources = LocalResources.current

    // Also checked in the service, for location switched off in between; only this one can explain.
    val start = {
        if (isGpsEnabled()) onGranted() else say(resources.getString(use.off)) { context.openLocationSettings() }
    }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        // False after a refusal means "don't ask again": the request returns without a dialog.
        val settled = activity != null &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        val appSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        }
        when {
            // Precise only: GPS_PROVIDER needs it, and approximate is hundreds of metres off.
            granted[Manifest.permission.ACCESS_FINE_LOCATION] == true -> start()
            // Always to settings: its precise switch is easier to find than a second request.
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true ->
                say(resources.getString(use.preciseRequired), appSettings)
            else -> say(resources.getString(use.denied), appSettings.takeIf { settled })
        }
    }
    val granted = {
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    val ask = {
        // Coarse with fine, because fine alone is ignored.
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (use.asksNotifications) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.launch(wanted.toTypedArray())
    }
    // Kept for its dialog state, so it reaches the latest lambdas through these.
    val latestAsk by rememberUpdatedState(ask)
    val latestStart by rememberUpdatedState(start)
    return remember(use) { LocationRequest(use, { latestAsk() }, { latestStart() }, granted) }
}
