plugins { id("com.android.application") }

android {
    namespace = "com.chardyb.doom.fixturegate"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.chardyb.doom.fixturegate"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-test-fixture"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
}
