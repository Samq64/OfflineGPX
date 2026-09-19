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

**Size.** Release is **2.9 MB**; the 33 MB figure is the debug build, which is 32.5 MB of
unminified dex — R8 is what closes the gap and only release enables it. The only native
code in the app is `libandroidx.graphics.path.so`, pulled in transitively by compose-ui: 37
KB total across all four ABIs, so shipping x86, x86_64 and 32-bit ARM costs 27 KB of a 2.9
MB artifact. Splitting them is not worth a line of build config, and a Play App Bundle
strips them per device anyway.

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
  settings/      SettingsRepository: units and the three recording thresholds.
  track/         TrackRepository (interface) + GpxTrackRepository (SAF + Room).
ui/
  chart/         ChartSeries, scales, ProfileChart - the Canvas charts.
  map/           The app. Overlaid routes, the camera, the open track's sheet, recording.
  track/         RouteCanvas + MapCamera, and the sheet the map shows a track in.
  library/       Manage: import, export, rename, show/hide, batch delete.
  record/        RecordViewModel + RecordingBar, shown by the map. No screen of its own.
  settings/      Units, accuracy limit, minimum movement, update interval.
  nav/           @Serializable routes for navigation-compose. There are three.
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

**A recording is named, not stamped.** `2026-09-19T110233.gpx` is a filename — it sorts,
and that is all it does for someone scanning a list. The default name is the time of day
and the activity, *Sunday morning ride*, because that is what people actually reach for;
the row underneath already carries the date, the distance and the duration, so the headline
repeats none of them. Walk or ride is inferred from average moving speed, which is safe
because hiking is 3–6 km/h and cycling 15–30 — and a wrong guess costs one rename. The name
is written inside the GPX before the file is closed, so it survives an export.

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

**A fix is not a position until something says so.** `FixFilter` sits between the two, and
without it a phone on a table records a ride: a stationary consumer GPS does not report the
same coordinate twice, it wanders inside its error circle, and 1 Hz of that wander is
several km/h and hundreds of metres an hour that never happened. Two rules — a fix whose
own accuracy is worse than 25m is not a position at all, and a fix that has not moved
further than the error circle it arrived with has not been shown to have moved. Smoothing
afterwards cannot fix this; by then the distance has already been accumulated from noise.

**A reading that did not move is still a reading.** Rather than drop it, the filter hands
back the last known position carrying the new timestamp, one per ten seconds. The device is
recorded as having stayed put, which is precisely what was measured — a repeated coordinate
is a claim the data supports, unlike a line interpolated across a hole. This is what every
mainstream tracker effectively does by logging stationary samples continuously, and it is
why their speed charts sit at zero through a stop instead of breaking.

It decides what a stop looks like everywhere downstream. Dropped, the readings leave a
silence that has to be *inferred* back into a stop by `TrackAnalyzer` and then drawn as a
hole. Kept, the position simply stops changing while time carries on: the speed line walks
down to zero, sits there, and walks back up, with nothing invented and no gap to explain.
An hour's coffee stop costs 360 points rather than 3,600 or none.

Gap detection still earns its place — for *imported* files, which mostly do drop their
stationary samples, and for this app's own recordings when the signal is too poor to
conclude anything at all. There the silence is real and a hole is the honest drawing.

Rejecting is not ignoring. Time, speed and the moving clock advance on every reading,
believed or not — which is what stops a filtered track from claiming its pauses never
happened, and is why `SpeedWindow` is fed on every fix rather than every recorded point:
when you stop, distance stops growing while time does not, so the number decays to zero
instead of freezing at whatever you were doing when the last point was committed. That
window is `TrackAnalyzer.SPEED_WINDOW_SECONDS` wide, so the live speed and the chart drawn
from the same ride a minute later cannot disagree. The recorder used to divide the last hop
by the last interval, which is precisely the naive derivative the analyzer exists not to
use.

The visible consequence is that recording indoors looks like waiting for a fix, because it
is. The bar distinguishes a cold start from a signal that will never be good enough, since
from the outside those look identical and mean very different things.

