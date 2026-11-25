// AGP 9's built-in Kotlin defaults to its bundled KGP; pin the current stable
// KGP (2.4.21), inside its tested range for AGP 9.3.1 and Gradle 9.7.0
// (kotlinlang.org/docs/gradle-configure-project.html).
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
