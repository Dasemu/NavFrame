plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "dev.navframe.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.navframe.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "0.13.1"
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
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
