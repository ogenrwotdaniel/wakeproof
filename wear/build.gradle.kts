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

android {
    namespace = "com.ogenrwotdaniel.wakeproof.wear"
    compileSdk = 37

    defaultConfig {
        // Same applicationId as the phone app so the watch app is recognised as
        // its companion and shares the Wearable Data Layer.
        applicationId = "com.ogenrwotdaniel.wakeproof"
        minSdk = 30
        targetSdk = 34
        // Offset by 1000 from the phone's versionCode (3), deliberately, rather than
        // simply the next free number. Both artifacts live in the same Play app and
        // every versionCode there must be unique forever, so picking 4 would collide
        // with the phone's second-next upload. The offset keeps the two sequences
        // from ever meeting: phone N, watch 1000 + N.
        versionCode = 1003
        versionName = "1.1.0"
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
}
