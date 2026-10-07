# O-Trace

Record and manage GPX tracks (traces) without the `INTERNET` permission. Supports optional [Mapsforge](https://download.mapsforge.org) compatible files.

## Features

- Record with support for waypoints, pausing and chart stats on the fly
- Import and export traces as `.gpx` files
- Speed and elevation charts with zoom support
- Switch between viewing charts by distance or time
- Trace trimming
- Sort, filter and categorize traces
- Bulk operation on traces
- Multiple maps at once and traces on unmapped areas
- 7 trace colours

## Non-features

- Anything requiring internet access
- External device integration
- Map routing or searching

## Building

Needs the Android SDK (`sdk.dir` in `local.properties`, or `ANDROID_HOME`) and any JDK 17+
to start Gradle, which then fetches the JDK 25 it builds with. Runs on Android 12 or later.

```sh
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release-unsigned.apk
```

The release build is minified but unsigned; sign it with your own key:

```sh
apksigner sign --ks release.jks --out app-release.apk \
    app/build/outputs/apk/release/app-release-unsigned.apk
```

The build fails if any dependency adds a network permission to the merged manifest
(`CheckNoNetworkPermissions` in `app/build.gradle.kts`).

## Testing

```sh
./gradlew test                  # JVM unit tests for :core and :app, no device needed
./gradlew coverageVerification  # coverage report in app/build/reports/coverage, with per-file minimums
./gradlew ktlintCheck           # style per .editorconfig; ktlintFormat fixes most of it
./gradlew check                 # all of the above, plus Android lint
```

Instrumented tests in `app/src/androidTest` need a connected device or emulator:

```sh
./gradlew :app:connectedDebugAndroidTest
```

## Source layout

```
core/        JVM Gradle module, so no Android: model (Track, TrackPoints columns,
             TrackPoint for single points), analysis (FixFilter, SpeedWindow,
             TrackAnalyzer -> TrackProfile).
app/ data/   gpx (streaming parser/writer/trimmer), db (Room), map (MapStore, .map headers, VTM
             tile source), record (LocationSource, RecordingWal, RecordingService/
             Controller/Recovery), settings, track (TrackRepository, TrackCache).
app/ ui/     map (MapScreen, VTM canvas, layers, generated render theme), track (sheet),
             chart (hand-rolled Canvas charts), library, record, settings, format, theme, nav.
app/ di/     AppContainer: manual wiring, no Hilt.
```

## License

O-Trace is licensed under the GPLv3. See [LICENSE](LICENSE) for details.

Bundled libraries are credited within the app at Settings > Libraries

`app/src/test/resources/andorra-fragment.map` is cut from a mapsforge extract of
OpenStreetMap data, © OpenStreetMap contributors, available under the
[Open Database License](https://opendatacommons.org/licenses/odbl/).
