# Offline GPX

An Android app for cycling and hiking stats. Record a ride or a walk, or import a `.gpx`
file, and get a **route** over an offline map, a **speed timeline** and an **elevation
profile**, all on one shared, synchronised scrubber.

## Build and sign

Needs the Android SDK (`sdk.dir` in `local.properties`, or `ANDROID_HOME`). Gradle fetches
the JDK it runs on (`gradle/gradle-daemon-jvm.properties`).

```sh
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk, debug-signed
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release-unsigned.apk
```

The release build has no signing config; sign it with your own keystore, kept out of the
repo (`*.jks` is ignored):

```sh
"$ANDROID_HOME"/build-tools/<version>/apksigner sign --ks release.jks \
    --out app-release.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

## The permission budget

The goal is **no `INTERNET`, permanently, and as few other permissions as possible** while
still being a real tool for cycling and hiking.

| Permission | Status | Why |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | **never** | the build fails if one reaches the merged manifest — basemaps come from files the user supplies, not from this app fetching anything |
| storage | never | tracks and offline maps alike are copied from a one-shot SAF pick into app-private storage; no persisted grant, no storage permission |
| camera, microphone, Bluetooth, contacts | never | no feature needs them |
| `ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` | **declared** | the irreducible cost of being a tracker; asked for on the tap that starts a recording, never at launch |
| `POST_NOTIFICATIONS` | declared | so the recording notification is seen. Refusing it does not stop recording |
| `ACCESS_COARSE_LOCATION` | forced, never used | Android 12+ ignores a fine request without a paired coarse one |

`INTERNET` can't be requested lazily — once declared it's permanent — so it's kept out
structurally rather than by vigilance. The manifest merger folds in every dependency's
permissions, so `CheckNoNetworkPermissions` in `app/build.gradle.kts` fails the build if one
appears in the merged manifest — wired as a manifest transform, so it can't be skipped by
any build that produces an APK. Deliberately not a silent `tools:node="remove"`: a new
dependency asking for the network should force a decision. Verify what shipped:

```sh
./gradlew :app:processReleaseMainManifest
grep uses-permission app/build/intermediates/merged_manifest/release/AndroidManifest.xml
```

A permission added here must map to a feature a user can name.

Nothing is backed up to the cloud. A device-to-device transfer carries the library, the
imported maps and settings to a new phone; an in-progress recording's log stays behind.

## Stack

Kotlin and Jetpack Compose with Material 3; minSdk 29 (Android 10), targetSdk 37. Versions
live in `gradle/libs.versions.toml`.

Dependencies: AndroidX, Compose, Room, navigation-compose (with kotlinx.serialization for
its routes), and VTM, mapsforge's OpenGL renderer. The permission budget is the governing
constraint: a dependency earns its place by beating the hand-rolled code it replaces, and
is disqualified by pulling `INTERNET` into the merged manifest. VTM ships as plain jars with
no manifest, its one native library is a ~45 KB tessellator, and its SVG decoder is
excluded since the render theme draws no symbols: the release APK is **3.6 MB**. VTM
releases after 0.25.0 are on JitPack only; `settings.gradle.kts` lets JitPack serve that one
group and nothing else.

Basemaps are mapsforge `.map` files, which store three base zooms (5/10/14) and render the
rest by scaling. Settings links to the files published at `download.mapsforge.org`, whose
tags the generated render theme is written for.

Still hand-rolled: the charts (no library gives a shared-domain scrubber or an
extreme-preserving per-column reduction), `GpxParser`/`GpxWriter` (streaming and tolerant,
where a full-object-model library would not be), and `AppContainer` (manual DI; the graph
is a handful of objects).

## Architecture

```
core/            Pure Kotlin. No Android imports, directly unit-testable.
  model/         Track, TrackSegment, TrackPoint - a faithful view of the file.
  analysis/      FixFilter, SpeedWindow, TrackAnalyzer -> TrackProfile.
data/
  gpx/           GpxParser + GpxWriter: streaming, tolerant of real-world GPX.
  db/            Room: one `tracks` row per track, summary only, no geometry.
  map/           MapStore + MapFileHeader: offline basemap files.
  record/        LocationSource, RecordingWal, RecordingService, RecordingController.
  settings/      SettingsRepository: units, recording thresholds, shown maps.
  track/         TrackRepository: Room rows plus the app-private GPX files they index.
ui/
  chart/         ChartMath, ProfileChart - the Canvas charts.
  map/           MapScreen, OfflineMapCanvas (VTM) and its camera, layers and theme.
  track/         TrackSheet, TrackMenu, TrackDialogs, TrackSummary - a tapped route's sheet.
  library/       Manage: import, export, rename, show/hide, batch delete.
  record/        RecordingBar, shown by the map. No screen of its own.
  settings/      Units, accuracy limit, minimum movement, offline maps.
  format/        Formatters: units, axes and times.
  theme/         Material scheme, route palette, recording and chart colours.
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

An import is copied in rather than read where the user left it, so a recording and an
import are the same kind of thing to the rest of the app — deleting the row deletes the
file, renaming rewrites the file, and export is a byte copy either way. Rows store paths
relative to the files directory, so a device transfer that restores elsewhere still works.

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

