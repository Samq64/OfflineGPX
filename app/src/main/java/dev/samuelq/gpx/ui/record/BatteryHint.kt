package dev.samuelq.gpx.ui.record

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.R
import dev.samuelq.gpx.ui.map.openAppDetails

/**
 * After a force stop, which is how most battery savers close an app. One sentence whether or not
 * Android's optimisation is off, as many skins have their own settings the exemption misses.
 */
@Composable
fun BatteryHint(modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            stringResource(R.string.record_battery_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // App info has the app's own battery page, where Unrestricted is the exemption; its name
        // varies by skin. Not a direct exemption request, which needs a permission Play restricts.
        AppInfoButton()
    }
}

/** To this app's info page, for its permissions and battery use; under body text. */
@Composable
fun AppInfoButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    TextButton(
        onClick = { context.openAppDetails() },
        // Aligns the label, not the ripple, with the text above.
        modifier = modifier.offset(x = (-12).dp),
    ) {
        Text(stringResource(R.string.action_app_info))
    }
}
