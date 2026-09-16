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
        // versionCode just needs to keep increasing by 1 each build — it deliberately does
        // not encode the versionName scheme, which is why it carries straight on from the
        // 0.1.x series into 1.0.0. Android compares versionCode and nothing else when
        // deciding whether an APK is an upgrade, so this must never go backwards even if
        // the versionName does.
        versionCode = 66
        versionName = "1.0.0"
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
            // Left off for 1.0.0 deliberately. Enabling R8 here would be the first time
            // this app shipped shrunk and obfuscated code, and the parts most likely to
            // break under it — reflective RemoteViews method lookup by name
            // (setBackgroundResource, setColorFilter) and the karoo-ext callback surface —
            // are exactly the parts with no test coverage and the hardest failures to
            // diagnose on a head unit.
            isMinifyEnabled = false
            // The same committed keystore the debug variant uses, which is what lets a
            // release APK install over an existing debug install without wiping saved
            // cameras and profiles. Changing this key later would force every user to
            // uninstall first.
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
// The variant name is no longer part of the filename (1.0.0). Releases build the release
// variant, and "karoo-insta360-1.0.0-release.apk" reads like an internal build artefact
// rather than something to hand someone. Note this is cosmetic as far as upgrading goes:
// Android decides that on applicationId, signing key and versionCode, and ignores the
// filename entirely — so renaming cannot break an update, and a debug build of the same
// version would now overwrite the release file of the same name locally, which is the one
// thing to watch.
//
// `outputFileName` isn't exposed on the public VariantOutput interface (AGP 8.13.2) —
// only on the impl class — so this cast is required; it's the approach Android's own
// samples use for renaming APK output, not a hack around the API.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is VariantOutputImpl) {
                output.outputFileName.set(
                    "karoo-insta360-${android.defaultConfig.versionName}.apk",
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
