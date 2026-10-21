plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

// Exported schemas are committed: MigrationTest builds old versions from them.
room {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "co.shaypunter.worldexplorer"
    compileSdk = 37

    defaultConfig {
        applicationId = "co.shaypunter.worldexplorer"
        minSdk = 24
        targetSdk = 37
        versionCode = 5
        versionName = "0.3.0"

        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Use a checked-in debug keystore so every build (local, CI, anywhere)
        // signs with the same key. Without this, each CI run generates a fresh
        // keystore in ANDROID_USER_HOME and Android rejects updates with
        // INSTALL_FAILED_UPDATE_INCOMPATIBLE. Release APKs on GitHub are signed
        // with it too, so they install over existing debug builds without
        // wiping the explored-points database. The key is public; see README.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        // `./gradlew pixel6Api37DebugAndroidTest` boots a throwaway emulator,
        // runs the instrumented tests and tears it down. Never run
        // connectedAndroidTest against a real phone: AGP uninstalls the app
        // afterwards, which deletes its explored-points database.
        managedDevices {
            localDevices {
                create("pixel6Api37") {
                    device = "Pixel 6"
                    apiLevel = 37
                    systemImageSource = "google"
                    // AGP can't tell the page size of the 37.0 image and warns on every build;
                    // the plain google_apis image (the one CI boots) is 4 KB aligned.
                    pageAlignment = com.android.build.api.dsl.ManagedVirtualDevice.PageAlignment.FORCE_4KB_PAGES
                }
            }
        }
    }

    sourceSets {
        // MigrationTestHelper reads the exported schemas from the test APK assets.
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")

    // OpenStreetMap (free, self-hosted-friendly, no API key)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Battery-efficient fused location
    implementation("com.google.android.gms:play-services-location:21.4.0")

    // Local persistence
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    constraints {
        // room-testing needs kotlinx-serialization 1.8+, but lifecycle brings 1.7.3 and AGP
        // pins the test APK to the app's versions: MigrationTest then dies with an
        // AbstractMethodError. Lift the app's copy to match.
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
    }

    // JVM unit tests
    testImplementation("junit:junit:4.13.2")

    // Instrumented E2E tests (run on an emulator; see CLAUDE.md)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.room:room-testing:2.8.5")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
