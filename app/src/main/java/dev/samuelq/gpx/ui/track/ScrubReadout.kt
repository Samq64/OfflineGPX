package dev.samuelq.gpx.ui.track

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.analysis.TrackProfile
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.theme.LocalChartColors

/**
 * What the scrubber is pointing at, for both charts at once.
 *
 * One readout, every series: the reader drags anywhere and gets every value at that x,
 * rather than having to land on a particular line. The height is reserved whether or not
 * anything is selected, so starting a drag never shifts the charts under the finger.
 */
@Composable
fun ScrubReadout(
    profile: TrackProfile,
    selectedIndex: Int?,
    useTimeAxis: Boolean,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chartColors = LocalChartColors.current
    val index = selectedIndex?.takeIf { it in 0 until profile.size }

    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index == null) {
            Text(
                text = stringResource(R.string.scrub_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Row
        }

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ReadoutValue(
                value = if (useTimeAxis) {
                    Formatters.duration(profile.elapsedSeconds[index].toDouble())
                } else {
                    Formatters.distance(profile.distanceMeters[index].toDouble())
                },
                label = stringResource(if (useTimeAxis) R.string.axis_time else R.string.axis_distance),
            )

            if (profile.hasTime) {
                ReadoutValue(
                    value = Formatters.speed(profile.speedMps[index].toDouble()),
                    label = stringResource(R.string.chart_speed),
                    key = chartColors.speed,
                )
            }

            if (profile.hasElevation) {
                ReadoutValue(
                    value = Formatters.elevation(profile.elevationMeters[index].toDouble()),
                    label = stringResource(R.string.chart_elevation),
                    key = chartColors.elevation,
                )
            }
        }

        IconButton(onClick = onClear) {
            Icon(Icons.Default.Close, stringResource(R.string.scrub_clear))
        }
    }
}

/**
 * The value leads, the label follows: the reader already knows which series they want and
 * came for the number. [key] is a short stroke of the series colour - it carries the
 * identity, so the text can stay in a legible text token rather than a light hue.
 */
@Composable
private fun ReadoutValue(
    value: String,
    label: String,
    key: Color? = null,
) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (key != null) {
                Box(
                    Modifier
                        .size(width = 12.dp, height = 2.dp)
                        .background(key, RoundedCornerShape(1.dp)),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
