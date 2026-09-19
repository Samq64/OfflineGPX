# GPX Viewer

An Android app for cycling and hiking stats. Record a ride or a walk, or open a `.gpx`
file, and get a **route**, a **speed timeline** and an **elevation profile** on one shared,
synchronised scrubber.

Offline maps are planned. Until they land the route is drawn on a plain surface - the shape
alone is recognisable, and the slot a map renders into is already there.

## The permission budget

The goal is **no `INTERNET`, permanently, and as few other permissions as possible** while
still being a real tool for cycling and hiking.

| Permission | Status | Why |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | **never** | stripped from the merged manifest; map regions are downloaded by the *browser* |
| storage | never | SAF grants access to exactly the file the user picked — tracks and map regions alike |
| camera, microphone, Bluetooth, contacts | never | no feature needs them |
| `ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` | **declared** | the irreducible cost of being a tracker; asked for on the tap that starts a recording, never at launch |
| `POST_NOTIFICATIONS` | declared | so the recording notification is seen. Refusing it does not stop recording |
| `ACCESS_COARSE_LOCATION` | forced, never used | see *Recording* |

Two things make the network claim structural rather than aspirational:

1. `INTERNET` cannot be requested lazily — it is a manifest declaration, so once added it
   is permanent. Maps are therefore built around `ACTION_VIEW` into a browser.
2. The manifest merger folds in every dependency's permissions, so a library can add
   `INTERNET` without anyone writing it. `AndroidManifest.xml` carries `tools:node="remove"`
   entries that strip it back out regardless. Verify what shipped:

```sh
./gradlew :app:processReleaseMainManifest
grep uses-permission app/build/intermediates/merged_manifest/release/AndroidManifest.xml
```

A permission added here must map to a feature a user can name.

## Status

Compiles. Not yet run on a device - treat the first launch, and the first real recording
in particular, as the review. The recorder has never sampled a fix outside a reading of the
code.

Dependency versions were resolved against Maven Central and Google's Maven. Note that KSP
dropped its `<kotlin>-<ksp>` version pairing at 2.3.0 and is versioned independently now,
so it no longer tracks the Kotlin version.

The Gradle wrapper JAR is not included. Run `gradle wrapper --gradle-version 9.7.1`, or
open the project in Android Studio.

## Stack

| | |
|---|---|
| Language | Kotlin 2.4.20 |
| UI | Jetpack Compose, Material 3 (Compose BOM 2026.08.00) |
| Build | AGP 9.4.0 on Gradle 9.7.1, JDK 17 |
| minSdk / targetSdk | 29 (Android 10) / 37 (Android 17) |

Dependencies are AndroidX, Compose, Room, navigation-compose and kotlinx.serialization.
The original no-dependency minimalism is **no longer the governing constraint** — the
permission budget is. A dependency earns its place by beating the hand-rolled code it
replaces; what disqualifies one is pulling `INTERNET` into the merged manifest or wanting
a permission for a feature nobody asked for. None of these four do either.

Still hand-rolled, deliberately: the charts and the route canvas (a library gives no
shared-domain scrubber and no extreme-preserving reduction), `GpxParser`/`GpxWriter`
(streaming and tolerant, where `jpx` builds an object model), and `AppContainer` — the
recording service reaches the graph through the Application, which is the one place the
lack of Hilt shows and still not enough to pay for it.

`minSdk 29` because Chrome dropped below Android 10 at version 140. Material You is gated
on the *device's* version, so Android 12+ gets the wallpaper palette and 10/11 fall back to
a static scheme from the same ramp.

## Architecture

```
core/            Pure Kotlin. No Android imports, directly unit-testable.
  model/         Track, TrackSegment, TrackPoint - a faithful view of the file.
  analysis/      Geo + TrackAnalyzer -> TrackProfile (distance, speed, ascent, stats).
data/
  gpx/           GpxParser + GpxWriter: streaming, tolerant of real-world GPX.
  db/            Room: one `tracks` row per track, summaries only, no geometry.
  record/        LocationSource, RecordingWal, RecordingService, RecordingController.
  track/         TrackRepository (interface) + GpxTrackRepository (SAF + Room).
ui/
  chart/         ChartSeries, scales, ProfileChart - the Canvas charts.
  map/           Home. Visible routes overlaid in one projection.
  track/         One track: route, summary, scrub readout, charts. RouteCanvas.
  library/       Manage: import, export, rename, show/hide, batch delete.
  record/        The live recorder.
  nav/           @Serializable routes for navigation-compose.
di/              AppContainer: manual wiring.
```

## How it is put together

