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
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                "karoo-insta360-${android.defaultConfig.versionName}-${variant.name}.apk",
            )
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
