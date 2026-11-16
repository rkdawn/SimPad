plugins { alias(libs.plugins.android.application) }

android {
    namespace = "com.houvven.impad"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "io.github.rkdawn.padbridge"
        minSdk = 27
        targetSdk = 36
        versionCode = 32
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getenv("IMPAD_SIGNING_STORE")
                ?: "${System.getProperty("user.home")}/.android/debug.keystore")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "META-INF/*.version" }
}

dependencies {
    implementation(libs.dexkit)
    compileOnly(libs.libxposed.api)
    testImplementation(libs.junit)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("java.io.tmpdir", System.getenv("TMPDIR") ?: rootProject.file("tmp/gradle").absolutePath)
}
