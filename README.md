# Offline GPX

A GPX recorder and viewer for Android that never goes online. Record a ride or a walk, or
import a `.gpx` file, and see it on an offline map with speed and elevation charts.

## Features

- **Record** rides and walks with GPS: pause, drop waypoints with a note, and pick up a
  recording that was cut short by a crash or a flat battery.
- **Import** `.gpx` files, and export, share, rename or delete them, with undo.
- **Map** your tracks over an offline vector map you import, in light or dark.
- **Charts** of speed and elevation, by distance or time. Drag along one and the point
  moves on the map too.
- Metric or imperial units.

## Privacy

The app has no internet permission, so it can't go online at all, and the build fails if a
dependency ever tries to add one. There's no account, no analytics and no cloud backup.

| Permission | Why |
|---|---|
| Precise location, foreground service | Recording. Asked for when you first tap Record, never at launch. |
| Notifications | The recording notification. Recording still works without it. |

Tracks and maps are copied into the app's own storage when you import them, so no storage
permission is needed. A device-to-device transfer brings your library, maps and settings to
a new phone.

## Getting a map

Maps are [mapsforge](https://github.com/mapsforge/mapsforge) `.map` files. In the app, go
to **Settings → Get maps** to open the published files at `download.mapsforge.org`, download
the region you want, then **Import map**. Without one, tracks are drawn on a plain background.

## Building

Needs the Android SDK (`sdk.dir` in `local.properties`, or `ANDROID_HOME`). Gradle fetches
the JDK it needs.

```sh
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew test                   # unit tests, no device needed
```

Android 12 or later.

## Known limitations

- GPX only: no FIT, TCX or KML.
- Heart rate, cadence and power in GPX extensions aren't read.
- The charts can't be read with TalkBack yet.
- It's a track recorder and viewer, not a maps app: no routing, search or turn-by-turn.

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
