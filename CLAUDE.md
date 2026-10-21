# World Explorer — notes for Claude

Android app (Kotlin, Views + ViewBinding, no Compose) that fogs an OSM map and
clears circles where you've been. Single `:app` module, package
`co.shaypunter.worldexplorer`.

## Stack

- AGP 9.4 (built-in Kotlin: no `kotlin-android` plugin; KGP 2.4 pinned on the
  buildscript classpath), Gradle 9.8, KSP 2.3, Java 17 target; minSdk 24 /
  targetSdk 37 / compileSdk 37
- osmdroid (OSM raster tiles), Play Services fused location, Room (KSP)
- Foreground service `location/LocationTrackingService` does all recording;
  `MainActivity` toggles it between foreground (1 s) and background (30 s) cadence

## Commands

Gradle 9.8 runs on JDK 17–25 (JDK 21 and the Android Studio JBR 25 both work):

```sh
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.11"
./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease
./gradlew pixel6Api37DebugAndroidTest -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
```

## Testing

- JVM unit tests in `app/src/test` (pure logic such as `TrailSmoother`,
  `GapFiller`, `nearbyBoxes`; no Android APIs there, they're stubs on the
  JVM). Keep maths out of Android classes so it stays testable; distances use
  `data/Geo.kt`'s `haversineMeters`, not `Location.distanceBetween`.
  `TestGeo.destination()` places a point N metres along a bearing.
- Instrumented tests in `app/src/androidTest` (Espresso + in-memory Room).
  `MainActivityE2ETest` grants location, launches, feeds a mock fused-location
  fix and checks it lands in the DB, then toggles tracking.
- Tests call `assumeEmulator()` and wipe the DB/prefs. **Never run
  `connectedAndroidTest` against Shay's phone**: AGP uninstalls the app after
  the run, deleting his explored area. Locally use the Gradle managed device
  (`pixel6Api37…`); CI uses `reactivecircus/android-emulator-runner`.
- `scripts/smoke-apk.sh <apk>` installs and launches a packaged APK on a
  running emulator and fails on crash or if the tracking service didn't start.
  CI runs it against the minified release APK, so R8 breakage is caught.
- Mock locations need `appops set <pkg> android:mock_location allow` and a
  `google_apis` image (fused location needs Play services).

## Releasing

- Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`; the
  release workflow fails if the tag doesn't match `versionName`.
- Release APKs are signed with the committed debug keystore on purpose, so they
  update over Shay's existing install. Changing the signing key forces an
  uninstall (data loss): needs Shay's OK. Shay confirmed on 2026-10-14 (card
  #2429) that the public debug key is fine for this personal app: don't raise it again.
- Asset name: `WorldExplorer-vX.Y.Z.apk` (registry pattern `\.apk$`).

## Quirks

- Edge-to-edge (enforced from targetSdk 35): the map draws under the system
  bars and `MainActivity.keepControlsClearOfSystemBars()` adds the bar and
  cutout insets to each chip/FAB's layout margins. A new control anchored to a
  screen edge must be added there, or it ends up under the status or
  navigation bar. Bar colours come from `enableEdgeToEdge()`, not the theme.
- `stats_initial` must stay `formatted="false"` (literal `%` trips lint).
- Tracking auto-starts on launch unless the user stopped it (`pausedByUser`).
  Stop paths: `LocationTrackingService.stop()` (in-app, writes prefs
  synchronously) and `ACTION_STOP_FROM_NOTIFICATION` (the service writes them).
  Don't make the service write prefs on plain `ACTION_STOP`: a quick
  stop-then-start would undo the start.
- Boot resume (`BootReceiver`) needs background location ("Allow all the
  time") on Android 10+: Android 14 throws a SecurityException for a location
  foreground service started from the background without it (reproduced on
  API 34, card #2424). Without it the receiver skips and the app resumes
  tracking when opened. Service starts go through `startSafely` and
  `startInForeground` returns false instead of throwing; keep it that way.
  When reproducing reboot behaviour on an emulator, wait ~30 s and `adb shell
  sync` after `pm grant` before `adb reboot`, or the grants are lost.
- Trail smoothing (`data/TrailSmoother`) runs on every app open but only
  touches points after the `smoothedUpToId` watermark in prefs, and never
  across trip boundaries (> 5 min gap). Projection isn't idempotent on curves,
  so never re-smooth old points.
- First-launch prompts run one at a time (location → notifications →
  background location → battery) through `MainActivity.nextPrompt` /
  `continuePrompts()`. Android drops a permission request launched while
  another is showing, so never launch two together.
- Room schemas are exported to `app/schemas` (committed). Any entity change
  bumps the `AppDatabase` version, commits the new `schemas/<n>.json`, adds a
  migration to `data/Migrations.kt` and a case in `MigrationTest`, or the app
  crashes on upgrade. Never `fallbackToDestructiveMigration` (wipes the
  explored area). Room rewrites an existing `schemas/<n>.json` when the
  entity changes without a version bump, so a diff to an old schema file
  means a missing bump.
- Anything that adds or compares longitudes must handle ±180°: `GapFiller`
  goes the short way round (`shortestLonDelta`), and the dedup query uses
  `nearbyBoxes`, which returns two boxes across the antimeridian and a full
  longitude band when the circle reaches a pole.
- Minor-versioned platforms (Android 17 is `android-37.0`, 16 QPR is `36.1`): the
  managed device's `apiLevel = 37` resolves to the `android-37.0` google_apis
  image by itself, but `reactivecircus/android-emulator-runner` interpolates
  `api-level` as-is into `platforms;android-X` / `system-images;android-X;…`,
  so CI has to say `api-level: '37.0'`, quoted: a bare `37.0` is YAML's number 37,
  and `platforms;android-37` doesn't exist (the first CI run failed on that). The CI E2E job is
  named without the API level because it's a required check (#2711).
- Managed-device runs flake on a cold emulator: `setMockMode` can still time
  out after 60 s, and the 10,000-point % test can ANR. Rerun before debugging.
- "% explored" is the area of the *union* of the circles (`data/ExploredArea`):
  each circle is rasterised onto ~50 m equal-area cells kept in a primitive
  long hash set, and new points are added incrementally. Don't go back to
  summing π·r² per point (points sit 20–150 m apart, so that overcounted
  several-fold). The display has only 5 decimals, so E2E checks need a big
  seeded area to see a difference.
