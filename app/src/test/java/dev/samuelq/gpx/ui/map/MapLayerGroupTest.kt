package dev.samuelq.gpx.ui.map

import org.mapsforge.map.awt.graphics.AwtGraphicFactory
import org.mapsforge.map.layer.GroupLayer
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.model.DisplayModel
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertEquals

/**
 * Refilling a layer group.
 *
 * A layer normally receives its display model from `Layers.add`, and putting one straight
 * into [GroupLayer.layers] bypasses that. `GroupLayer` hands its own down only to the
 * children present at the moment it is itself added, and these groups are added empty and
 * filled afterwards - so a child that misses out draws with a null display model and takes
 * the render thread down with it. That was a launch crash, which is why it is pinned here.
 */
class MapLayerGroupTest {

    private val displayModel = DisplayModel()

    private fun polyline() = Polyline(AwtGraphicFactory.INSTANCE.createPaint(), AwtGraphicFactory.INSTANCE)

    @Test
    fun `every child is given the display model`() {
        val group = GroupLayer()
        val children = listOf(polyline(), polyline(), polyline())

        group.replaceWith(children, displayModel)

        children.forEach { assertSame(displayModel, assertNotNull(it.displayModel)) }
    }

    /** Refilling replaces, never appends - a stale route must not outlive its update. */
    @Test
    fun `refilling drops what was there`() {
        val group = GroupLayer()
        group.replaceWith(listOf(polyline(), polyline()), displayModel)

        val replacement = polyline()
        group.replaceWith(listOf(replacement), displayModel)

        assertEquals(1, group.layers.size)
        assertSame(replacement, group.layers.single())
        assertNotNull(replacement.displayModel)
    }

    /** Emptying is how the recording's trace disappears when a ride ends. */
    @Test
    fun `a group can be emptied`() {
        val group = GroupLayer()
        group.replaceWith(listOf(polyline()), displayModel)
        group.replaceWith(emptyList(), displayModel)
        assertEquals(0, group.layers.size)
    }
}
