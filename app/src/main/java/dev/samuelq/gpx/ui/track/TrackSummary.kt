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
import androidx.compose.ui.res.pluralStringResource
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

/** Elapsed, as the recording shows; moving time, which average speed is over, is in the details. */
@Composable
fun trackHeadline(stats: TrackStats, hasTime: Boolean): List<Stat> {
    val formatters = LocalFormatters.current

    // Remembered: the sheet recomposes on every scrub frame.
    val distanceLabel = stringResource(R.string.axis_distance)
    val timeLabel = stringResource(R.string.stat_elapsed)
    val speedLabel = stringResource(R.string.stat_avg_speed)
    val pointsLabel = pluralStringResource(R.plurals.stat_points, stats.pointCount)

    return remember(stats, hasTime, formatters, distanceLabel, timeLabel, speedLabel, pointsLabel) {
        buildList {
            add(Stat(distanceLabel, formatters.distance(stats.distanceMeters)))
            if (hasTime) {
                add(
                    Stat(
                        label = timeLabel,
                        value = Formatters.duration(stats.totalDurationSeconds),
                    )
                )
                add(Stat(speedLabel, formatters.speed(stats.averageSpeedMps)))
            } else {
                add(Stat(pointsLabel, Formatters.count(stats.pointCount)))
            }
        }
    }
}

/**
 * Moving time, ascent, descent and points. [complete] shows every entry, zero where there's
 * no data, so a recording's layout holds still; null [stats] is no data yet.
 */
@Composable
fun TrackDetails(
    stats: TrackStats?,
    hasTime: Boolean,
    hasElevation: Boolean,
    modifier: Modifier = Modifier,
    complete: Boolean = false,
    /** Overrides the stats', for a recording's count that moves with every fix. */
    pointCount: Int? = null,
) {
    val formatters = LocalFormatters.current

    val movingLabel = stringResource(R.string.stat_moving)
    val ascentLabel = stringResource(R.string.stat_ascent)
    val descentLabel = stringResource(R.string.stat_descent)
    val points = pointCount ?: stats?.pointCount ?: 0
    val pointsLabel = pluralStringResource(R.plurals.stat_points, points)

    val details = remember(
        stats, hasTime, hasElevation, complete, points, formatters,
        movingLabel, ascentLabel, descentLabel, pointsLabel,
    ) {
        val timed = stats?.takeIf { hasTime }
        val climbed = stats?.takeIf { hasElevation }
        buildList {
            // Shown even when equal to elapsed time, for a stable layout.
            if (hasTime || complete) {
                add(movingLabel to Formatters.duration(timed?.movingDurationSeconds ?: 0.0))
            }
            if (hasElevation || complete) {
                add(ascentLabel to formatters.meters(climbed?.ascentMeters ?: 0.0))
                add(descentLabel to formatters.meters(climbed?.descentMeters ?: 0.0))
            }
            if (hasTime || complete) {
                add(pointsLabel to Formatters.count(points))
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
                    // Value over label, like StatRow.
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyLarge.tabularFigures(),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
