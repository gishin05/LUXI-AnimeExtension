plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "eu.kanade.tachiyomi.lib.playlistutils"
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
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.aniyomi.extensions.lib)
    compileOnly(libs.jspecify)
    compileOnly(libs.okhttp)
}
