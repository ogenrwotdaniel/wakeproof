plugins {
    alias(libs.plugins.android.application)
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
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}