Recording is implemented; offline maps are not. Between them they decided the shape of the
storage and navigation layers, which is why those were replaced before either was built.

### The map is home, the list is for managing

The app opens on the map, showing every track the user has chosen to show. Dumping the
whole library onto one canvas is noise, so visibility is a per-track property, persisted,
and curated in the list — a screen that decides for you is worse than one you point at what
you want.

There is exactly one ordering in the app: **most recently interacted with first.** The list
shows it top-down; the map walks the same list backwards so the track you last touched is
painted last and lands on top of the pile. The two agree by construction rather than by two
orderings kept in step.

Colour does *not* follow that order. It is assigned per track at import and never
recomputed, because a hue that changed when you tapped something would be worse than any
stacking order. The list shows each track's swatch, so the two surfaces read against each
other without a legend. Six colours, then they repeat — past about six overlaid routes the
canvas is unreadable whatever the palette does, and the answer is to hide some.

Import lives in the list rather than on the map: the app makes its own GPX files now, so
bringing one in from elsewhere is the rarer action, and the map's one button should be the
common one.

### A recorded track is a GPX file

Room indexes tracks; it does not store their points. A recording is written to a `.gpx`
file in app-private storage and the database keeps one row holding the summary and a
pointer. The only difference between an imported track and a recorded one is whether that
pointer is a SAF URI the user owns or an internal path the app owns.

30,000 rows per ride in SQLite would buy nothing here — nothing queries a point across
tracks, and every screen loads one track and walks it end to end. Against that, files give
free export (a recording is *already* the format the user wants out), one read path
through `GpxParser` and `TrackAnalyzer` rather than two that can diverge, and a database
small enough that stats queries need no thought about indices.

The cost: a half-finished recording is not valid GPX. So the recorder appends to a
**write-ahead file**, one line per fix, and converts to GPX on stop. A crash or a flat
battery mid-ride leaves a complete WAL that recovers on next launch instead of truncated
XML. This is the one place the app keeps a format of its own, and it exists so that losing
a recording is not a possible outcome.

### What Room is for

One `tracks` table over both sources, because the library shows them in one list and the
stats want them in one query: a stable `id` (what navigation routes carry — never a URI),
a source discriminator, and the summary every list row and stat screen reads.

This is what the JSON file could not do. Distance this month or ascent this year becomes
one query instead of parsing fifty files, and navigation stops carrying URIs.

The JSON store it replaced was dropped outright rather than migrated — there was no
released version to carry forward.

### Recording

**Platform `LocationManager`, not Play Services.** `FusedLocationProviderClient` lives in
`play-services-location`, which pulls in Google's stack — exactly the dependency this
app's permission argument exists to avoid. `GPS_PROVIDER` is in the platform and gives raw
fixes at 1 Hz, which is what the analyzer wants anyway.

A `location`-typed foreground service owns the recording, with a notification showing
distance and elapsed time. It is also the only component that needs injecting, which is
the argument for Hilt — deferred until the service exists.

**There is no approximate-location mode.** Android 12+ ignores a fine-location request
that does not also ask for coarse, so `ACCESS_COARSE_LOCATION` appears in the manifest
whether or not it is wanted. That is a platform pairing, not a capability. Approximate
location is accurate to hundreds of metres at best; a route drawn from it is noise and a
distance or speed computed from it is a wrong number presented as a real one. Granted
approximate only, recording says it needs precise location and does not start.

### Offline maps

`Track` keeps raw lat/lon and `TrackProfile` shares its indices, so the scrubber index
already identifies a map position: a map section slots into `TrackScreen` above the charts
and reads the same `selectedIndex`.

**Region files, never tiles.** Per-tile fetching would stream the user's viewport to a
server continuously. Instead the app renders from large regional vector files on disk.

Not OpenStreetMap directly — the OSMF tile policy prohibits apps using
`tile.openstreetmap.org`, and OSM serves no prebuilt regional files. Extracts come from
the Mapsforge server, or **OpenAndroMaps** for hiking maps with contour lines. Renderer:
**Mapsforge**, pure Java, offline by design. MapLibre Native looks better but drags in
machinery this app will not use. Either way it becomes the largest dependency here.

**The app never downloads anything. It hands the browser a URL.**
`startActivity(ACTION_VIEW, <region url>)` needs no permission: the browser fetches, the
file lands in Downloads, and the user imports it through SAF like a GPX. Mapsforge's
layout is predictable enough to deep-link at the file, so the download starts on tap:

```
https://download.mapsforge.org/maps/v5/<continent>/<country>.map
https://download.mapsforge.org/maps/v5/europe/germany/bayern.map   # large countries split
```

