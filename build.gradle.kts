buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        // AGP 9 has a runtime dependency on KGP 2.2.10 and uses built-in Kotlin.
        // dav4jvm 4.1.0 carries Kotlin metadata mv=[2,4,0], which 2.2.x rejects,
        // so KGP is upgraded here — the documented way to raise the built-in version.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
