plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jameslongo.geococcyx"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jameslongo.geococcyx"
        minSdk = 34  // Wear OS 5: Pixel Watch 3 and later
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes {
        release { isMinifyEnabled = false }
        // Shrunk (~28 MB -> ~7 MB) so installs over the watch's flaky Wi-Fi finish before it drops.
        // Still debuggable, so tools/install.sh can push config.json with run-as.
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // ../config.json (settings + credentials, gitignored) is built into the app as its default config.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/cfgAssets"))
}

val copyConfig by tasks.registering(Copy::class) {
    from(rootProject.file("config.json"))
    into(layout.buildDirectory.dir("generated/cfgAssets"))
}
tasks.named("preBuild") { dependsOn(copyConfig) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.wear:wear-ongoing:1.0.0")
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.wear.protolayout:protolayout-material:1.2.1")
    implementation("androidx.health:health-services-client:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1")
}

dependencies { testImplementation("junit:junit:4.13.2") }
