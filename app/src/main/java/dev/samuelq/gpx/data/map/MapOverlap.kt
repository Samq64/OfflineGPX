package dev.samuelq.gpx.data.map

import java.io.IOException

/**
 * How much of one map's data the other already has, 0 to 1, whichever way is more. Over the
 * tiles both boxes cover, each map's bytes above its own filler are compared tile by tile.
 *
 * Boxes alone can't say: an extract fills its whole box with land and sea polygons, so a
 * neighbour's box can lie inside another's. Those polygons are the filler, tens of bytes a
 * tile against kilobytes of real data, which only fills a map's own region. Neighbours
 * measured at most 0.11 and a duplicate 1.
 *
 * Null when it can't be told from the files, as with differing base zooms.
 */
internal fun sharedData(a: OfflineMap, b: OfflineMap): Double? {
    val sa = a.header.deepest ?: return null
    val sb = b.header.deepest ?: return null
    if (sa.baseZoom != sb.baseZoom) return null
    val ra = TileRange(a.header, sa.baseZoom)
    val rb = TileRange(b.header, sb.baseZoom)
    val left = maxOf(ra.left, rb.left)
    val right = minOf(ra.right, rb.right)
    val top = maxOf(ra.top, rb.top)
    val bottom = minOf(ra.bottom, rb.bottom)
    if (left > right || top > bottom) return 0.0

    // Rows are sampled past a budget, so a country against a continent stays quick.
    val columns = right - left + 1
    val rows = bottom - top + 1
    val step = maxOf(1L, (rows * columns + MAX_SAMPLED_TILES - 1) / MAX_SAMPLED_TILES)

    val (bytesA, bytesB) = try {
        TileIndex(a.file, a.header, sa).use { ia ->
            TileIndex(b.file, b.header, sb).use { ib ->
                val sampled = (top..bottom step step).toList()
                sampled.flatMapTo(ArrayList()) { ia.row(it, left, right).asList() }.toLongArray() to
                    sampled.flatMapTo(ArrayList()) { ib.row(it, left, right).asList() }.toLongArray()
            }
        }
    } catch (_: IOException) {
        return null
    }

    val ca = bytesA.aboveFiller()
    val cb = bytesB.aboveFiller()
    val shared = ca.indices.sumOf { minOf(ca[it], cb[it]) }.toDouble()
    return maxOf(shared / ca.sum().coerceAtLeast(1), shared / cb.sum().coerceAtLeast(1))
}

/** Each tile's bytes less a low percentile, which is the land and sea filler. */
private fun LongArray.aboveFiller(): LongArray {
    val nonEmpty = filter { it > 0 }.sorted()
    val filler = if (nonEmpty.isEmpty()) 0 else nonEmpty[(nonEmpty.size * FILLER_PERCENTILE).toInt()]
    return LongArray(size) { (this[it] - filler).coerceAtLeast(0) }
}

/** At or above this, an import is taken to duplicate a map rather than border it. */
internal const val DUPLICATE_SHARE = 0.5

/** About 2.5 MB of index per map, and 4 MB of sizes held. */
private const val MAX_SAMPLED_TILES = 500_000L

private const val FILLER_PERCENTILE = 0.05