**Both thresholds are settings, because both are judgements rather than measurements.** The
defaults describe a phone in a jersey pocket; a better antenna, or somewhere with no sky,
wants different ones, and there is no number here that is right for everyone. They are read
when a recording *starts* and fixed for its duration — a threshold that moved mid-ride would
make the first half and the second half of one track mean different things. The settings
screen says what moving each one costs, because a number you can change without knowing
what it does is a number you will change once and never understand again.

Two knobs are deliberately absent. **Sampling rate**: the hardware fixes at 1 Hz and
anything slower is this app throwing some of that away, which coarsens the route by exactly
what it saves — the two ends of that slider are "worse route" and "worse battery".
**Pause-detection threshold**: unlike the two above it describes a *file* rather than this
device, and because it decides where a track is cut it also decides that track's distance
and moving time — every stored summary in the library would have to be recomputed against
it on every change, so a list and a sheet could never be trusted to agree mid-sweep. The
adaptive `10 × median` rule handles the variation it would have been reaching for.

**Pause survives, but only because it now stops the receiver.** Auto-detection made its old
job redundant — the recorder's own stops leave a silence the analyser splits on without
being told — and it used to do nothing else: the GPS stayed on at 1 Hz and every fix was
discarded, losing the data *and* the battery. Stopping sampling is the thing inference
cannot do. The explicit `<trkseg>` it writes is the other: a segment boundary travels with
the file to whatever reads it next, where a rule about medians does not.

**A long silence in a file is a break, not a straight line at riding pace.** Almost no
file records a dismounted stop as a `<trkseg>` boundary — auto-pause, smart recording and a
rider who simply stopped all produce one long interval between two ordinary-looking points.
Taken at face value the speed chart then draws a line from the speed going in to the speed
coming out, so a ten-minute coffee stop reads as ten minutes of riding and never touches
zero; moving time counts every second of it; and the route is drawn as if the rider took
the straight line. `TrackAnalyzer` splits there instead, and every consumer of
`segmentStartIndices` already knows what that means, because signal loss always has.

The threshold is `max(setting, 10 × the median interval)`, not a fixed number of seconds:
"unusually long" means something different for a 1 Hz recording and a route exported with
one point per kilometre, and the median is used rather than the mean precisely because the
gaps being looked for would drag a mean up with them. The floor is the one setting here
that describes a *file* rather than this device's hardware, and because it decides where a
track is cut it also decides that track's distance and moving time — so moving it
re-indexes the library. A summary in the list computed under one rule beside a sheet
computed under another is two different answers to the same question.

**A break is drawn as a break, and labelled.** Not interpolated down to zero and back:
that would invent two decelerations and put timings on them nobody measured, which is the
same objection that makes a partially timed file count as untimed. But an unexplained hole
reads as a rendering fault rather than as a fact about the ride, so the chart washes the
gap and writes *Paused 10:24* across it where there is room. **The x axis defaults to
distance** for the same reason: on a time axis a stop is a hole as wide as the stop was,
which on a ride with a long lunch is most of the chart, while on a distance axis it takes
no width at all — correctly, since no distance passed during it. Time is one tap away for
when the stops are the thing you came to look at.

**A recording that went nowhere is not saved.** Under ten metres there is no route, no
speed and no profile — a library row would be a name and three empty charts. Ten rather
than zero because a handful of fixes that happened to clear the displacement floor is the
same nothing as none at all. The crash-recovery path applies the same bar: a crash must not
resurrect what pressing Stop would have thrown away, since the leftover log is the only
difference between the two and it is not a difference the user made.

A `location`-typed foreground service owns the recording, with a notification showing
distance and elapsed time. It is also the only component that needs injecting, which is
the argument for Hilt — deferred until the service exists.

**There is no approximate-location mode.** Android 12+ ignores a fine-location request
that does not also ask for coarse, so `ACCESS_COARSE_LOCATION` appears in the manifest
whether or not it is wanted. That is a platform pairing, not a capability. Approximate
location is accurate to hundreds of metres at best; a route drawn from it is noise and a
distance or speed computed from it is a wrong number presented as a real one. Granted
approximate only, recording says it needs precise location and does not start.

