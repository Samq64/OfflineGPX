# Offline GPX

An Android app for cycling and hiking stats. Record a ride or a walk, or import a `.gpx`
file, and get a **route** over an offline map, a **speed timeline** and an **elevation
profile**, all on one shared, synchronised scrubber.

## The permission budget

The goal is **no `INTERNET`, permanently, and as few other permissions as possible** while
still being a real tool for cycling and hiking.

| Permission | Status | Why |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | **never** | stripped from the merged manifest, including MapLibre's own — basemaps come from files the user supplies, not from this app fetching anything |
| storage | never | tracks and offline maps alike are copied from a one-shot SAF pick into app-private storage; no persisted grant, no storage permission |
| camera, microphone, Bluetooth, contacts | never | no feature needs them |
| `ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` | **declared** | the irreducible cost of being a tracker; asked for on the tap that starts a recording, never at launch |
| `POST_NOTIFICATIONS` | declared | so the recording notification is seen. Refusing it does not stop recording |
| `ACCESS_COARSE_LOCATION` | forced, never used | Android 12+ ignores a fine request without a paired coarse one |

`INTERNET` can't be requested lazily — once declared it's permanent — so it's kept out
structurally rather than by vigilance. The manifest merger folds in every dependency's
permissions (MapLibre declares three network ones of its own), so
`AndroidManifest.xml` strips them back out with `tools:node="remove"`, and
`CheckNoNetworkPermissions` in `app/build.gradle.kts` fails the build if any survive into
the merged manifest — wired as a manifest transform, so it can't be skipped by any build
that produces an APK. Verify what shipped:

```sh
./gradlew :app:processReleaseMainManifest
grep uses-permission app/build/intermediates/merged_manifest/release/AndroidManifest.xml
```

A permission added here must map to a feature a user can name.

## Stack

| | |
|---|---|
| Language | Kotlin 2.4.20 |
| UI | Jetpack Compose, Material 3 (Compose BOM 2026.08.00) |
| Build | AGP 9.4.0 on Gradle 9.7.1, JDK 17 |
| minSdk / targetSdk | 29 (Android 10) / 37 (Android 17) |

Dependencies: AndroidX, Compose, Room, navigation-compose, kotlinx.serialization, MapLibre
Native. The original no-dependency minimalism isn't the governing constraint any more — the
permission budget is. A dependency earns its place by beating the hand-rolled code it
replaces; what disqualifies one is pulling `INTERNET` into the merged manifest, or wanting a
permission for a feature nobody asked for. MapLibre is the one dependency that costs real
size — a hand-rolled Compose canvas rendered routes before it, and was dropped in its
favour because it also did the projection, camera and hit-testing this app used to
maintain itself.

Still hand-rolled: the charts (no library gives a shared-domain scrubber or an
extreme-preserving per-column reduction), `GpxParser`/`GpxWriter` (streaming and tolerant,
where a full-object-model library would not be), and `AppContainer` (manual DI for a graph
of three objects — Hilt earns its keep once the recording service can be injected instead
of reaching the graph through `Application`, which it does today).

## Architecture

```
core/            Pure Kotlin. No Android imports, directly unit-testable.
  model/         Track, TrackSegment, TrackPoint - a faithful view of the file.
  analysis/      FixFilter, SpeedWindow, TrackAnalyzer -> TrackProfile.
data/
  gpx/           GpxParser + GpxWriter: streaming, tolerant of real-world GPX.
  db/            Room: one `tracks` row per track, summary only, no geometry.
  map/           MapStore + PmtilesHeader/Coverage/Metadata: offline basemap archives.
  record/        LocationSource, RecordingWal, RecordingService, RecordingController.
  settings/      SettingsRepository: units and the three recording thresholds.
  track/         TrackRepository (interface) + GpxTrackRepository (Room + app-private files).
ui/
  chart/         ChartMath, ProfileChart - the Canvas charts.
  map/           MapScreen, OfflineMapCanvas (MapLibre), MapChrome (scale bar), MapStyle.
  track/         TrackSheet + TrackDialogs + TrackSummary - the sheet a tapped route opens in.
  library/       Manage: import, export, rename, show/hide, batch delete.
  record/        RecordViewModel + RecordingBar, shown by the map. No screen of its own.
  settings/      Units, accuracy limit, minimum movement, update interval, offline maps.
  nav/           @Serializable routes for navigation-compose. Three: map, library, settings.
di/              AppContainer: manual wiring.
```

