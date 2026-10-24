import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Same untracked keystore.properties the phone module reads. The watch bundle goes
// into the SAME Play app as the phone one, so it has to be signed with the same
// upload key — an unsigned or debug-signed artifact is rejected at upload.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/**
 * Firebase settings for the watch's direct cloud upload, read from the PHONE's
 * untracked `app/google-services.json` rather than a second copy in this module.
 *
 * The google-services plugin is deliberately NOT applied here: it insists on its
 * own file in the module directory, and two copies of an untracked file is one
 * that goes stale. Both apps share one applicationId, so the phone's Android
 * client entry IS the watch's — the values are baked into BuildConfig and the
 * watch initialises Firebase by hand (`WatchCloud`). Empty strings = no Firebase
 * in this build; the watch then simply never uploads, as before 1.7.
 */
val firebaseValues: Map<String, String> = rootProject.file("app/google-services.json").let { file ->
    if (!file.exists()) return@let emptyMap()
    @Suppress("UNCHECKED_CAST")
    val json = groovy.json.JsonSlurper().parse(file) as Map<String, Any>
    val project = json["project_info"] as Map<String, Any>
    val clients = json["client"] as List<Map<String, Any>>
    val client = clients.firstOrNull {
        val info = it["client_info"] as Map<String, Any>
        (info["android_client_info"] as Map<String, Any>)["package_name"] == "com.ogenrwotdaniel.wakeproof"
    } ?: return@let emptyMap()
    val apiKey = (client["api_key"] as List<Map<String, Any>>).first()["current_key"] as String
    val webClient = clients.asSequence()
        .flatMap { (it["oauth_client"] as? List<Map<String, Any>> ?: emptyList()).asSequence() }
        .firstOrNull { (it["client_type"] as? Number)?.toInt() == 3 }
        ?.get("client_id") as? String
    mapOf(
        "FIREBASE_PROJECT_ID" to project["project_id"] as String,
        "FIREBASE_APP_ID" to (client["client_info"] as Map<String, Any>)["mobilesdk_app_id"] as String,
        "FIREBASE_API_KEY" to apiKey,
        "WEB_CLIENT_ID" to (webClient ?: "")
    )
}

android {
    namespace = "com.ogenrwotdaniel.wakeproof.wear"
    compileSdk = 37

    defaultConfig {
        // Same applicationId as the phone app so the watch app is recognised as
        // its companion and shares the Wearable Data Layer.
        applicationId = "com.ogenrwotdaniel.wakeproof"
        minSdk = 30
        // 35, not 34: Credential Manager (Google sign-in on the watch itself) asks
        // for it on Wear OS.
        targetSdk = 35
        // Offset by 1000 from the phone's versionCode (4), deliberately, rather than
        // simply the next free number. Both artifacts live in the same Play app and
        // every versionCode there must be unique forever, so picking 4 would collide
        // with the phone's second-next upload. The offset keeps the two sequences
        // from ever meeting: phone N, watch 1000 + N.
        versionCode = 1009
        versionName = "1.7.0"

        for (key in listOf("FIREBASE_PROJECT_ID", "FIREBASE_APP_ID", "FIREBASE_API_KEY", "WEB_CLIENT_ID")) {
            buildConfigField("String", key, "\"${firebaseValues[key].orEmpty()}\"")
        }
    }

    buildFeatures {
        buildConfig = true
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
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // findByName, not getByName: a fresh clone with no keystore.properties
            // has no release config and must still build (unsigned).
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    // Wearable Data Layer (read the stats the phone pushed)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.coroutines.guava)

    // Tiles + ProtoLayout UI
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.protolayout.material)

    // Ongoing Activity (self-ticking timer chip on the watch face)
    implementation(libs.androidx.wear.ongoing)

    // Ambient mode: the timer stays on the watch screen, dimmed, when the wrist
    // drops, instead of the watch returning to its face. Needs a ComponentActivity.
    implementation(libs.androidx.wear)
    implementation(libs.androidx.activity)

    // Direct cloud upload (1.7): finished blocks and the live timer go straight to
    // the same Firestore tree the phone and tablet sync through, so a phone left
    // somewhere else no longer holds the watch's sessions back.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.identity.googleid)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
}
