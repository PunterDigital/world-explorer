# World Explorer

An Android app that reveals the world as you move. The map starts fully fogged;
every location fix burns away a circular patch around you, so the world unlocks
in real life as you walk, cycle, or drive through it.

**Download:** grab the latest APK from
[punterdigital.github.io/personal-apps](https://punterdigital.github.io/personal-apps/)
or the [Releases page](https://github.com/PunterDigital/world-explorer/releases).
On your phone, open the APK and allow *Install unknown apps* for your browser or
file manager when Android asks. Requires Android 7.0+ with Google Play services.

## Features

- **Fog of war overlay** over an OpenStreetMap base layer. Explored areas are
  softly feathered cutouts so the edges blend rather than appearing as hard
  circles.
- **Two tracking cadences**: `FusedLocationProviderClient` at
  `PRIORITY_HIGH_ACCURACY`. While the app is on screen it polls every second
  (5 m minimum displacement) so the trail grows in real time; in the background
  it drops to 30 s / 25 m. A partial wake lock keeps fixes flowing while the
  screen is off, and long straight gaps between fixes are interpolated.
- **Low data usage**: free OSM raster tiles cached on-device (up to ~200 MB,
  auto-trimmed). Only tiles in view are fetched; once cached, areas can be
  revisited offline.
- **Persistent reveal**: explored points are stored in a local Room database,
  with geographic dedup so a stationary phone does not bloat storage.
- **Foreground service** keeps tracking surviving the activity going away,
  with a low-priority ongoing notification and a `Stop` action.

## Stack

- Kotlin, AGP 8.5, Gradle 8.9, Java 17
- minSdk 24 / targetSdk 34
- [osmdroid](https://github.com/osmdroid/osmdroid) for OSM tiles
- Google Play Services Location (FLP)
- Room for persistence

## Building

Needs JDK 17 or 21 (Gradle 8.9 can't run on newer JDKs) and the Android SDK.

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew assembleRelease      # minified, app/build/outputs/apk/release/
./gradlew lintDebug testDebugUnitTest
./gradlew pixel6Api34DebugAndroidTest   # E2E on a throwaway emulator
```

CI (`.github/workflows/ci.yml`) runs lint, unit tests and both builds on every
PR, plus the instrumented tests and an install-and-launch smoke test of the
release APK on an API 34 emulator. Pushing a `vX.Y.Z` tag runs
`release.yml`, which builds the release APK and attaches it to a GitHub Release.

### Signing

All builds, including the APKs on the Releases page, are signed with the debug
keystore checked in at `app/debug.keystore`. That keeps updates installable
over any earlier build without losing your explored area, but the key is
public: only install World Explorer APKs from this repo's Releases.

## Permissions

- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — required.
- `ACCESS_BACKGROUND_LOCATION` — declared so the foreground service can keep
  tracking while the screen is off. You'll be prompted separately for this
  if needed.
- `POST_NOTIFICATIONS` (API 33+) — required to show the tracking
  notification.
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` — for the tracking
  service.

## Tuning

Most knobs live in
`app/src/main/java/co/shaypunter/worldexplorer/location/LocationTrackingService.kt`:

- `UPDATE_INTERVAL_MS` — desired interval between location fixes.
- `MIN_DISTANCE_M` — only emit a fix once you've moved this far.
- `UNLOCK_RADIUS_METERS` — radius of each revealed circle.

The fog appearance lives in
`app/src/main/java/co/shaypunter/worldexplorer/ui/FogOverlay.kt`
(`FOG_ALPHA`, radial-gradient stops).

## Licence

MIT
