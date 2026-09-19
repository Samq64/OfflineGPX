package dev.samuelq.gpx.ui.track

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
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

/** One reading in a [StatRow]. */
@Immutable
class Stat(val label: String, val value: String)

/**
 * The line of numbers the sheet leads with, at whatever height it is sitting.
 *
 * The value leads and the label follows: the reader came for the number. This is the whole
 * track and stays the whole track; what a scrubber is pointing at is drawn on the chart
 * that is being scrubbed, beside the mark, where there is no question which series it
 * belongs to.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatRow(
    stats: List<Stat>,
    modifier: Modifier = Modifier,
    /**
     * Put beside the last reading when the line has room for it, and on its own line
     * underneath when it does not - which is what a `Row` could not do, having no choice
     * but to clip. Wide units, a long duration and a large font scale can each use the
     * room up on their own.
     */
    trailing: @Composable (FlowRowScope.() -> Unit)? = null,
) {
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

        trailing?.invoke(this)
    }
}

private val RowHeight = 48.dp

/**
 * The three readings that answer "what was this ride", and no fourth.
 *
 * Max speed used to sit here too and does not any more: the speed chart marks and labels
 * its own peak a few hundred pixels below, and printing the same figure twice on one
 * screen is the sheet paying rent in height for nothing.
 */
@Composable
fun trackHeadline(stats: TrackStats, hasTime: Boolean): List<Stat> {
    val formatters = LocalFormatters.current
    return buildList {
        add(Stat(stringResource(R.string.axis_distance), formatters.distance(stats.distanceMeters)))
        if (hasTime) {
            add(Stat(stringResource(R.string.stat_duration), Formatters.duration(stats.totalDurationSeconds)))
            add(Stat(stringResource(R.string.stat_avg_speed), formatters.speed(stats.averageSpeedMps)))
        } else {
            add(Stat(stringResource(R.string.stat_points), Formatters.count(stats.pointCount)))
        }
    }
}

/**
 * Everything worth keeping that did not earn a place in the headline, on one line.
 *
 * Reached only by opening the sheet, which is the right price for it: these are the
 * numbers you go looking for, not the ones you glance at.
 */
@Composable
fun TrackDetails(
    stats: TrackStats,
    hasTime: Boolean,
    hasElevation: Boolean,
    modifier: Modifier = Modifier,
) {
    val formatters = LocalFormatters.current
    val details = buildList {
        if (hasTime) {
            add(stringResource(R.string.stat_moving) to Formatters.duration(stats.movingDurationSeconds))
        }
        if (hasElevation) {
            add(stringResource(R.string.stat_ascent) to formatters.elevation(stats.ascentMeters))
            add(stringResource(R.string.stat_descent) to formatters.elevation(stats.descentMeters))
        }
        if (hasTime) {
            add(stringResource(R.string.stat_points) to Formatters.count(stats.pointCount))
        }
    }

    Column(modifier.fillMaxWidth()) {
        stats.startedAt?.let {
            Text(
                text = Formatters.dateTime(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
        }

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
