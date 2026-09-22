# Validation

Measurements taken 22 September 2026, against `download.mapsforge.org/maps/v5/` and the
Protomaps planet build `20260917.pmtiles`.

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
| Andorra 8 × 8 km | 2.4 MB | 1.16 MB | 22 | 1.17 MB |
| Berlin centre 5 × 5 km | 51.6 MB | 5.51 MB | 14 | 5.51 MB |

Overfetch is negligible because one output row's tiles are contiguous upstream, so a row
costs one request regardless of how wide it is.

## Format size comparison

Same region, same day's OSM data, cut both ways. Iceland, chosen so one source covers both
a small town and a large empty area.

**Selfoss — 5 × 5 km, population ~9,500**

| format | size |
|---|---|
| `.pmtiles` z0–15 | 1.23 MB |
| `.pmtiles` z12–15 (the range the app can display) | 599 KB |
| `.map` | 307 KB |

**Icelandic highlands — 200 × 200 km, near-zero population**

| format | size |
|---|---|
| `.pmtiles` z0–15 | 68.3 MB |
| `.map` | 11.3 MB |

`.map` is 4× smaller on the town and 6× smaller on the wilderness. Two structural reasons:
PMTiles stores a tile at every zoom 0–15 while mapsforge stores three base zooms (5/10/14)
and renders the rest by scaling; and PMTiles carries a fixed ~600 KB–1.2 MB of global
low-zoom context, which is half of a small extract.

Not a like-for-like content comparison: the Protomaps basemap carries multilingual
`name:*` values and layers this app never styles, while the mapsforge writer ran with its
default tag config and `simplification-factor=2.5`.
