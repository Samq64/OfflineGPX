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
 * The stacking order, bottom first. VTM keeps each group's layers together however late
 * they are added, so a rebuilt basemap lands back under the routes rather than on top.
 *
 * Names over the routes, haloed, so a road stays readable where a track runs along it.
 * That puts them over the mask too, which is why [ClippedMapSource] drops what is past a
 * file's edge before a name can be placed on it.
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

/**
 * One style per colour, shared by every line drawn with it. VectorLayer batches
 * consecutive lines of the *same* style into one draw, so a style per line would cost a
 * draw call each.
 */
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
            // Simplified to a pixel at the zoom it is drawn at: a long ride has far more
            // positions than the screen has pixels to show them with.
            .generalization(Style.GENERALIZATION_SMALL)
            .build()
    }
}

/**
 * Every route as lines, one per segment - the gap between segments is signal loss and
 * nothing should be drawn across it. Stacked in list order, the last on top.
 */
internal fun List<RouteOverlay>.toLines(styles: RouteStyles): List<LineDrawable> {
    val out = ArrayList<LineDrawable>()
    forEachIndexed { priority, route ->
        // VTM draws by priority, higher later. Within one priority the order is whatever its
        // spatial index returns, not the order added, so each route gets its own.
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
 * A [VectorLayer] that keeps up with the camera. VTM recomputes one on camera events, but a
 * layer attached around the start-up framing move regularly missed it and went on showing
 * the whole-world view it was first computed for - the outside mask most visibly, which
 * left every map's surroundings unmasked until the camera next moved. So after any map
 * event, each layer checks whether its last pass was for the current camera, and goes
 * again until it was.
 */
internal class OverlayLayer(private val owner: Map) : VectorLayer(owner) {
    private val drawnFor = MapPosition()
    private val current = MapPosition()
    @Volatile private var drawnValid = false
    @Volatile private var checking = false

    override fun processFeatures(t: Task, b: Box) {
        // Skipped by VTM while the view has no size; not a pass for any camera.
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

    /** Swaps every line for [next] and asks for one redraw. */
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

/** How long an overlay lets the camera settle before checking it drew for it. */
private const val OVERLAY_CHECK_MS = 150L