Pause stops the receiver rather than discarding fixes, which would cost battery for
nothing. A recording under ten metres isn't saved;
crash recovery applies the same floor, so a leftover WAL can't resurrect what pressing Stop
would have thrown away.

There's no approximate-location mode: granted coarse-only, recording says it needs precise
location and doesn't start, because a route or a speed computed from hundred-metre accuracy
is a wrong number presented as a real one.

### Offline maps

VTM renders a basemap on the GPU from a `.map` file the user supplies through the file
picker — the app fetches nothing and ships no maps of its own. A file is copied into
app-private storage at import (its header read first; deleted again if it isn't a map file).
Tiles are vector geometry held in memory, never written to disk, so zooming stays sharp
between zoom levels.

A `.map` file's coverage is a rectangle and the header states it exactly, so it is taken at
its word. Two files over the same ground are two renderings of one place stacked, and no
z-order makes that legible, so importing a map that overlaps one already shown deletes the
older one — newer supersedes older automatically, rather than refusing the import and making
the user delete the old one by hand. Attribution, if the file's own comment or created-by
field carries one, is listed on the settings screen; the app has no source of its own to
credit.

The render theme is generated at runtime rather than shipped as an asset, so it can take its
colours from the theme the user is in. It is written in the mapsforge theme dialect, which
VTM reads as well as its own: rules filter on raw OSM tags, and a width that varies with
zoom is written out as one nested rule per zoom level, interpolated between stops. VTM widens lines by 1.4 per zoom above z12 on its own; the
theme divides that back out. Label collisions are
settled by explicit `priority`, trail names highest.

A file's low zooms are whole tiles tens of kilometres wide, so it carries lakes, roads and
towns well past its own box, and VTM draws all of it. Each file's data is clipped to its
box as it's decoded, so no name is placed out there, and a mask in the background colour
covers what still overhangs the edge.

The camera is the map view's: pinch, fling, and a pan clamp to the fit of every track and
shown map, kept north-up with rotation and tilt turned off. A track opened from the list is
framed above the sheet, and the clamp admits that view even for a track at the edge of the
collection; a tap on the map never moves the camera. A scale bar reads the camera's live
scale and is drawn over it. The map's own
composition doesn't survive navigating away to the library or settings and back -
Compose Navigation only keeps the current destination composed - so the camera's last
position is remembered in the ViewModel (which does survive) and restored directly on
return, rather than re-fitting to the tracks or the map from nothing every time.

The basemap style is deliberately plain: earth, one green for anything vegetated, water
(always blue, regardless of theme), buildings (a landmark on a country road), and roads
and surface rail with their names. Urban tint and finer landuse distinctions are left out —
this is a place to read a route against, not a general-purpose map.

Sidewalks aren't filtered out, though they're pavement this app already draws as the road
beside them: the tag configuration the published `.map` files are written with doesn't
record `footway=sidewalk`, so a sidewalk reaches the renderer as an ordinary
`highway=footway`. In a town this draws a second dashed line beside every street. Fixing it
needs source files written with a custom tag configuration.

## Design decisions worth knowing

- **Speed is differentiated over a ~10 s window, not per sample.** A 1 Hz consumer GPS has
  metres of horizontal noise; a per-hop derivative turns that into a spurious "max speed".
- **Ascent uses a 3 m hysteresis threshold on smoothed elevation**, or a track that sat
  still for an hour accumulates hundreds of metres of phantom climbing.
- **Segment boundaries are respected everywhere.** A `<trkseg>` break means lost signal, so
  distance doesn't accumulate across it, the speed window doesn't span it, and neither the
  chart nor the route draws through it.
- **Average speed is over moving time**, matching what watches report.
- **Data colours are fixed; chrome follows Material You.** The chart colours and the six
  route colours are checked for contrast against the map and for separation under
  simulated colour blindness, and the recording has its own red. A wallpaper-derived
  palette carries no such guarantee.
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

`./gradlew test` — JVM unit tests over GPX parsing and renaming, the analyzer, the fix
filter, speed window and recording session, the `.map` header, the render theme, map
extents, chart maths, formatters and palette slots. No device needed. `GpxParser` takes its `XmlPullParser` as a
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

## License

Copyright (C) 2026 Samq64

This program is free software: you can redistribute it and/or modify it under the terms of
the GNU General Public License as published by the Free Software Foundation, either version 3
of the License, or (at your option) any later version. It is distributed in the hope that it
will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See [LICENSE](LICENSE) for the full text.

Dependencies keep their own licences: AndroidX (Compose, Room, navigation included), the
Kotlin libraries and their other transitive dependencies are Apache 2.0; VTM is LGPL 3.0;
JTS (via `vtm-jts`) is EPL 2.0 or EDL 1.0. All are compatible with the GPL.

`app/src/test/resources/andorra-fragment.map` is cut from a mapsforge extract of
OpenStreetMap data, © OpenStreetMap contributors, available under the
[Open Database License](https://opendatacommons.org/licenses/odbl/).
