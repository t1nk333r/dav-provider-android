plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "xyz.satr.davprovider"
    compileSdk = 36

    defaultConfig {
        applicationId = "xyz.satr.davprovider"
        minSdk = 24
        targetSdk = 36
        // Bumped on every build that produces an APK — see AGENTS.md, "Versioning". A build whose
        // code is unchanged but whose version is not tells you nothing about which APK is on the
        // phone, and Android refuses to install one whose versionCode goes backwards, so this only
        // ever increases.
        versionCode = 6
        versionName = "0.3.1"
    }

    compileOptions {
        // dav4jvm exposes java.time (API 26+) and both parsers need it.
        // At minSdk 24 this is a RUNTIME failure without desugaring, not a build failure.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // Off by default since AGP 8, and needed because the settings screen shows the version it
        // was built with — the only way to tell one installed build from another.
        buildConfig = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)

    implementation(libs.dav4jvm)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ezvcard)
    implementation(libs.ical4j)

    // Pinned strictly: the parsers are sensitive to these.
    implementation(libs.commons.codec)
    implementation(libs.commons.lang3)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    testImplementation(libs.junit)
}
