plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// One place decides the version. Bump the name here; the code is derived from it so the two can
// never disagree. A release is cut by pushing the tag `drive-v<this name>`, which is what the app
// looks for when it checks for updates.
val appVersionName = "0.1.0"
val appVersionCode = appVersionName.split(".").let { (major, minor, patch) ->
    major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
}

/** Whatever `local.properties` holds; it is gitignored, so it is where a machine's own secrets live. */
val localProperties: Map<String, String> = rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.readLines()
    ?.mapNotNull { line ->
        val text = line.trim()
        if (text.startsWith("#") || "=" !in text) return@mapNotNull null
        text.substringBefore("=").trim() to text.substringAfter("=").trim()
    }
    ?.toMap()
    .orEmpty()

fun buildSetting(name: String): String? =
    (localProperties[name] ?: System.getenv(name))?.takeIf(String::isNotBlank)

/**
 * The release signing key, shared by both Pocket Cloud apps.
 *
 * Android refuses to install an update signed with a different key than the install it would
 * replace, so every release has to carry the same signature. The key lives outside the repository
 * and in GitHub secrets. Absent, the release build is simply unsigned: a fresh checkout still
 * builds, and an unsigned APK cannot be mistaken for a release because nothing can install it.
 */
val releaseKeystore = buildSetting("POCKET_KEYSTORE_FILE")?.let(rootProject::file)?.takeIf { it.exists() }
val releaseKeystorePassword = buildSetting("POCKET_KEYSTORE_PASSWORD")
val releaseKeyAlias = buildSetting("POCKET_KEY_ALIAS")

android {
    namespace = "cloud.pocket.drive"
    compileSdk = 37

    defaultConfig {
        applicationId = "cloud.pocket.drive"
        minSdk = 29          // Android 10, so it also runs on the Note 9 itself
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseKeystore != null && releaseKeystorePassword != null && releaseKeyAlias != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = buildSetting("POCKET_KEY_PASSWORD") ?: releaseKeystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // A sideloaded build should be obvious in Settings without checking a commit hash.
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true       // BuildConfig.VERSION_NAME is what the update check compares against
    }
    packaging {
        resources {
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("cloud.pocket:pocket-cloud")          // ../clients/kotlin, as an included build
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.coil.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.unifiedpush)
    implementation(libs.bouncycastle)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)
}
