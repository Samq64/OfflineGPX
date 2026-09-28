package dev.samuelq.gpx.ui.track

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.analysis.TrackStats
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.format.tabularFigures

@Immutable
class Stat(val label: String, val value: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatRow(
    stats: List<Stat>,
    modifier: Modifier = Modifier,
) {
    // Wraps rather than clips at large font scales.
    FlowRow(
        modifier = modifier.fillMaxWidth().heightIn(min = RowHeight),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        stats.forEach { stat ->
            Column {
                Text(
                    text = stat.value,
                    style = MaterialTheme.typography.titleLarge.tabularFigures(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                Text(
                    text = stat.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

private val RowHeight = 48.dp

/** Moving time, since average speed is measured over it. */
@Composable
fun trackHeadline(stats: TrackStats, hasTime: Boolean): List<Stat> {
    val formatters = LocalFormatters.current

    // Remembered: the sheet recomposes on every scrub frame.
    val distanceLabel = stringResource(R.string.axis_distance)
    val timeLabel = stringResource(R.string.stat_moving)
    val speedLabel = stringResource(R.string.stat_avg_speed)
    val pointsLabel = stringResource(R.string.stat_points)

    return remember(stats, hasTime, formatters, distanceLabel, timeLabel, speedLabel, pointsLabel) {
        buildList {
            add(Stat(distanceLabel, formatters.distance(stats.distanceMeters)))
            if (hasTime) {
                add(
                    Stat(
                        label = timeLabel,
                        value = Formatters.duration(stats.movingDurationSeconds),
                    )
                )
                add(Stat(speedLabel, formatters.speed(stats.averageSpeedMps)))
            } else {
                add(Stat(pointsLabel, Formatters.count(stats.pointCount)))
            }
        }
    }
}

@Composable
fun TrackDetails(
    stats: TrackStats,
    hasTime: Boolean,
    hasElevation: Boolean,
    modifier: Modifier = Modifier,
) {
    val formatters = LocalFormatters.current

    val elapsedLabel = stringResource(R.string.stat_elapsed)
    val ascentLabel = stringResource(R.string.stat_ascent)
    val descentLabel = stringResource(R.string.stat_descent)
    val pointsLabel = stringResource(R.string.stat_points)

    val details = remember(
        stats, hasTime, hasElevation, formatters,
        elapsedLabel, ascentLabel, descentLabel, pointsLabel,
    ) {
        buildList {
            // Shown even when equal to moving time, for a stable layout.
            if (hasTime) {
                add(elapsedLabel to Formatters.duration(stats.totalDurationSeconds))
            }
            if (hasElevation) {
                add(ascentLabel to formatters.meters(stats.ascentMeters))
                add(descentLabel to formatters.meters(stats.descentMeters))
            }
            if (hasTime) {
                add(pointsLabel to Formatters.count(stats.pointCount))
            }
        }
    }

    Column(modifier.fillMaxWidth()) {
        if (details.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                details.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyLarge.tabularFigures(),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
