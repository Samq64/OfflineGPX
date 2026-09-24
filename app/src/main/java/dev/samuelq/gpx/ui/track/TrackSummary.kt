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
 * The line of numbers the sheet leads with. Value leads, label follows - the reader came
 * for the number. Always the whole track; a scrubbed value is drawn on its own chart instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatRow(
    stats: List<Stat>,
    modifier: Modifier = Modifier,
) {
    // A FlowRow, not a Row: wide units or a large font scale can eat the width, and
    // wrapping beats clipping.
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
 * The three readings that answer "what was this ride", and no fourth - max speed isn't
 * here since the speed chart already marks and labels its own peak. The duration shown is
 * whichever one the average speed was measured over; see [movingIsBasis].
 */
@Composable
fun trackHeadline(stats: TrackStats, hasTime: Boolean): List<Stat> {
    val formatters = LocalFormatters.current
    val moving = hasTime && stats.movingIsBasis

    // Labels resolved first, then the row built once - the sheet recomposes on every scrub
    // frame, and these numbers don't change with the scrub.
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
 * Whether the headline should read moving time rather than the wall clock. Average speed
 * is distance over *moving* time; pairing it with the wall clock instead would make a ride
 * with a long lunch read as three numbers that don't reconcile. The other duration moves
 * into the fold - the same number as this one when there were no stops, hence the fold.
 */
val TrackStats.movingIsBasis: Boolean
    get() = movingDurationSeconds > 0.0 &&
        totalDurationSeconds - movingDurationSeconds >= STOPPED_TIME_WORTH_SPLITTING

/** Below a second the two durations format identically, so there is nothing to tell apart. */
private const val STOPPED_TIME_WORTH_SPLITTING = 1.0

/**
 * Everything worth keeping that didn't earn a place in the headline - numbers you go
 * looking for, not ones you glance at, so opening the sheet is the right price.
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

    // Built once per track, not per recomposition - same reason as [trackHeadline].
    val details = remember(
        stats, hasTime, hasElevation, formatters,
        elapsedLabel, ascentLabel, descentLabel, pointsLabel,
    ) {
        buildList {
            // The duration the headline didn't take; see [movingIsBasis] for which one that is.
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
