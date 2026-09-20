plugins {
    kotlin("multiplatform") version "2.0.20"
    id("org.jetbrains.compose") version "1.7.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
    id("com.android.application") version "8.5.2"
}

repositories {
    google()
    mavenCentral()
    maven("https://jitpack.io")
}

kotlin {
    jvm()
    androidTarget()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material)
            implementation(compose.ui)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("net.sourceforge.tess4j:tess4j:5.11.0")
            implementation("org.openpnp:opencv:4.9.0-0")
        }

        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.9.2")
            implementation("androidx.appcompat:appcompat:1.7.0")
            implementation("cz.adaptech.tesseract4android:tesseract4android:4.7.0")
        }
    }
}

android {
    namespace = "com.example.imagetotable"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.imagetotable"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.example.imagetotable.MainKt"
    }
}
