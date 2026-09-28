package dev.samuelq.gpx.ui.map

import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.oscim.core.Box
import org.oscim.core.MapPosition
import org.oscim.event.Gesture
import org.oscim.event.GestureListener
import org.oscim.event.MotionEvent
import org.oscim.layers.Layer
import org.oscim.layers.vector.VectorLayer
import org.oscim.layers.vector.geometries.LineDrawable
import org.oscim.layers.vector.geometries.Style
import org.oscim.map.Map

/**
 * Stacking order, bottom first; VTM keeps each group together however late it's added.
 * Labels sit over routes (haloed) and so over the mask too, which is why [ClippedMapSource]
 * drops data past a file's edge before a name can be placed there.
 */
internal enum class LayerGroup { Land, Tiles, Mask, Outline, Routes, Trace, Labels, Markers, Tap }

/** A single tap anywhere on the map, in screen pixels. Double taps still zoom. */
internal class TapLayer(map: Map, private val onTap: (x: Float, y: Float) -> Unit) :
    Layer(map), GestureListener {
    override fun onGesture(g: Gesture, e: MotionEvent): Boolean {
        if (g !is Gesture.Tap) return false
        onTap(e.x, e.y)
        return true
    }
}

/** One style per colour: VectorLayer batches consecutive same-style lines into one draw. */
internal class RouteStyles(density: Density) {
    private val width = with(density) { ROUTE_WIDTH_DP.dp.toPx() }
    private val cache = HashMap<Int, Style>()

    @Synchronized
    fun of(color: Color): Style = cache.getOrPut(color.toArgb()) {
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

/** One line per segment, never across a gap. Stacked in list order, the last on top. */
internal fun List<RouteOverlay>.toLines(styles: RouteStyles): List<LineDrawable> {
    val out = ArrayList<LineDrawable>()
    forEachIndexed { priority, route ->
        // Within one priority VTM orders by spatial index, not insertion, so each route gets its own.
        val style = styles.of(route.color)
        route.forEachRun { from, to ->
            // A single position isn't a line; still drawn as the puck if it is live.
            if (to - from < 2) return@forEachRun

            val lonLat = DoubleArray((to - from) * 2)
            for (index in from until to) {
                val point = route.points[index]
                lonLat[(index - from) * 2] = point.longitude
                lonLat[(index - from) * 2 + 1] = point.latitude
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

/** Settle time before an overlay checks it drew for the current camera. */
private const val OVERLAY_CHECK_MS = 150L
