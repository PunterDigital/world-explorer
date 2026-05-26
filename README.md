# World Explorer

An Android app that reveals the world as you move. The map starts fully fogged;
every location fix burns away a circular patch around you, so the world unlocks
in real life as you walk, cycle, or drive through it.

## Features

- **Fog of war overlay** over an OpenStreetMap base layer. Explored areas are
  softly feathered cutouts so the edges blend rather than appearing as hard
  circles.
- **Battery-friendly tracking**: `FusedLocationProviderClient` with
  `PRIORITY_BALANCED_POWER_ACCURACY`, 30 s interval, 25 m minimum
  displacement. The provider uses cell/Wi-Fi when adequate and only falls back
  to GPS when needed.
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

```sh
./gradlew assembleDebug
```

APK lands in `app/build/outputs/apk/debug/`.

CI builds run on a self-hosted Linux GitHub Actions runner — see
`.github/workflows/build.yml`. The workflow installs the Android SDK
(platform-tools, android-34, build-tools 34.0.0) via
`android-actions/setup-android`, builds the debug APK, and uploads it as an
artifact.

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
