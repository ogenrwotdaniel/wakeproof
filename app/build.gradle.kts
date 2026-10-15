import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing is configured via an untracked keystore.properties in the repo root;
// when the file is absent (CI, fresh clone) the release build falls back to unsigned.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Cloud sync is configured by an untracked app/google-services.json. The plugin is
// applied only when that file is present, so a fresh clone (or CI) still builds —
// the app then detects at runtime that no Firebase app initialised and keeps sync
// switched off instead of crashing. See CloudSyncAvailability.
val firebaseConfigured = file("google-services.json").exists()
if (firebaseConfigured) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.warn(
        "WakeProof: app/google-services.json is missing — building WITHOUT cloud sync. " +
            "A Play release build must have it."
    )
}

/**
 * The OAuth *web* client id (client_type 3) that Google sign-in must request.
 *
 * Read here rather than from the `default_web_client_id` string resource the
 * google-services plugin generates. That resource can only be reached by name at
 * runtime (it does not exist in a build without the plugin, so `R` cannot refer to
 * it), and a by-name lookup is invisible to the resource shrinker — which duly
 * deleted it from the release build, breaking sign-in for Play installs only while
 * every debug build kept working. Baking the value into BuildConfig removes the
 * resource, and the whole failure mode, from the picture.
 */
val firebaseWebClientId: String = if (!firebaseConfigured) "" else {
    @Suppress("UNCHECKED_CAST")
    val json = groovy.json.JsonSlurper()
        .parse(file("google-services.json")) as Map<String, Any>
    val clients = json["client"] as List<Map<String, Any>>
    clients.asSequence()
        .flatMap { (it["oauth_client"] as? List<Map<String, Any>> ?: emptyList()).asSequence() }
        .firstOrNull { (it["client_type"] as? Number)?.toInt() == 3 }
        ?.get("client_id") as? String
        ?: throw GradleException(
            "google-services.json has no web OAuth client (client_type 3). Enable Google " +
                "sign-in in the Firebase console and re-download it — see docs/CLOUD_SYNC.md."
        )
}

android {
    namespace = "com.ogenrwotdaniel.wakeproof"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ogenrwotdaniel.wakeproof"
        minSdk = 34
        // Play requires new apps and updates to target API 36 from 31 Aug 2026;
        // production rollout lands after that date, so ship at 36 now rather than
        // be locked out of shipping updates three weeks in.
        targetSdk = 36
        // 2 was consumed by an upload Play already holds, and a versionCode can
        // never be reused. The build it names is what matters, not the number.
        versionCode = 8
        versionName = "1.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Lets the UI explain *why* sync is unavailable (build without Firebase
        // config) rather than silently doing nothing.
        buildConfigField("boolean", "FIREBASE_CONFIGURED", firebaseConfigured.toString())
        buildConfigField("String", "WEB_CLIENT_ID", "\"$firebaseWebClientId\"")

        // Target device (Samsung S25 Edge) is arm64-only; dropping the other ABIs
        // cuts the ML Kit / CameraX native-lib payload from the APK.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // On for the Play build: strips unused library code (Firebase, CameraX
            // and ML Kit each pull in far more than this app calls) and keeps the
            // download honest. See proguard-rules.pro for the cases R8 cannot infer.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
        // No applicationIdSuffix on debug: :wear must share the phone's
        // applicationId for companion recognition and the Wearable Data Layer, and
        // a suffix would also orphan the data on the already-installed debug build.
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Persistence
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    // Camera + on-device pose detection
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // Explicit, not the 1.0.0 CameraX drags in: the water detector needs EXIF
    // orientation applied, and a sideways bottle defeats the whole method.
    implementation(libs.androidx.exifinterface)
    implementation(libs.mlkit.pose.detection)
    implementation(libs.play.review.ktx)

    // Networking (Kimi API)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)

    // Misc
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.security.crypto)
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Cross-device sync (phone <-> tablet): Google sign-in via Credential Manager,
    // mirrored into Firestore.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.identity.googleid)

    // Wear OS data sync (push stats to the paired watch tile)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
