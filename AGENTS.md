This is a GPX viewer and recorder for Android written in Kotlin and Compose with no INTERNET permission. Vector based `.map` files are imported manually by the user. Keep the app lean in terms of libraries and permissions. Keep code comments concise. When an emulator is available specify it every time to avoid accidentally touching physical devices. Don't worry about cleaning up its state. Resetting the emulator's app data is also fine.

## Layout

```
core/        Pure Kotlin, no Android imports: model (Track, TrackPoint), analysis
             (FixFilter, SpeedWindow, TrackAnalyzer -> TrackProfile).
data/        gpx (streaming parser/writer), db (Room), map (MapStore, .map headers),
             record (LocationSource, RecordingWal, RecordingService/Controller/Recovery),
             settings, track (TrackRepository).
ui/          map (MapScreen, VTM canvas, layers, generated render theme), track (sheet),
             chart (hand-rolled Canvas charts), library, record, settings, format, theme, nav.
di/          AppContainer: manual wiring, no Hilt.
```

## Constraints

- `CheckNoNetworkPermissions` in `app/build.gradle.kts` fails the build if INTERNET,
  ACCESS_NETWORK_STATE or ACCESS_WIFI_STATE reaches the merged manifest. Don't silence it
  with `tools:node="remove"`; a dependency that wants the network has to be decided on.
  Any new permission must map to a feature a user can name.
- Location is platform `LocationManager` on `GPS_PROVIDER`. Not the fused provider: that
  needs Play services.
- VTM is on JitPack only; `settings.gradle.kts` lets JitPack serve that group and nothing
  else. Its SVG decoder is excluded since the theme draws no symbols.
- A track is a `.gpx` file in app-private storage; Room holds one summary row per track and
  no geometry. Rows store paths relative to `filesDir` so a device transfer still resolves.
  A recording appends to a line-per-fix WAL and becomes GPX on stop, so a crash leaves a
  recoverable log rather than truncated XML.
- The render theme is generated at runtime in the mapsforge theme dialect, from the app's
  colours; it expects the tags of the v5 files at download.mapsforge.org. VTM widens lines
  1.4x per zoom above z12 and the theme divides that back out.
- Data colours (charts, the six route colours, the recording red) are fixed, checked for
  contrast against the map and separation under simulated colour blindness. Chrome follows
  Material You.
- Analysis invariants: a `<trkseg>` break is lost signal, so distance, the speed window and
  drawn lines never span it. Speed is differentiated over ~10 s, ascent uses a 3 m
  hysteresis on smoothed elevation, average speed is over moving time, and long series are
  reduced per pixel column keeping extremes.
- Scope: a track recorder and viewer over an offline basemap, not a maps app. No routing,
  tile fetching, search or turn-by-turn. Never intercept back at the root.

## Tests

`./gradlew test` runs JVM unit tests. `GpxParser` takes its `XmlPullParser` as a parameter
so tests can use kxml2; android.jar's xmlpull classes are stubs.
