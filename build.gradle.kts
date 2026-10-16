buildscript {
    dependencies {
        // AGP 9 compiles Kotlin itself (built-in Kotlin) and pulls in its own
        // Kotlin Gradle plugin. This pins a newer one.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("androidx.room") version "2.8.5" apply false
}
