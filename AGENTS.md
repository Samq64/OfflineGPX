A GPX recorder and manager for Android written in Kotlin and Compose with no INTERNET permission. Vector based `.map` files are imported manually by the user. Try to avoid unnecessary app permissions and large (1MB+) libraries. Keep code comments concise. When an emulator is available specify it every time to avoid touching physical devices. The emulator's app state does not matter.

See README.md for the source layout and the build, test, coverage and style commands.

## Constraints

- `CheckNoNetworkPermissions` in `app/build.gradle.kts` fails the build if INTERNET,
  ACCESS_NETWORK_STATE or ACCESS_WIFI_STATE reaches the merged manifest. A dependency that
  declares one is dropped, or has it stripped with `tools:node="remove"` and a comment saying
  why it works offline. Stripping can't leak, since the OS then refuses sockets, but a
  library that does use the network will fail. Any new permission must map to a feature a
  user can name.
- Location is platform `LocationManager` on `GPS_PROVIDER`. Not the fused provider: that
  needs Play services. Its altitude is converted to sea level by `core-location-altitude`,
  whose geoid map is bundled.
- VTM is on JitPack only; `settings.gradle.kts` lets JitPack serve that group and nothing
  else. Its SVG decoder is excluded since the theme draws no symbols.
- A track is a `.gpx` file in app-private storage; Room holds one row per track with its
  stats and bounding box, and no geometry. Rows store paths relative to `filesDir` so a
  device transfer still resolves. Parsed points are cached in binary under `cacheDir`, which
  is regenerable and never backed up or shared. A saved track's name and colour come from
  its row only. The colour goes into a file only on its way out: export and share stream it
  through `GpxTrimmer`, writing gpx_style's RGB and Garmin's nearest name. An import takes a
  file's colour as the nearest slot by hue. Files shared from other apps join the library;
  MainActivity is singleTask so they reach the one instance.
- Trim rewrites a file with `GpxTrimmer`, which streams it through and keeps
  everything but the points cut; `GpxWriter` writes only what the app reads. The original
  waits under `noBackupFilesDir/edits` for the undo, and is purged at the next launch.
  A recording appends to a line-per-fix WAL and becomes GPX on stop, so a crash leaves a
  recoverable log rather than truncated XML.
- The render theme is generated at runtime in the mapsforge theme dialect, from the app's
  colours; it expects the tags of the v5 files at download.mapsforge.org. VTM widens lines
  1.4x per zoom above z12 and the theme divides that back out.
- Data colours (charts, the seven route colours, the recording red) are fixed, checked for
  contrast against the map and separation under simulated colour blindness. Chrome follows
  Material You.
- Analysis invariants: a `<trkseg>` break is lost signal, so distance, the speed window and
  drawn lines never span it. Speed is differentiated over ~10 s, ascent uses a 3 m
  hysteresis on smoothed elevation, average speed is over moving time, and long series are
  reduced per pixel column keeping extremes.
- Scope: a track recorder and viewer over an offline basemap, not a maps app. No routing,
  tile fetching, search or turn-by-turn. Never intercept back at the root.

## Tests

`GpxParser` takes its `XmlPullParser` as a parameter so tests can use kxml2; android.jar's
xmlpull classes are stubs.
`connectedDebugAndroidTest` runs on every attached device, so prefix it with
`ANDROID_SERIAL=<emulator>`.
