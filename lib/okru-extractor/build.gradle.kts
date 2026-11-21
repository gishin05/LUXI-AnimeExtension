plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "eu.kanade.tachiyomi.lib.okruextractor"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        named("main") {
            kotlin.srcDirs("src")
            java.srcDirs("src")
        }
    }
}

dependencies {
    implementation(project(":lib:playlist-utils"))
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.aniyomi.extensions.lib)
    compileOnly(libs.jspecify)
    compileOnly(libs.okhttp)
    compileOnly(libs.jsoup)
    compileOnly(libs.kotlinx.serialization.json)
}
