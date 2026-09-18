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
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        // dav4jvm exposes java.time (API 26+) and both parsers need it.
        // At minSdk 24 this is a RUNTIME failure without desugaring, not a build failure.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
