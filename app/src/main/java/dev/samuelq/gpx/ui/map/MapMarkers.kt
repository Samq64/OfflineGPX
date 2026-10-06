package dev.samuelq.gpx.ui.map

import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import dev.samuelq.gpx.core.analysis.bearingDegrees
import dev.samuelq.gpx.core.analysis.haversineMeters
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.Waypoint
import org.oscim.layers.marker.MarkerInterface
import org.oscim.android.canvas.AndroidBitmap
import org.oscim.core.GeoPoint
import org.oscim.layers.marker.MarkerItem
import org.oscim.layers.marker.MarkerSymbol

/** Marker bitmaps, built once per colour. [hole] is the map's land colour, filling pin centres. */
internal class MarkerSymbols(marker: Color, puck: Color, hole: Color, density: Density) {
    val marker: MarkerSymbol
    val puck: MarkerSymbol

    /** The puck once there is a direction: an arrow, so it can't be mistaken for the scrub dot. */
    private val heading: MarkerSymbol

    /** In their owner's colour, like a list's bookmarks: the focused track's, or the recording's. */
    val trackWaypoint: MarkerSymbol
    val liveWaypoint: MarkerSymbol

    init {
        with(density) {
            val ringWidth = MARKER_RING_WIDTH_DP.dp.toPx()
            this@MarkerSymbols.marker = symbol(
                MARKER_RADIUS_DP.dp.toPx(), ringWidth, fill = marker, ring = MARKER_RING, halo = null, haloRadius = 0f,
            )
            // Bigger and haloed: the only marker about right now.
            this@MarkerSymbols.puck = symbol(
                PUCK_RADIUS_DP.dp.toPx(), ringWidth, fill = puck, ring = MARKER_RING,
                halo = puck.copy(alpha = PUCK_HALO_ALPHA), haloRadius = PUCK_HALO_RADIUS_DP.dp.toPx(),
            )
            heading = arrow(PUCK_ARROW_RADIUS_DP.dp.toPx(), ringWidth, fill = puck, ring = MARKER_RING)
            // Hotspot at the tip, so the pin points at the position exactly.
            val pinRadius = PIN_RADIUS_DP.dp.toPx()
            val tipLength = PIN_TIP_LENGTH_DP.dp.toPx()
            val pinRing = PIN_RING_WIDTH_DP.dp.toPx()
            trackWaypoint = pin(pinRadius, tipLength, pinRing, fill = marker, ring = MARKER_RING, hole = hole)
            liveWaypoint = pin(pinRadius, tipLength, pinRing, fill = puck, ring = MARKER_RING, hole = hole)
        }
    }

    private fun symbol(
        radius: Float,
        ringWidth: Float,
        fill: Color,
        ring: Color,
        halo: Color?,
        haloRadius: Float,
    ): MarkerSymbol {
        val outer = maxOf(radius + ringWidth / 2, haloRadius)
        val size = kotlin.math.ceil(outer * 2).toInt() + 2
        val bitmap = createBitmap(size, size)
        val canvas = android.graphics.Canvas(bitmap)
        val centre = size / 2f
        if (halo != null) canvas.drawCircle(centre, centre, haloRadius, fillPaint(halo))
        canvas.fillThenRing(fill, ring, ringWidth) { drawCircle(centre, centre, radius, it) }
        return MarkerSymbol(AndroidBitmap(bitmap), MarkerSymbol.HotspotPlace.CENTER, false)
    }

    /** Pointing up, centred on the position; the map turns it to the heading. */
    private fun arrow(radius: Float, ringWidth: Float, fill: Color, ring: Color): MarkerSymbol {
        val size = kotlin.math.ceil((radius + ringWidth) * 2).toInt() + 2
        val bitmap = createBitmap(size, size)
        val canvas = android.graphics.Canvas(bitmap)
        val c = size / 2f
        val path = android.graphics.Path().apply {
            moveTo(c, c - radius)
            lineTo(c + radius * 0.8f, c + radius * 0.8f)
            lineTo(c, c + radius * 0.35f)
            lineTo(c - radius * 0.8f, c + radius * 0.8f)
            close()
        }
        canvas.fillThenRing(fill, ring, ringWidth) { drawPath(path, it) }
        return MarkerSymbol(AndroidBitmap(bitmap), MarkerSymbol.HotspotPlace.CENTER, false)
    }

    /** At [at]; an arrow turned to [bearing] when there is one, else the dot. */
    fun puck(at: TrackPoint?, bearing: Double?): List<MarkerInterface> {
        at ?: return emptyList()
        if (bearing == null) return listOf(marker(at, puck))
        // One puck, so the shared symbol can carry its rotation.
        heading.rotation = bearing.toFloat()
        return listOf(marker(at, heading))
    }

