plugins {
    // AGP 9 ships built-in Kotlin support, so no separate kotlin-android plugin
    // (same toolchain as the fleet's Maeon Brews app, proven on Play 2026-10-08).
    id("com.android.application") version "9.4.0" apply false
    // The Compose compiler plugin must match AGP's built-in Kotlin version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
