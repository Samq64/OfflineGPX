package dev.samuelq.gpx.ui.record

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.samuelq.gpx.R
import dev.samuelq.gpx.ui.map.openAppDetails

/** How to keep recordings alive, shown only while the app is still optimised. */
@Composable
fun BatteryOptimisationHint(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Rechecked on resume, since the user changes it in system settings.
    var restricted by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        restricted = !context.isIgnoringBatteryOptimizations()
    }
    if (!restricted) return

    Column(modifier) {
        Text(
            stringResource(R.string.record_battery_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            // App info has the app's own battery page, where Unrestricted is the exemption; its name
            // varies by skin. Not a direct exemption request, which needs a permission Play restricts.
            onClick = { context.openAppDetails() },
            // Aligns the label, not the ripple, with the text above.
            modifier = Modifier.offset(x = (-12).dp),
        ) {
            Text(stringResource(R.string.record_battery_open_app_info))
        }
    }
}

private fun Context.isIgnoringBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