## How it's put together

### The map is home, the list is for managing

The app opens on the map, showing every track the user has chosen to show. Visibility is a
per-track property, persisted, and curated in the library — dumping the whole collection
onto one canvas is noise. Ordering is one thing everywhere: most recently interacted with
first. The library shows it top-down; the map draws the same list back to front, so the
track you last touched lands on top. Colour doesn't follow that order — it's assigned per
track at import and never recomputed, so a hue never changes under you.

A track is a selection on the map, not a screen you travel to. Tapping a route opens a
sheet over the same canvas; there's no second screen redrawing the route in isolation and
losing every other track off the side of it. The sheet has two heights — a peek (name and
headline numbers) and an expanded one (route still in view, numbers and charts below,
reached by scrolling). Dragging a chart moves the marker on the route; tapping the route
moves the chart crosshairs, because `TrackProfile` shares its indices with the track's
lat/lon.

### A track is a GPX file; Room only indexes it

Room doesn't store points. Both a recording and an import are written or copied into
app-private storage as a `.gpx` file, and the database keeps one row per track holding a
summary and a path. Files give free export, one read path through `GpxParser` and
`TrackAnalyzer`, and a database small enough that stats queries need no thought about
indices — nothing here queries a point across tracks. The cost is that a half-written
recording isn't valid GPX, so the recorder appends to a write-ahead file, one line per fix,
and converts to GPX on stop; a crash or a flat battery mid-ride leaves a complete WAL that
recovers at next launch instead of truncated XML.

An import used to be read where the user left it, behind a persisted SAF grant. It's copied
in at import time instead, so a recording and an import are the same kind of thing to the
rest of the app — deleting the row deletes the file, renaming rewrites the file, and export
is a byte copy either way.

### Recording

Platform `LocationManager` on `GPS_PROVIDER`, not `FusedLocationProviderClient` —
`play-services-location` pulls in Google's stack, which is exactly the dependency the
permission argument exists to avoid.

A fix isn't a position until something says so. `FixFilter` rejects a fix whose own accuracy
is worse than 25 m, and treats a fix that hasn't moved further than its own error circle as
not having moved — a stationary consumer GPS wanders inside that circle, and 1 Hz of that
wander is several km/h that never happened. A rejected reading isn't dropped: it comes back
as the last known position stamped with the new time, once every ten seconds, so a stop
reads as a stop (the speed line decays to zero and sits there) rather than as a hole
`TrackAnalyzer` has to infer. `SpeedWindow` differentiates over the same ~10 s window the
saved-track analysis uses, fed on every fix so it decays properly when you stop.

Gap detection (`TrackAnalyzer`) still earns its place for imported files, which mostly do
drop stationary samples, and for this app's own recordings when the signal is too poor to
conclude anything. The threshold is `max(setting, 10 × median interval)` rather than a fixed
number of seconds, because "unusually long" means something different for a 1 Hz recording
than for a route with one point per kilometre. A gap is drawn as a labelled wash across the
chart, not interpolated through.

Pause stops the receiver rather than discarding fixes at 1 Hz, which is what it used to do
— that cost battery and the data both for no reason once auto-detected stops made an
explicit pause redundant for splitting the chart. A recording under ten metres isn't saved;
crash recovery applies the same floor, so a leftover WAL can't resurrect what pressing Stop
would have thrown away.

There's no approximate-location mode: granted coarse-only, recording says it needs precise
location and doesn't start, because a route or a speed computed from hundred-metre accuracy
is a wrong number presented as a real one.

### Offline maps

MapLibre Native renders a basemap from a `.pmtiles` archive the user supplies through the
file picker — the app fetches nothing and ships no maps of its own. An archive is copied
into app-private storage at import (validated first; deleted again if it fails to parse).

Coverage is read from the tile directory, not trusted from the header's bounding box, which
is a lie for anything cut from a drawn polygon rather than a bbox. Two archives over the
same ground are two renderings of one place stacked, and no z-order makes that legible, so
importing a map that overlaps one already shown deletes the older one — newer supersedes
older automatically, rather than refusing the import and making the user delete the old one
by hand. Attribution, if the archive's own metadata carries one, is listed on the settings
screen; the app has no source of its own to credit.

