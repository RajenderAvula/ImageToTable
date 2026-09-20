
// ImageToTable/build.gradle.kts

sourceSets {
    val jvmMain by getting {
        dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.material)
            
            // Tess4J - Java JNI wrapper for Tesseract OCR
            implementation("net.sourceforge.tess4j:tess4j:5.11.0")
            
            // Coroutines for background OCR processing
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
        }
    }
}

plugins {
    kotlin("multiplatform") version "2.0.20"
    id("org.jetbrains.compose") version "1.7.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
}

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

kotlin {
    jvm {
        withJava()
    }
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.material)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.example.imagetotable.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
            )
            packageName = "ImageToTable"
            packageVersion = "1.0.0"
        }
    }
}
