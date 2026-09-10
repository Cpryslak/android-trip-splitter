import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * Signing. Every APK must carry the same signature or Android refuses to install
 * one over another, and the only way past that is to uninstall — which deletes
 * every trip. So the CI build signs with a key kept in the repository's
 * secrets (see README, "Signing"). Locally, with no key set up, the ordinary
 * Android Studio debug key is used as usual.
 */
val sharedKeystore: File? = System.getenv("TRIPSPLIT_KEYSTORE_PATH")
    ?.let { file(it) }
    ?.takeIf { it.exists() }

/* GitHub gives every run a number; use it so each APK is distinguishable. */
val ciRunNumber: Int? = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

android {
    namespace = "com.tripsplit.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tripsplit.app"
        minSdk = 26
        targetSdk = 35
        versionCode = ciRunNumber ?: 2
        versionName = "1.1" + (ciRunNumber?.let { " (build $it)" } ?: "")
    }

    signingConfigs {
        if (sharedKeystore != null) {
            create("shared") {
                storeFile = sharedKeystore
                storePassword = System.getenv("TRIPSPLIT_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("TRIPSPLIT_KEY_ALIAS") ?: "tripsplit"
                keyPassword = System.getenv("TRIPSPLIT_KEY_PASSWORD")
                    ?: System.getenv("TRIPSPLIT_KEYSTORE_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("shared")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("shared")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // The real org.json, so the file format can be tested on the JVM.
    testImplementation("org.json:json:20240303")
}