    /** A teardrop pin: a circle with exact tangent lines to a tip [tipLength] below its centre. */
    private fun pin(
        radius: Float,
        tipLength: Float,
        ringWidth: Float,
        fill: Color,
        ring: Color,
        hole: Color,
    ): MarkerSymbol {
        // Half margin below, with a round join: a mitred sharp tip would spike past the ring.
        val topPad = ringWidth
        val bottomPad = ringWidth / 2
        val width = kotlin.math.ceil(2 * radius + 2 * topPad).toInt() + 1
        val height = kotlin.math.ceil(radius + tipLength + topPad + bottomPad).toInt() + 1
        val cx = width / 2f
        val cy = topPad + radius
        val tipY = cy + tipLength

        val bitmap = createBitmap(width, height)
        val canvas = android.graphics.Canvas(bitmap)
        val path = teardropPath(cx, cy, radius, tipY)
        canvas.fillThenRing(fill, ring, ringWidth) { drawPath(path, it) }
        // Land-coloured, not see-through: that showed the pin's own line, the fill's own tone.
        // Not white either, which glared in dark mode.
        canvas.drawCircle(cx, cy, radius * PIN_HOLE_RATIO, fillPaint(hole))
        return MarkerSymbol(AndroidBitmap(bitmap), MarkerSymbol.HotspotPlace.BOTTOM_CENTER, false)
    }
}

private fun fillPaint(color: Color) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb() }

/** [shape] filled, then outlined in [ring] with a round join. */
private inline fun android.graphics.Canvas.fillThenRing(
    fill: Color,
    ring: Color,
    ringWidth: Float,
    shape: android.graphics.Canvas.(Paint) -> Unit,
) {
    val paint = fillPaint(fill)
    shape(paint)
    paint.style = Paint.Style.STROKE
    paint.strokeJoin = Paint.Join.ROUND
    paint.strokeWidth = ringWidth
    paint.color = ring.toArgb()
    shape(paint)
}

private fun teardropPath(cx: Float, cy: Float, radius: Float, tipY: Float): android.graphics.Path {
    val d = tipY - cy
    val angle = kotlin.math.acos((radius / d).coerceIn(-1f, 1f))
    val a1 = (Math.PI / 2).toFloat() - angle
    val a1Degrees = Math.toDegrees(a1.toDouble()).toFloat()
    // The long way round, over the top; the short way is the wedge the tangents replace.
    val sweepDegrees = -(360f - Math.toDegrees((2 * angle).toDouble()).toFloat())

    return android.graphics.Path().apply {
        moveTo(cx + radius * kotlin.math.cos(a1), cy + radius * kotlin.math.sin(a1))
        arcTo(
            android.graphics.RectF(cx - radius, cy - radius, cx + radius, cy + radius),
            a1Degrees,
            sweepDegrees,
        )
        lineTo(cx, tipY)
        close()
    }
}

private fun marker(at: TrackPoint, symbol: MarkerSymbol) =
    MarkerItem("", "", GeoPoint(at.latitude, at.longitude)).apply { marker = symbol }

/** Every pin but [onTop], which is drawn above the rest. */
internal fun MarkerSymbols.pins(
    trackWaypoints: List<Waypoint>,
    liveWaypoints: List<Waypoint>,
    onTop: Waypoint?,
): List<MarkerInterface> =
    trackWaypoints.filter { it != onTop }.map { marker(it.point, trackWaypoint) } +
        liveWaypoints.filter { it != onTop }.map { marker(it.point, liveWaypoint) }

/** From the last point back to one far enough off to point from, within its segment. */
internal fun RouteOverlay.headingDegrees(): Double? {
    val last = points.lastOrNull() ?: return null
    val from = points.segmentStart(points.segmentCount - 1)
    for (index in points.size - 2 downTo from) {
        val distance = haversineMeters(points.latitude(index), points.longitude(index), last.latitude, last.longitude)
        if (distance >= HEADING_MIN_METERS) return bearingDegrees(points[index], last)
    }
    return null
}

/** Past GPS jitter at a standstill. */
private const val HEADING_MIN_METERS = 3.0

internal fun MarkerSymbols.selectedDot(at: TrackPoint?): List<MarkerInterface> =
    listOfNotNull(at?.let { marker(it, marker) })

internal fun MarkerSymbols.onTopPin(onTop: Waypoint?, liveWaypoints: List<Waypoint>): List<MarkerInterface> =
    listOfNotNull(onTop?.let { marker(it.point, if (it in liveWaypoints) liveWaypoint else trackWaypoint) })

private const val MARKER_RING_WIDTH_DP = 1.5f

private const val MARKER_RADIUS_DP = 7f

private const val PUCK_RADIUS_DP = 8f

/** Tip to centre: the arrow needs more than the dot's radius to read as one. */
private const val PUCK_ARROW_RADIUS_DP = 11f

// 40dp tall with the ring's top edge: big enough to tap, since taps only hit the icon.
private const val PIN_RADIUS_DP = 14f

internal const val PIN_TIP_LENGTH_DP = 24.75f

// Thicker than the dots': it separates the pin from its own line, which shares its colour.
private const val PIN_RING_WIDTH_DP = 2.5f

private const val PIN_HOLE_RATIO = 0.4f

/** The pin's head, ring included. */
internal val WaypointPinHeadRadius = (PIN_RADIUS_DP + PIN_RING_WIDTH_DP).dp

/** White in both themes: a surface-coloured ring vanished against dark-mode land. */
private val MARKER_RING = Color.White

private const val PUCK_HALO_RADIUS_DP = 14f

private const val PUCK_HALO_ALPHA = 0.24f
