plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.bumplog"
    compileSdk = 37

    androidResources { noCompress += "m4a" }

    defaultConfig {
        applicationId = "com.bumplog"
        minSdk = 31
        targetSdk = 34
        versionCode = 27
        versionName = "1.19.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Pins the assistant-guide link in report emails to the exact commit CI built from.
        buildConfigField("String", "GUIDE_REF", "\"${System.getenv("GITHUB_SHA") ?: "v$versionName"}\"")
    }

    // Release signing key is private and never committed. CI passes it in through environment
    // variables from encrypted repository secrets. Without them the release build is unsigned.
    val ksFile: String? = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (ksFile != null) {
            create("release") {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "bumplog"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ksFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Built-in Kotlin (AGP 9): kotlinOptions is gone; jvmTarget defaults to
    // compileOptions.targetCompatibility (17 above), so no kotlin block is needed.
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // The assistant guide is bundled as an asset so the app, the setup email, and the repo doc share one source.
    sourceSets { named("main") { assets.srcDir("build/generated/guide") } }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.18.1")
    testImplementation("org.json:json:20260814")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    // NOTE: androidTest cannot carry kotlin-stdlib even if declared here - AGP dedupes
    // library classes the tested app already provides out of the test APK (verified:
    // APK built with an explicit androidTestImplementation(kotlin("stdlib")) contained
    // zero kotlin classes, and CI run 45309923688 crashed identically). Test-package
    // process code (CaptureMailActivity) must therefore use java.util only.
}

val copyAssistantGuide = tasks.register<Copy>("copyAssistantGuide") {
    from(rootProject.file("docs")) { include("assistant.md") }
    into(layout.buildDirectory.dir("generated/guide"))
}
tasks.named("preBuild") { dependsOn(copyAssistantGuide) }


// CI unit-test failures must show the real exception message, not a bare class name.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
