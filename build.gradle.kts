buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        if (file("app/google-services.json").exists()) {
            classpath("com.google.gms:google-services:4.4.3")
        }
    }
}

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
