plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.yanjian.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yanjian.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.3.0-local"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/officialAssets"))
    testOptions.unitTests.isIncludeAndroidResources = true
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("junit:junit:4.13.2")
}

// Only the production file is packaged; tests never supply a substitute for it.
val officialContentFile = rootProject.file("data/processed/official-content.json")
val syncOfficialContent by tasks.registering {
    inputs.files(officialContentFile)
    outputs.dir(layout.buildDirectory.dir("generated/officialAssets/content"))
    doLast {
        val destination = layout.buildDirectory.dir("generated/officialAssets/content").get().asFile
        if (officialContentFile.isFile) {
            project.sync { from(officialContentFile); into(destination) }
        } else {
            project.delete(destination)
            logger.warn("Official content is not ready; the app will show an explicit empty state.")
        }
    }
}
tasks.named("preBuild").configure { dependsOn(syncOfficialContent) }
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
}
