plugins { id("com.android.application"); kotlin("android") }
val releaseSigningNames = listOf("NAVFRAME_KEYSTORE_PATH", "NAVFRAME_KEYSTORE_PASSWORD", "NAVFRAME_KEY_ALIAS", "NAVFRAME_KEY_PASSWORD")
val releaseSigning = releaseSigningNames.associateWith { System.getenv(it).orEmpty() }
val hasReleaseSigning = releaseSigning.values.all { it.isNotBlank() }
if (releaseSigning.values.any { it.isNotBlank() } && !hasReleaseSigning) {
    throw GradleException("Incomplete release signing configuration. Required variables: ${releaseSigningNames.joinToString()}")
}
val navframeVersionCode = providers.gradleProperty("navframeVersionCode").orNull?.toInt()
require(navframeVersionCode == null || navframeVersionCode in 1..2_100_000_000) { "Android versionCode must be between 1 and 2100000000" }
android {
    namespace = "dev.navframe.app"
    compileSdk = 36
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "dev.navframe.app"
        minSdk = 26
        targetSdk = 35
        versionName = "0.13.4"
        versionCode = 20
    }
    if (hasReleaseSigning) {
        signingConfigs.create("production") {
            storeFile = file(releaseSigning.getValue("NAVFRAME_KEYSTORE_PATH"))
            storePassword = releaseSigning.getValue("NAVFRAME_KEYSTORE_PASSWORD")
            keyAlias = releaseSigning.getValue("NAVFRAME_KEY_ALIAS")
            keyPassword = releaseSigning.getValue("NAVFRAME_KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("production")
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
navframeVersionCode?.let { android.defaultConfig.versionCode = it }
gradle.taskGraph.whenReady {
    if (!hasReleaseSigning && allTasks.any { it.path in setOf(":app:assembleRelease", ":app:bundleRelease", ":app:packageRelease") }) {
        throw GradleException("Production builds require release signing. Configure ${releaseSigningNames.joinToString()}.")
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation("org.maplibre.gl:android-sdk-opengl:13.5.2")
    implementation(project(":navilite"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    systemProperty("navframe.previewDir", layout.buildDirectory.dir("outputs/preview").get().asFile.absolutePath)
}
