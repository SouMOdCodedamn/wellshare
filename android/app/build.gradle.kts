import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Server settings come from environment variables (GitHub Actions secrets)
// or, when building on your own computer, from android/local.properties:
//   supabase.url=https://xxxx.supabase.co
//   supabase.key=sb_publishable_...
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(env: String, prop: String): String =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: localProps.getProperty(prop, "")

val keystoreFile: String? = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "org.wellshare.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.wellshare.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"

        buildConfigField("String", "SUPABASE_URL", "\"${cfg("SUPABASE_URL", "supabase.url").trimEnd('/')}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${cfg("SUPABASE_KEY", "supabase.key")}\"")
    }

    signingConfigs {
        create("release") {
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8: shrink and obfuscate the release app (see proguard-rules.pro).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without your own key, fall back to a debug key so the APK still installs.
            signingConfig = signingConfigs.getByName(if (keystoreFile != null) "release" else "debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Keep CI builds from failing on style warnings; run `gradle lint` yourself to see them.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No third-party libraries: only the Android framework and Kotlin.
