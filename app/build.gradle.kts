import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val backendUrl = providers.gradleProperty("backendUrl")
    .orElse(providers.environmentVariable("BACKEND_URL"))
    .getOrElse(local.getProperty("backend.url", "https://example.invalid")).trimEnd('/')
require(backendUrl.matches(Regex("https://[a-zA-Z0-9.-]+(:[0-9]+)?"))) {
    "backendUrl must be an HTTPS origin without a path."
}
val releaseStore = providers.environmentVariable("RELEASE_KEYSTORE_FILE").orNull
val releasePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
val releaseAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
val releaseName = providers.environmentVariable("RELEASE_VERSION_NAME").getOrElse("0.4.0-dev")
val releaseCode = providers.environmentVariable("RELEASE_VERSION_CODE").getOrElse("4").toInt()

android {
    namespace = "com.bitcoinobsessed.livewallpaper"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "com.bitcoinobsessed.livewallpaper"
        minSdk = 28
        targetSdk = 36
        versionCode = releaseCode
        versionName = releaseName
        buildConfigField("String", "API_BASE_URL", "\"$backendUrl\"")
    }
    if (releaseStore != null) {
        signingConfigs {
            create("distribution") {
                storeFile = file(releaseStore)
                storePassword = releasePassword
                keyAlias = releaseAlias
                keyPassword = releaseKeyPassword
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("distribution")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

val validateRelease = tasks.register("validateReleaseConfiguration") {
    doLast {
        check(listOf(releaseStore, releasePassword, releaseAlias, releaseKeyPassword).all { !it.isNullOrBlank() }) {
            "Release signing is not configured. See docs/RELEASING.md."
        }
        check(backendUrl != "https://example.invalid") { "Set BACKEND_URL for release builds." }
        check(!file("google-services.json").readText().contains("example-bitcoin-obsessed")) {
            "Use your real Firebase client configuration for release builds."
        }
        check(releaseCode >= 1000 && releaseCode <= 2100000000) { "Set a release version code of at least 1000." }
        check(releaseName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Set a numeric release version name." }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(validateRelease) }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation(platform("com.google.firebase:firebase-bom:34.3.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-auth")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    testImplementation("junit:junit:4.13.2")
}
