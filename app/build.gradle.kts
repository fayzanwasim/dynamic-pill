import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.personal.dynamicpill"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.personal.dynamicpill"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    lint {
        // Personal sideloaded build: icons for notifications from any app need broad package visibility.
        disable += "QueryAllPackagesPermission"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")

    testImplementation("junit:junit:4.13.2")
}