**Recording is not a destination.** It is a bar docked under the map, and the route being
recorded is drawn on that map among the saved ones. A recorder on its own screen makes you
leave the one surface that can show what you are recording, and there was nothing on that
screen the map could not carry. There is no page of reassurance before the permission
prompt either: the tap that starts a recording asks the system for location and nothing
else, and every way it can fail — permission refused, approximate granted, location off
system-wide, location switched off mid-ride — is answered by a snackbar where the tap
happened. Location being off used to be the silent one: `requestLocationUpdates` succeeds
and then simply never calls back, which is indistinguishable from waiting for a fix.

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

**A track is a selection on the map, not a place you go.** There is no track screen; there
is one sheet over the map, and tapping a route opens it. A second screen meant redrawing
the same route on a second canvas, losing every other track off the side of it, and
carrying a back stack entry for what is really a selection. Swiping the sheet away, tapping
bare map, or pressing back all deselect — the sheet and the selection are one fact, so
there is no state where the numbers describe something that is not on screen. It carries
the track's name and its palette swatch, because the top app bar that used to name it
belongs to the map now.

The sheet has three heights. It peeks at the name plus one row of numbers — enough to
answer "what is this", and not so much that it peeks at a chart heading with no chart under
it, which is the worst row it has. Expanded it takes 58% of the window, which answers "what
happened" with the route still in view beside the numbers. Full height is for reading the
charts themselves, where the map has stopped being the point; you ask for it with the
chevron beside the axis selector, because Material's sheet has only three values and one of
them is Hidden, so there is no fourth drag anchor to give it. It sits down there with the
other controls rather than in the title because that is where it becomes reachable — from
the peek there is already a drag anchor one step up, and it is only past that step that a
third height needs asking for. It is the *content's* max height that changes, moving
the Expanded anchor the sheet is already settled at — animated, so the anchor is recomputed
each frame and the result is a slide rather than a jump. Dragging down past the middle
gives the map back for good, and back steps down before it closes. The map is drawn
full-bleed underneath rather than squeezed above, which is both what a map should look like
and the only way the sheet can be gone *entirely* without leaving a strip of nothing behind.
What the sheet and the controls cover is passed to the canvas as padding, so the fit still
puts the whole route somewhere you can see it.

**Everything between the headline and the first chart folds away, collapsed by default.**
Date, moving time, ascent, descent, point count, and what the charts are plotted against:
the first five are answers to questions you go looking for and the last is a decision you
make once, so none of them is worth the room above a graph you came to read. The disclosure
sits beside the headline readings it opens more of — and drops to its own line when wide
units or a large font scale have used the width, which is why that row flows rather than
clips. It is a triangle that turns over, not a second chevron: the sheet-height control is
a chevron, and two identical glyphs opening two different things is a screen you have to
experiment with. It stays open once opened: someone who wants those numbers wants them
every time.

The height control went back to the title bar, which is the one row on screen at every
height. It had been down among the controls it belongs with conceptually, until those
controls became a thing that folds away — which is no place for the control that makes room
for what is left.

**The scrubbed values are on the chart, not in a row above it.** They used to take over the
summary row at the top of the sheet, which meant reading a value off a chart involved
looking somewhere else entirely — and put the figure for the chart you were *not* touching
next to the one you were. Beside the mark there is no question which series a number
belongs to. The position along the track is drawn once per chart in the axis band under the
crosshair, where an x label belongs; the value sits in a box by the dot, opaque because it
is over the line it describes. The summary row is now simply the whole track, always. Max
speed left it for a related reason — the speed chart marks and labels its own peak a few
hundred pixels below, and printing a figure twice on one screen is the sheet paying rent in
height for nothing.

**The scrubber runs both ways.** Dragging a chart moves the marker on the route, and
tapping the route moves the chart crosshairs, because `TrackProfile` shares its indices
with the track's lat/lon. That is what a route view buys over a picture of one.

**The route is projected equirectangular with longitude scaled by cos(latitude).** Over one
activity's extent the error against a true Mercator is below a pixel. Taking the extent in
raw degrees instead would stretch the shape a third too wide at 50N.

**The camera exists because the fit alone is useless across towns.** One shared projection
is what makes overlaying tracks mean anything, but fitted to the union of two rides fifty
miles apart it renders both as specks. Drag pans and pinch zooms. At fit zoom panning does
nothing on purpose — everything is already on screen — and the pan is clamped so the routes
can never be flung somewhere you have to guess at. Zooming is about the pinch centroid, not
the canvas centre, which is the difference between zooming into the bit under your fingers
and zooming into the middle and then hunting for it. It is two numbers, scale and offset; a
tile layer's camera is the same two, spelled centre and level.

