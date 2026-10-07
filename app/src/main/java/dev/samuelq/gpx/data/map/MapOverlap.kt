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
    val sa = a.deepest
    val sb = b.deepest
    if (sa.baseZoomLevel != sb.baseZoomLevel) return null
    val left = maxOf(sa.boundaryTileLeft, sb.boundaryTileLeft)
    val right = minOf(sa.boundaryTileRight, sb.boundaryTileRight)
    val top = maxOf(sa.boundaryTileTop, sb.boundaryTileTop)
    val bottom = minOf(sa.boundaryTileBottom, sb.boundaryTileBottom)
    if (left > right || top > bottom) return 0.0

    // Rows are sampled past a budget, so a country against a continent stays quick.
    val columns = right - left + 1
    val rows = bottom - top + 1
    val step = maxOf(1L, (rows * columns + MAX_SAMPLED_TILES - 1) / MAX_SAMPLED_TILES)

    val (bytesA, bytesB) = try {
        TileIndex(a.file, sa).use { ia ->
            TileIndex(b.file, sb).use { ib ->
                val sampled = (top..bottom step step).toList()

                // Unboxed: up to MAX_SAMPLED_TILES sizes each.
                fun TileIndex.sizes() = LongArray(sampled.size * columns.toInt()).also { out ->
                    sampled.forEachIndexed { i, y -> row(y, left, right).copyInto(out, i * columns.toInt()) }
                }
                ia.sizes() to ib.sizes()
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
    val sorted = copyOf().apply { sort() }
    val first = sorted.indexOfFirst { it > 0 }
    val filler = if (first < 0) 0 else sorted[first + ((size - first) * FILLER_PERCENTILE).toInt()]
    return LongArray(size) { (this[it] - filler).coerceAtLeast(0) }
}

/** At or above this, an import is taken to duplicate a map rather than border it. */
internal const val DUPLICATE_SHARE = 0.5

/** About 2.5 MB of index per map, and 4 MB of sizes held. */
private const val MAX_SAMPLED_TILES = 500_000L

private const val FILLER_PERCENTILE = 0.05
