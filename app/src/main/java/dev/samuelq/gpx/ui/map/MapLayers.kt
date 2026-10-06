package dev.samuelq.gpx.ui.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.oscim.core.Box
import org.oscim.core.MapPosition
import android.view.ViewConfiguration
import org.oscim.event.Event
import org.oscim.event.MotionEvent
import org.oscim.layers.vector.VectorLayer
import org.oscim.layers.vector.geometries.LineDrawable
import org.oscim.layers.vector.geometries.Style
import org.oscim.map.Map
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Stacking order, bottom first; VTM keeps each group together however late it's added.
 * Labels sit over routes (haloed) and so over the mask too, which is why [dev.samuelq.gpx.data.map.ClippedMapSource]
 * drops data past a file's edge before a name can be placed there.
 */
internal enum class LayerGroup { Land, Tiles, Mask, Outline, Routes, Trace, Labels, Markers }

/**
 * A single tap anywhere on the map, in screen pixels, reported on lift. Not VTM's `TAP`,
 * which comes from onSingleTapConfirmed and so waits out the double-tap timeout on every
 * tap. The second tap of a double tap is swallowed: VTM zooms on it, and a second
 * selection would undo the first.
 *
 * Holding the second tap and dragging zooms around it, down to zoom in, as in other map
 * apps: one-handed, and the only way out besides a pinch. [doublingPx] of drag doubles the scale.
 */
