import com.android.build.api.variant.impl.VariantOutputImpl

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.karooinsta360"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.karooinsta360"
        minSdk = 26
        targetSdk = 34
        // versionCode just needs to keep increasing by 1 each build — it doesn't need to
        // encode the versionName scheme. versionName itself is 0.1.<build number> rather
        // than 0.<build number> going forward, per request.
        versionCode = 17
        versionName = "0.1.17"
    }

    // Added (2026-09-07). Without this, every build signs with whatever auto-generated
    // debug keystore happens to exist on the machine doing the building — a different one
    // on each GitHub Actions runner, and a third on the dev Mac. Android refuses to update
    // an installed app when the signing key changes, so each new APK had to be uninstalled
    // and reinstalled, losing all saved cameras and profiles every time.
    //
    // A committed keystore fixes that: same key everywhere, so updates install over the
    // top and app data survives. It uses Android's standard debug credentials
    // (androiddebugkey / "android"), which are public by design — this is a sideload key,
    // not a secret, and must not be reused for anything published.
    signingConfigs {
        create("shared") {
            storeFile = file("shared-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Kotlin sources live directly under src/main/kotlin (not src/main/java)
    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
}

// The module is named "app" (see settings.gradle.kts), so without this the built file
// is just "app-debug.apk"/"app-release.apk" — indistinguishable from any other Android
// project's default output once it's sitting in a Downloads folder or attached to a
// GitHub release. Name it after what it actually is instead.
//
// `outputFileName` isn't exposed on the public VariantOutput interface (AGP 8.13.2) —
// only on the impl class — so this cast is required; it's the approach Android's own
// samples use for renaming APK output, not a hack around the API.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is VariantOutputImpl) {
                output.outputFileName.set(
                    "karoo-insta360-${android.defaultConfig.versionName}-${variant.name}.apk",
                )
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("com.google.android.material:material:1.12.0")

    // Karoo extension SDK — see settings.gradle.kts for the GitHub Packages
    // credentials this requires.
    implementation("io.hammerhead:karoo-ext:1.1.9")

    // For streaming heart rate/power from KarooSystemService (Flow-based wrapper
    // around its callback API) in the auto-record threshold trigger.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