`GpxApplication` tells MapLibre it's offline before any map exists — its
`ConnectivityReceiver` calls `getActiveNetworkInfo()` unguarded, which throws without
`ACCESS_NETWORK_STATE`, and that permission is one of the three stripped from the merged
manifest.

The camera is MapLibre's: pinch, fling, and a pan clamp to the fit of every track and shown
map. A scale bar reads the camera's live scale and is drawn over it. The map's own
composition doesn't survive navigating away to the library or settings and back -
Compose Navigation only keeps the current destination composed - so the camera's last
position is remembered in the ViewModel (which does survive) and restored directly on
return, rather than re-fitting to the tracks or the map from nothing every time.

The basemap style is deliberately plain: earth, one green for anything vegetated, water
(always blue, regardless of theme), buildings (a landmark on a country road), and roads
and surface rail with their names. Sidewalks and crossings are filtered out - pavement
this app already draws as the road beside it, not a trail of their own. Urban tint and
finer landuse distinctions are left out — this is a place to read a route against, not a
general-purpose map.

## Design decisions worth knowing

- **Speed is differentiated over a ~10 s window, not per sample.** A 1 Hz consumer GPS has
  metres of horizontal noise; a per-hop derivative turns that into a spurious "max speed".
- **Ascent uses a 3 m hysteresis threshold on smoothed elevation**, or a track that sat
  still for an hour accumulates hundreds of metres of phantom climbing.
- **Segment boundaries are respected everywhere.** A `<trkseg>` break means lost signal, so
  distance doesn't accumulate across it, the speed window doesn't span it, and neither the
  chart nor the route draws through it.
- **Average speed is over moving time**, matching what watches report.
- **The two chart colours are fixed**, validated for lightness, chroma and CVD separation;
  a wallpaper-derived palette carries no such guarantee, so chrome follows Material You and
  data does not.
- **Long tracks are reduced per pixel column, keeping the extremes.** Every-nth subsampling
  would delete the peaks, which on a speed chart are the point of looking.
- **A tap has a reach of 40 dp and measures to the drawn line, not to route vertices** —
  decimation can leave a hundred pixels of line between two kept points, and only what's
  drawn can be hit.
- **The map follows the scrubber, minimally.** Dragging a chart pans the camera just enough
  to keep the marker inside the part of the canvas the sheet doesn't cover, and does
  nothing when it's already there — re-centring every frame would turn reading a chart into
  a ride through a moving map.
- **Everything below the headline collapses by default** — date, moving time, ascent,
  descent, point count, chart axis — because they're answers you go looking for, not ones
  worth the room above a graph you came to read.

## Tests

`./gradlew test` — JVM unit tests over the parser, the analyzer, the fix filter, the speed
window and the formatters. No device needed. `GpxParser` takes its `XmlPullParser` as a
constructor parameter so it can be tested with kxml2 on a plain JVM; `android.jar`'s xmlpull
classes are stubs in unit tests.

## Known gaps

- GPX only. No FIT, TCX, KML.
- `<extensions>` are skipped, so heart rate, cadence and power are not read.
- No stats over time. The schema is a table of summaries, so "distance this month" is one
  query away, but nothing asks it yet.
- No comparison view. Overlaying routes on the map is as far as it goes; the charts show
  one track at a time.
- **The charts are not readable without a pointer.** `ProfileChart` carries only a
  `contentDescription`, so TalkBack announces "Speed" and no values.

## Why this exists

**A cycling and hiking app that behaves like an Android app and cannot talk to the
network.** Concretely:

- **No onboarding, no account, no login prompt** — optional or otherwise. First launch is
  an empty map and a button.
- **Native UI.** Not a web view, not a desktop toolkit wearing an Android skin.
- **Never intercept the back gesture.** Back at the root exits, with no confirmation
  dialog, ever. Predictive back is on.
- **No network, and the fewest permissions the features allow.**
- **Charts worth reading.** A synchronised speed and elevation scrubber is the feature,
  not a checkbox.

This is a track recorder and viewer with an offline basemap under it, not a maps app —
routing, tile fetching, search and turn-by-turn are out of scope on purpose. Anything that
makes it feel like a port of something else, or like it's chasing feature parity with a
mapping app, is a bug.
