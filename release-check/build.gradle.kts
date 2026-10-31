plugins { id("com.android.test") }

require(providers.gradleProperty("releaseSmokeTest").getOrElse("false") == "true") {
    "The standalone release-check module is test-mode only"
}

android {
    namespace = "dev.scrcpy.releasecheck.receiver"
    compileSdk = 36
    targetProjectPath = ":app"
    // This supported mode skips app compileOnly code/mapping and runtime dedup.
    experimentalProperties["android.experimental.self-instrumenting"] = true
    defaultConfig {
        minSdk = 30
        targetSdk = 35
        testInstrumentationRunner = "dev.scrcpy.releasecheck.ReleaseCheckRunner"
    }
    buildTypes {
        create("release") {
            isDebuggable = false
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            // TestCreationConfig gathers TEST rules even in a standalone module.
            testProguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("junit:junit:4.13.2")
    implementation("com.google.errorprone:error_prone_annotations:2.41.0")
}
