package dev.samuelq.gpx.ui.track

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
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
) {
    // A FlowRow rather than a Row: wide units, a long duration and a large font scale can
    // each use the width up on their own, and wrapping beats a Row's only other option,
    // which is to clip.
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

/**
 * The three readings that answer "what was this ride", and no fourth.
 *
 * Max speed used to sit here too and does not any more: the speed chart marks and labels
 * its own peak a few hundred pixels below, and printing the same figure twice on one
 * screen is the sheet paying rent in height for nothing.
 *
 * The duration is whichever one the average speed was measured over - see [movingIsBasis].
 */
@Composable
fun trackHeadline(stats: TrackStats, hasTime: Boolean): List<Stat> {
    val formatters = LocalFormatters.current
    val moving = hasTime && stats.movingIsBasis

    // Labels resolved first, then the row built once. These numbers are about the whole
    // ride and change when the ride does - but the sheet around them recomposes on every
    // frame of a chart scrub, and without this each of those frames re-ran the formatters
    // to arrive at the same three strings.
    val distanceLabel = stringResource(R.string.axis_distance)
    val timeLabel = stringResource(if (moving) R.string.stat_moving else R.string.stat_duration)
    val speedLabel = stringResource(R.string.stat_avg_speed)
    val pointsLabel = stringResource(R.string.stat_points)

    return remember(stats, hasTime, formatters, distanceLabel, timeLabel, speedLabel, pointsLabel) {
        buildList {
            add(Stat(distanceLabel, formatters.distance(stats.distanceMeters)))
            if (hasTime) {
                add(
                    Stat(
                        label = timeLabel,
                        value = Formatters.duration(
                            if (moving) stats.movingDurationSeconds else stats.totalDurationSeconds
                        ),
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
 * Whether the headline should read the moving time rather than the wall clock.
 *
 * The average speed is distance over *moving* time, the way every tracker reports it. Put
 * the wall clock next to it and the row does not reconcile: a ride with a long lunch reads
 * 8 km, 2:30, 18 km/h, and the reader is left to work out which of the three is lying -
 * none of them is, but the one number that would explain it is folded away under Details.
 * So the time on this row is the time the speed beside it was measured over, and the other
 * one moves into the fold. When the ride has no stops in it they are the same number, and
 * printing it twice is what the fold exists to avoid.
 */
val TrackStats.movingIsBasis: Boolean
    get() = movingDurationSeconds > 0.0 &&
        totalDurationSeconds - movingDurationSeconds >= STOPPED_TIME_WORTH_SPLITTING

/** Below a second the two durations format identically, so there is nothing to tell apart. */
private const val STOPPED_TIME_WORTH_SPLITTING = 1.0

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

    val elapsedLabel = stringResource(R.string.stat_elapsed)
    val ascentLabel = stringResource(R.string.stat_ascent)
    val descentLabel = stringResource(R.string.stat_descent)
    val pointsLabel = stringResource(R.string.stat_points)

    // Built once per track rather than once per recomposition, for the reason given in
    // [trackHeadline]: a scrub redraws this sheet many times a second and none of these
    // figures is about the scrub.
    val details = remember(
        stats, hasTime, hasElevation, formatters,
        elapsedLabel, ascentLabel, descentLabel, pointsLabel,
    ) {
        buildList {
            // The duration the headline did not take. One of the two is up there already,
            // and which one depends on whether the ride had any standing still in it.
            if (hasTime && stats.movingIsBasis) {
                add(elapsedLabel to Formatters.duration(stats.totalDurationSeconds))
            }
            if (hasElevation) {
                add(ascentLabel to formatters.elevation(stats.ascentMeters))
                add(descentLabel to formatters.elevation(stats.descentMeters))
            }
            if (hasTime) {
                add(pointsLabel to Formatters.count(stats.pointCount))
            }
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
