package dev.samuelq.gpx.ui.map

import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.Waypoint
import org.oscim.layers.marker.MarkerInterface
import org.oscim.android.canvas.AndroidBitmap
import org.oscim.core.GeoPoint
import org.oscim.layers.marker.MarkerItem
import org.oscim.layers.marker.MarkerSymbol
import org.oscim.layers.vector.geometries.Style

/** The circles, drawn once per colour - see where [MarkerSymbols] is remembered. */
internal class MarkerSymbols(marker: Color, puck: Color, darkTheme: Boolean, density: Density) {
    val marker: MarkerSymbol
    val puck: MarkerSymbol
    val waypoint: MarkerSymbol

    init {
        with(density) {
            val ringWidth = MARKER_RING_WIDTH_DP.dp.toPx()
            this@MarkerSymbols.marker = symbol(
                MARKER_RADIUS_DP.dp.toPx(), ringWidth, fill = marker, ring = MARKER_RING, halo = null, haloRadius = 0f,
            )
            // Bigger than the scrub marker and wearing a halo: one points at a moment in a
            // ride that's over, this is the only thing on screen about right now.
            this@MarkerSymbols.puck = symbol(
                PUCK_RADIUS_DP.dp.toPx(), ringWidth, fill = puck, ring = MARKER_RING,
                halo = puck.copy(alpha = PUCK_HALO_ALPHA), haloRadius = PUCK_HALO_RADIUS_DP.dp.toPx(),
            )
            // A pin, not a dot: a waypoint is a place someone marked, and the shape says so
            // on its own - the tip is the hotspot, so it points at the position exactly the
            // way the puck and the scrub marker sit centred on theirs.
            this@MarkerSymbols.waypoint = pin(
                PIN_RADIUS_DP.dp.toPx(), PIN_TIP_LENGTH_DP.dp.toPx(), ringWidth,
                fill = if (darkTheme) WAYPOINT_LIGHT_GREY else WAYPOINT_DARK_GREY,
                ring = if (darkTheme) WAYPOINT_DARK_GREY else MARKER_RING,
            )
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
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val centre = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (halo != null) {
            paint.color = halo.toArgb()
            canvas.drawCircle(centre, centre, haloRadius, paint)
        }
        paint.color = fill.toArgb()
        canvas.drawCircle(centre, centre, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ringWidth
        paint.color = ring.toArgb()
        canvas.drawCircle(centre, centre, radius, paint)
        return MarkerSymbol(AndroidBitmap(bitmap), MarkerSymbol.HotspotPlace.CENTER, false)
    }

    /**
     * A teardrop: a circle of [radius] tangent to two lines converging [tipLength] below its
     * centre, at the exact angle that meets the circle smoothly rather than a triangle
     * glued onto it. The tip is the hotspot - see [MarkerSymbol.HotspotPlace.BOTTOM_CENTER] -
     * so the point the pin actually marks is where the geometry says it is, not the middle
     * of the bitmap holding it.
     */
    private fun pin(radius: Float, tipLength: Float, ringWidth: Float, fill: Color, ring: Color): MarkerSymbol {
        // Full margin above and to the sides, for the stroke's overflow past the circle;
        // only half below, and a round join there rather than the default miter - a sharp
        // point mitred would spike well past the ring's own width.
        val topPad = ringWidth
        val bottomPad = ringWidth / 2
        val width = kotlin.math.ceil(2 * radius + 2 * topPad).toInt() + 1
        val height = kotlin.math.ceil(radius + tipLength + topPad + bottomPad).toInt() + 1
        val cx = width / 2f
        val cy = topPad + radius
        val tipY = cy + tipLength

        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val path = teardropPath(cx, cy, radius, tipY)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = fill.toArgb()
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = ringWidth
        paint.color = ring.toArgb()
        canvas.drawPath(path, paint)
        // Punched through rather than painted, so the map shows in it in either theme.
        paint.style = Paint.Style.FILL
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        canvas.drawCircle(cx, cy, radius * PIN_HOLE_RATIO, paint)
        return MarkerSymbol(AndroidBitmap(bitmap), MarkerSymbol.HotspotPlace.BOTTOM_CENTER, false)
    }
}

/**
 * A circle centred at ([cx],[cy]) with radius [radius], its outline replaced between the
 * two points tangent to a line down to ([cx],[tipY]) with the two tangent lines themselves -
 * the classic map pin, computed exactly rather than approximated with a fixed-width wedge.
 */
internal fun teardropPath(cx: Float, cy: Float, radius: Float, tipY: Float): android.graphics.Path {
    val d = tipY - cy
    val angle = kotlin.math.acos((radius / d).coerceIn(-1f, 1f))
    val a1 = (Math.PI / 2).toFloat() - angle
    val a1Degrees = Math.toDegrees(a1.toDouble()).toFloat()
    // The long way round, over the top of the circle - the short way is the wedge the
    // tangent lines replace.
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

internal fun marker(at: TrackPoint, symbol: MarkerSymbol) =
    MarkerItem("", "", GeoPoint(at.latitude, at.longitude)).apply { marker = symbol }

/**
 * Everything [MarkerSymbols] draws, bottom first. Waypoints first, so the puck and the
 * scrub marker - both about right now - are never underneath one dropped earlier; [onTop]
 * last among them. The [selected] point last of all, so what is being read about is never
 * underneath anything.
 */
internal fun MarkerSymbols.items(
    waypoints: List<Waypoint>,
    onTop: Waypoint?,
    puck: TrackPoint?,
    selected: TrackPoint?,
): List<MarkerInterface> = buildList {
    waypoints.sortedBy { it == onTop }.forEach { add(marker(it.point, waypoint)) }
    if (puck != null) add(marker(puck, this@items.puck))
    if (selected != null) add(marker(selected, marker))
}

private const val MARKER_RING_WIDTH_DP = 1.5f

private const val MARKER_RADIUS_DP = 7f

private const val PUCK_RADIUS_DP = 8f

// 40dp tall with the ring's top edge: big enough to tap, since taps only hit the icon.
private const val PIN_RADIUS_DP = 14f

internal const val PIN_TIP_LENGTH_DP = 25.25f

private const val PIN_HOLE_RATIO = 0.4f

/** The pin's head, ring included. */
internal val WaypointPinHeadRadius = (PIN_RADIUS_DP + MARKER_RING_WIDTH_DP).dp

/** White in both themes: a surface-coloured ring vanished against dark-mode land. */
private val MARKER_RING = Color.White

// Greys rather than a theme colour, so a pin never reads as belonging to any one track.
// Inverted in dark mode, where a dark pin sank into the land.
private val WAYPOINT_DARK_GREY = Color(0xFF424242)

private val WAYPOINT_LIGHT_GREY = Color(0xFFE0E0E0)

private const val PUCK_HALO_RADIUS_DP = 14f

private const val PUCK_HALO_ALPHA = 0.24f
