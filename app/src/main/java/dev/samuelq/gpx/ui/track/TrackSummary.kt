package dev.samuelq.gpx.ui.track

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.analysis.TrackStats
import dev.samuelq.gpx.ui.format.Formatters

/**
 * The headline number plus the stats that need no chart to be understood.
 *
 * Distance is the hero, one per view. It keeps the font's proportional figures: at this
 * size tabular ones make a number like `121` look gappy.
 */
@Composable
fun TrackSummary(
    stats: TrackStats,
    hasTime: Boolean,
    hasElevation: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = Formatters.distance(stats.distanceMeters),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stats.startedAt
                ?.let { Formatters.dateTime(it) }
                ?: stringResource(R.string.stat_points) + "  " + Formatters.count(stats.pointCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        val tiles = buildList {
            if (hasTime) {
                add(stringResource(R.string.stat_duration) to Formatters.duration(stats.totalDurationSeconds))
                add(stringResource(R.string.stat_moving) to Formatters.duration(stats.movingDurationSeconds))
                add(stringResource(R.string.stat_avg_speed) to Formatters.speed(stats.averageSpeedMps))
                add(stringResource(R.string.stat_max_speed) to Formatters.speed(stats.maxSpeedMps))
            }
            if (hasElevation) {
                add(stringResource(R.string.stat_ascent) to Formatters.elevation(stats.ascentMeters))
                add(stringResource(R.string.stat_descent) to Formatters.elevation(stats.descentMeters))
            }
            if (!hasTime && !hasElevation) {
                add(stringResource(R.string.stat_points) to Formatters.count(stats.pointCount))
            }
        }

        StatGrid(tiles)
    }
}

/** Three per row: wide enough for "1:04:12" without wrapping on a small phone. */
@Composable
private fun StatGrid(tiles: List<Pair<String, String>>, columns: Int = 3) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { (label, value) ->
                    StatTile(label, value, Modifier.weight(1f))
                }
                // Keeps the last row's tiles aligned with the ones above rather than
                // stretching two tiles across three columns' worth of space.
                repeat(columns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
