# World Explorer — notes for Claude

Android app (Kotlin, Views + ViewBinding, no Compose) that fogs an OSM map and
clears circles where you've been. Single `:app` module, package
`co.shaypunter.worldexplorer`.

## Stack

- AGP 8.5, Gradle 8.9, Kotlin 2.0, Java 17 target; minSdk 24 / targetSdk 34
- osmdroid (OSM raster tiles), Play Services fused location, Room (KSP)
- Foreground service `location/LocationTrackingService` does all recording;
  `MainActivity` toggles it between foreground (1 s) and background (30 s) cadence

## Commands

Gradle 8.9 fails on JDK 25 (the Android Studio JBR on this machine), so point
`JAVA_HOME` at JDK 21 first:

```sh
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.11"
./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease
./gradlew pixel6Api34DebugAndroidTest -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
```

## Testing

- Instrumented tests in `app/src/androidTest` (Espresso + in-memory Room).
  `MainActivityE2ETest` grants location, launches, feeds a mock fused-location
  fix and checks it lands in the DB, then toggles tracking.
- Tests call `assumeEmulator()` and wipe the DB/prefs. **Never run
  `connectedAndroidTest` against Shay's phone**: AGP uninstalls the app after
  the run, deleting his explored area. Locally use the Gradle managed device
  (`pixel6Api34…`); CI uses `reactivecircus/android-emulator-runner`.
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
  uninstall (data loss): needs Shay's OK.
- Asset name: `WorldExplorer-vX.Y.Z.apk` (registry pattern `\.apk$`).

## Quirks

- `stats_initial` must stay `formatted="false"` (literal `%` trips lint).
- Tracking auto-starts on launch unless the user stopped it (`pausedByUser`).
  Stop paths: `LocationTrackingService.stop()` (in-app, writes prefs
  synchronously) and `ACTION_STOP_FROM_NOTIFICATION` (the service writes them).
  Don't make the service write prefs on plain `ACTION_STOP`: a quick
  stop-then-start would undo the start.
- `smoothTrail()` runs on every app open and rewrites stored points.
- Room DB is version 1 with `exportSchema = false` and no migrations: any
  entity change needs a migration or the app crashes on upgrade.