OpenAndroMaps has the better hiking maps but spreads regions over paginated pages, ships
ZIPs and needs render themes fetched separately — an advanced option, not the default.

The rules that keep the claim true:

1. **The region index ships in the APK.** Names, bounding boxes, sizes, URLs. "Which
   region do I need?" resolves offline; as a server call it would leak the track's
   location before the user was asked.
2. **Confirm before leaving the app**, showing region, size and host. A "don't ask again"
   setting is allowed; asking is the default.
3. **No network code at all.** No HTTP client, no analytics, no crash reporting, no update
   checks.
4. **Degrade, don't nag.** A track with no local region shows the charts and an offer,
   never a blocking dialog or an empty grey map.

Two notes: pin `v5` to the Mapsforge version in use — it is the *format* version, so
upgrading may mean a different directory. And copy imported regions into app-private
storage, because Mapsforge needs random access and cloud SAF providers frequently cannot
seek.

## Design decisions worth knowing

**Speed is differentiated over a ~10 s window, not per sample.** A 1 Hz consumer GPS has
metres of horizontal noise, so a per-hop derivative swings wildly and its "max speed" is
pure noise. A test asserts a 25 m single-sample glitch does not become a sprint.

**Ascent uses a 3 m hysteresis threshold on smoothed elevation**, or a track that sat
still for an hour accumulates hundreds of metres of phantom climbing.

**Segment boundaries are respected everywhere.** A `<trkseg>` break means lost signal, so
distance does not accumulate across it, the speed window does not span it, and the chart
does not draw through it.

**Average speed is over moving time**, matching what watches report.

**The two chart colours are fixed, and Material You does not repaint them.** They were
validated for lightness band, chroma floor, CVD separation and contrast against both
surfaces; a wallpaper-derived palette carries no such guarantee, and "blue is speed"
should not vary by device. Chrome follows the wallpaper; data does not.

**Long tracks are reduced per pixel column, keeping the extremes.** A 30k-point track on a
1000px chart collapses each column to first/min/max/last. Every-nth subsampling would
delete the peaks, which on a speed chart are the whole point of looking.

**The track screen puts the route behind and the numbers in a sheet over it**, rather than
both competing in one scroll. That also settles the gesture question - the sheet's handle
owns vertical drag, everything above it belongs to the route. The sheet cannot be
dismissed: a state where the summary is gone entirely is not worth reaching.

**The scrubber runs both ways.** Dragging a chart moves the marker on the route, and
tapping the route moves the chart crosshairs, because `TrackProfile` shares its indices
with the track's lat/lon. That is what a route view buys over a picture of one.

**The route is projected equirectangular with longitude scaled by cos(latitude).** Over one
activity's extent the error against a true Mercator is below a pixel. Taking the extent in
raw degrees instead would stretch the shape a third too wide at 50N.

## Tests

`./gradlew test` — JVM unit tests over the parser and the analyzer, no device needed.
`GpxParser` takes its `XmlPullParser` as a constructor parameter so it can be tested with
kxml2 on a plain JVM; `android.jar`'s xmlpull classes are stubs in unit tests.

## Known gaps

- GPX only. No FIT, TCX, KML.
- `<extensions>` are skipped, so heart rate, cadence and power are not read.
- Metric only. All user-facing formatting is in `ui/format/Formatters.kt`, so an imperial
  toggle is a one-file change.
- No offline map yet. The route is drawn on a plain surface; the slot is there.
- Recording has never been run on a device.
- No stats over time. The schema is a table of summaries, so "distance this month" is one
  query away, but nothing asks it yet.
- No comparison view. Overlaying routes on the map is as far as it goes; the charts show
  one track at a time.
- **The charts are not readable without a pointer.** The per-point table that used to
  cover this was removed; `ProfileChart` carries only a `contentDescription`, so TalkBack
  announces "Speed" and no values. The fix is semantics on the scrub readout plus d-pad
  stepping of `selectedIndex`, not bringing the table back.

## Why this exists

**A cycling and hiking app that behaves like an Android app and cannot talk to the
network.** Concretely:

- **No onboarding, no account, no login prompt** — optional or otherwise. First launch is
  the empty list and a button.
- **Native UI.** Not a web view, not a desktop toolkit wearing an Android skin.
- **Never intercept the back gesture.** Back at the root exits, with no confirmation
  dialog, ever. Predictive back is on.
- **No network, and the fewest permissions the features allow.**
- **Charts worth reading.** A synchronised speed and elevation scrubber is the feature,
  not a checkbox.

Anything that makes this feel like a port of something else is a bug.