**The camera is the reader's, and nothing else moves it.** `MapCamera.Fitted` is both the
identity and the answer to "show me everything", so a cold start has every track on screen
without anything having to aim at them. After that the only things that touch it are
fingers and the scrubber. There is no *Fit* button: pinching back out returns
`MapCamera.Fitted` exactly, the moment the zoom reaches 1, so the way back is the same
gesture that left and a button for it would duplicate a pinch. Selecting a track used to
fly the camera onto it, which meant tapping something rearranged the picture you were
already reading — the sheet tells you what you picked, and the line is already highlighted
where you tapped it.

**A tap has a reach of 40dp.** Unbounded, every tap hit *something*, which was tolerable
when the fit meant routes filled the canvas and is not now that the camera can leave most
of it empty — a tap on nothing would open whichever track happened to be closest, half a
screen away. Beyond that radius a tap means "nothing", which is what deselects.

**Hit testing measures to the drawn segments, not to their endpoints.** Decimation throws
away every vertex that would land within a pixel of the last one, so a long straight
stretch of road is two points with a hundred pixels of line between them — measuring to
vertices meant the line you can plainly see was untappable along the middle of it. What is
drawn is what can be hit. The index it resolves to is still the nearer real sample:
interpolating one would point at a moment that was never recorded.

**The map follows the scrubber.** A marker that has walked off the side of the canvas, or
behind the sheet, is the argument for putting the charts and the route on one surface
failing at the only moment it matters. The camera pans the minimum needed to bring the
scrubbed point back inside the uncovered box and does nothing when it is already there —
minimal rather than centred, because re-centring every frame turns reading a chart into a
ride through a moving map and loses the surroundings that made the marker mean anything.
It is not animated: it answers a drag happening right now, and an ease measured in hundreds
of milliseconds would arrive after the finger had moved on. It is also the one piece of
automatic camera movement left, and it earns that by being the only one the reader is
actively asking for while it happens.

What counts as "uncovered" tracks the sheet rather than assuming its peek. Opening the
sheet to read the charts hides well over half the canvas, and a route fitted to the peek
would be sitting mostly behind it by then, so the fit shrinks into the strip that is left
and grows back when the sheet is collapsed.

**There is one X in the sheet.** The scrub readout used to carry its own clear button,
directly under the sheet's close icon, leaving the reader to work out which X meant which.
A tap anywhere in the sheet that is not a chart now reverts to the whole-track numbers —
the charts and the buttons consume their own taps, so the parent only ever sees the ones
that landed on nothing, which is exactly when "never mind" is what was meant. It is also a
much bigger target than an icon.

**First-composition cost lands on entry animations.** Going *back* to a screen is cheap —
its data is in a ViewModel and its code paths are warm — while going *forward* composes a
screen from scratch over the frames of its own slide, so anything expensive there shows up
as the animation stuttering in one direction only. Three things were doing that in the
list: a `StateFlow` starting at `emptyList()`, so the first frames rendered "No tracks yet"
and then replaced the whole screen with the list (it starts at `null` now, and renders
nothing until the query returns); a summary line rebuilt with three `String.format` calls
per row per composition, one of them through a localized `DateTimeFormatter` whose first
use loads locale data; and a `DropdownMenu` composed for every row whether or not it was
open.

None of that is the whole story on a debug build, which has no baseline profile, no R8 and
no AOT — `assets/dexopt/baseline.prof` only ships in release, where `profileinstaller`
applies it at install. Compare the two before chasing a frame budget.

## Tests

`./gradlew test` — JVM unit tests over the parser, the analyzer, the fix filter, the speed
window and the formatters. No device needed.
`GpxParser` takes its `XmlPullParser` as a constructor parameter so it can be tested with
kxml2 on a plain JVM; `android.jar`'s xmlpull classes are stubs in unit tests.

## Known gaps

- GPX only. No FIT, TCX, KML.
- `<extensions>` are skipped, so heart rate, cadence and power are not read.
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
