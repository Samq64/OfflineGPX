# Validation

Measurements taken 22 September 2026, against `download.mapsforge.org/maps/v5/`.

## The cut is content-identical to its source

An Andorra cut (`1.50,42.50 .. 1.60,42.58`) compared tile-by-tile against the full national
file, both read through mapsforge's own `MapFile` reader. A tile matches when its POI
positions, way geometries and tag sets are all equal.

| zoom | identical | differ | of those, straddling the cut edge |
|---|---|---|---|
| z12 | 1 | 3 | 3 |
| z13 | 6 | 3 | 3 |
| z14 | 30 | 0 | — |
| z15 | 110 | 0 | — |
| z16 | 399 | 0 | — |

Every difference is a tile that crosses the boundary, where the output legitimately holds
less than the source. z14 is the deepest base zoom, so z14–z16 is where copied blocks are
read back directly; all 539 are identical.

## Transfer efficiency

| cut | source | output | range requests | upstream bytes |
|---|---|---|---|---|
| Andorra 8 × 8 km | 2.4 MB | 1.16 MB | 16 | 1.17 MB |
| Berlin centre 5 × 5 km (`13.36,52.49 .. 13.44,52.535`) | 51.6 MB | 5.71 MB | 13 | 5.71 MB |

Overfetch is negligible because one output row's tiles are contiguous upstream, so a row
costs one request regardless of how wide it is. Each zoom interval's index is read in one
request for all rows; the columns outside the box come along at 5 bytes a tile.
