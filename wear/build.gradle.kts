import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Signed with the phone app's key: the Data Layer only connects apps with the same package and signature.
val releaseSigning = Properties().apply {
    val file = File(System.getProperty("user.home"), ".android/fitgymlog/signing.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "io.fitgymlog.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.fitgymlog.app"
        minSdk = 33
        targetSdk = 37
        // Play needs every build of one package to have its own code, so the watch's sit above the phone's (gradle.properties).
        versionCode = 1_000_000 + providers.gradleProperty("fitgymlog.versionCode").get().toInt()
        versionName = providers.gradleProperty("fitgymlog.versionName").get()
    }

    signingConfigs {
        if (!releaseSigning.isEmpty) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":watchlink"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.ongoing)
    // Play services still asks for Fragment 1.1, which breaks activity results; the phone gets a newer one through navigation.
    implementation(libs.androidx.fragment)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
}
