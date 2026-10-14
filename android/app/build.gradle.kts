import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * Release signing (the Play upload key) lives OUTSIDE git, in android/config.local.properties
 * (gitignored); the keystore itself in ~/.config/catlabgrilling/. Without that file a release
 * build falls back to the debug key, which Play refuses — on purpose, it cannot ship by accident.
 */
val localProps = Properties().apply {
    rootProject.file("config.local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val hasReleaseKey = localProps.getProperty("releaseStoreFile")?.let { file(it).isFile } == true

android {
    namespace = "co.laris.easydens"
    compileSdk = 36

    defaultConfig {
        applicationId = "co.laris.easydens"
        minSdk = 26
        targetSdk = 36
        // Same CalVer as the iOS app (26.10.14). versionCode = yymmdd * 100 + build of the day.
        versionName = "1.0"
        versionCode = 1
    }

    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = file(localProps.getProperty("releaseStoreFile"))
            storePassword = localProps.getProperty("releaseStorePassword")
            keyAlias = localProps.getProperty("releaseKeyAlias")
            keyPassword = localProps.getProperty("releaseKeyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests; the real one parses the demo file.
    testImplementation("org.json:json:20240303")
}
