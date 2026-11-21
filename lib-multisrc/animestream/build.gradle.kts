plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "eu.kanade.tachiyomi.multisrc.animestream"
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
    implementation(project(":lib:okru-extractor"))
    implementation(project(":lib:dailymotion-extractor"))
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.aniyomi.extensions.lib)
    compileOnly(libs.jspecify)
    compileOnly(libs.okhttp)
    compileOnly(libs.jsoup)
    compileOnly(libs.kotlinx.serialization.json)
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.injekt)
}
