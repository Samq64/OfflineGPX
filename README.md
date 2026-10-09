# mapcut

A prototype extract service for mapsforge `.map` files: cut the area you want out of a
published file, without downloading all of it.

```sh
node server.mjs          # http://localhost:8787
node bin/mapcut.mjs https://download.mapsforge.org/maps/v5/europe/andorra.map out.map 1.50,42.50,1.60,42.58
```

No dependencies, no build step.

The page is built for a phone as much as a desktop — the phone is where the `.map` file is
wanted, and a map cut on a laptop has to be moved across to be any use. The panel sits under
the map on a narrow screen, and the rectangle is dragged out with a finger: the drawing runs
on pointer events, since a touch screen sends no `mousemove` while a finger is down.

One wrinkle when serving over the LAN: Chrome calls a file from an `http://` page an
insecure download and asks before keeping it. Answer **Keep**. Serving this over TLS is the
only real fix and is beyond what a prototype is for.

## How it works

Upstream is `download.mapsforge.org/maps/v5/`, which mirrors the Geofabrik tree
(continent → country → sub-region), serves raw `.map` files, and — the part that matters —
answers byte-range requests. So a cut is a **byte copy out of a bigger file**, never a
re-encode: 5 × 5 km of central Berlin comes out of the 51.6 MB Berlin file in 13 range
requests with essentially no overfetch.

Three properties of the format make that possible:

- tile coordinates are stored relative to their own tile's origin, so a copied block stays
  valid as long as it lands at the same tile position in the output;
- tags are indices into dictionaries held in the header, so copying those verbatim keeps
  every index inside every block resolving;
- each zoom interval's index is a dense row-major array, so one output row's tiles are
  contiguous upstream and arrive in a single range request.

`comment` and `created_by` are carried over verbatim — that is where a file's ODbL
attribution lives. Start position and start zoom are dropped, since the source's are
almost certainly outside the new box.

## Why there is a server at all

`download.mapsforge.org` sends no `Access-Control-Allow-Origin`, so a browser cannot range-read
it directly. The cut happens server-side and the browser receives the finished file. That is
the only reason this is not a static page.

## Correctness

Validated against mapsforge's own `MapFile` reader: for a cut of Andorra, every tile from
z14 (the deepest base zoom) through z16 returns content identical to reading the same tile
out of the full national file — 539 tiles, zero differences. The only tiles that differ are
at z12–z13 and all of them straddle the cut boundary, where the output genuinely holds less
data than the source. See `docs/mapcut-validation.md`.

## Limits

- Rectangles only. Polygons are a superset: out-of-polygon entries in the output index
  would all point at one shared empty block.
- One source file per cut. An area spanning two upstream regions needs a merge, which is
  not a byte copy — the two files' tag dictionaries are independent, so every index in
  every block would need remapping.
- Files carrying debug signatures are rejected rather than handled. Nothing published
  ships them.
- `download.mapsforge.org` is a volunteer-run server with no published reuse policy.
  Anything beyond a prototype should mirror the regions it needs.