internal class TapDetector(
    config: ViewConfiguration,
    private val map: Map,
    private val doublingPx: Float,
    private val onTap: (x: Float, y: Float) -> Unit,
) : Map.InputListener {
    private val slop = config.scaledTouchSlop.toFloat()
    private val doubleTapSlop = config.scaledDoubleTapSlop.toFloat()

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var candidate = false
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var lastTapAt = Long.MIN_VALUE / 2

    private var scaling = false
    private var scaledY = 0f

    // VTM passes a null event.
    override fun onInputEvent(e: Event?, motion: MotionEvent) {
        when (motion.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                downX = motion.x
                downY = motion.y
                downAt = motion.time
                candidate = true
                if (motion.time - lastTapAt <= ViewConfiguration.getDoubleTapTimeout() &&
                    hypot(downX - lastTapX, downY - lastTapY) <= doubleTapSlop
                ) {
                    // Before any move, so VTM doesn't pan the drag too.
                    scaling = true
                    scaledY = motion.y
                    map.eventLayer.enableMove(false)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (hypot(motion.x - downX, motion.y - downY) > slop) candidate = false
                if (scaling && !candidate) {
                    val factor = 2.0.pow(((motion.y - scaledY) / doublingPx).toDouble()).toFloat()
                    scaledY = motion.y
                    // Pivot relative to the centre, as VTM takes it.
                    map.viewport().scaleMap(factor, downX - map.width / 2f, downY - map.height / 2f)
                    map.updateMap(true)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                candidate = false
                endScaling()
            }

            MotionEvent.ACTION_UP -> {
                endScaling()
                if (!candidate || motion.time - downAt > ViewConfiguration.getLongPressTimeout()) return
                candidate = false
                val second = motion.time - lastTapAt <= ViewConfiguration.getDoubleTapTimeout() &&
                    hypot(downX - lastTapX, downY - lastTapY) <= doubleTapSlop
                if (second) {
                    lastTapAt = Long.MIN_VALUE / 2
                    return
                }
                lastTapX = downX
                lastTapY = downY
                lastTapAt = motion.time
                onTap(downX, downY)
            }
        }
    }

    private fun endScaling() {
        if (!scaling) return
        scaling = false
        map.eventLayer.enableMove(true)
    }
}

/** One style per colour and width: VectorLayer batches consecutive same-style lines into one draw. */
internal class RouteStyles(density: Density) {
    val width = with(density) { ROUTE_WIDTH_DP.dp.toPx() }
    val focusedWidth = with(density) { FOCUSED_ROUTE_WIDTH_DP.dp.toPx() }
    private val cache = HashMap<Pair<Int, Float>, Style>()

    @Synchronized
    fun of(color: Color, width: Float = this.width): Style = cache.getOrPut(color.toArgb() to width) {
        Style.builder()
            .strokeColor(color.toArgb())
            .strokeWidth(width)
            .cap(org.oscim.backend.canvas.Paint.Cap.ROUND)
            .fixed(true)
            // A long ride has far more positions than pixels.
            .generalization(Style.GENERALIZATION_SMALL)
            .build()
    }
}

/**
 * One line per segment, never across a gap. Stacked in list order, the last on top.
 *
 * [focusedId]'s route is told apart by more than its colour, which repeats past six tracks
 * and is the only difference to some colour vision: it's drawn wider and on top, and the
 * rest are dimmed.
 */
internal fun List<RouteOverlay>.toLines(styles: RouteStyles, focusedId: Long? = null): List<LineDrawable> {
    val out = ArrayList<LineDrawable>()
    val focusing = focusedId != null && any { it.trackId == focusedId }
    forEachIndexed { index, route ->
        val focused = focusing && route.trackId == focusedId
        // Within one priority VTM orders by spatial index, not insertion, so each route gets its own.
        val priority = if (focused) size else index
        val style = when {
            focused -> styles.of(route.color, styles.focusedWidth)
            focusing -> styles.of(route.color.copy(alpha = UNFOCUSED_ALPHA))
            else -> styles.of(route.color)
        }
        route.forEachRun { from, to ->
            // A single position isn't a line; still drawn as the puck if it is live.
            if (to - from < 2) return@forEachRun

            val lonLat = DoubleArray((to - from) * 2)
            for (i in from until to) {
                lonLat[(i - from) * 2] = route.points.longitude(i)
                lonLat[(i - from) * 2 + 1] = route.points.latitude(i)
            }
            out.add(LineDrawable(lonLat, style).also { it.priority = priority })
        }
    }
    return out
}

/**
 * A [VectorLayer] that keeps up with the camera. Layers attached around the start-up framing
 * often missed VTM's recompute and kept a whole-world pass (visibly, an unmasked outside), so
 * after any map event it re-checks until its last pass matches the current camera.
 */
internal class OverlayLayer(private val owner: Map) : VectorLayer(owner) {
    private val drawnFor = MapPosition()
    private val current = MapPosition()
    @Volatile private var drawnValid = false
    @Volatile private var checking = false

    override fun processFeatures(t: Task, b: Box) {
        // VTM passes NaN while the view has no size; not a pass for any camera.
        if (b.xmin.isNaN()) return
        super.processFeatures(t, b)
        synchronized(drawnFor) { drawnFor.copy(t.position) }
        drawnValid = true
    }

    override fun onMapEvent(e: org.oscim.event.Event, pos: MapPosition) {
        super.onMapEvent(e, pos)
        if (!checking) {
            checking = true
            owner.postDelayed(::check, OVERLAY_CHECK_MS)
        }
    }

    private fun check() {
        owner.viewport().getMapPosition(current)
        val behind = synchronized(drawnFor) {
            !drawnValid || current.x != drawnFor.x || current.y != drawnFor.y || current.scale != drawnFor.scale
        }
        if (behind) {
            update()
            owner.postDelayed(::check, OVERLAY_CHECK_MS)
        } else {
            checking = false
        }
    }
}

/** A [VectorLayer] that remembers what it holds, since VTM offers no way to ask or to clear. */
internal class LineLayer(map: Map) {
    val layer = OverlayLayer(map)
    private val drawn = ArrayList<LineDrawable>()

    fun replaceWith(next: List<LineDrawable>) {
        synchronized(layer) {
            drawn.forEach { layer.remove(it) }
            drawn.clear()
            next.forEach { layer.add(it) }
            drawn.addAll(next)
        }
        layer.update()
    }
}

private const val ROUTE_WIDTH_DP = 3f
private const val FOCUSED_ROUTE_WIDTH_DP = 5f
private const val UNFOCUSED_ALPHA = 0.45f

/** Settle time before an overlay checks it drew for the current camera. */
private const val OVERLAY_CHECK_MS = 150L
