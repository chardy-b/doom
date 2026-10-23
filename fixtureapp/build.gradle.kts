plugins { id("com.android.application") }

android {
    namespace = "com.chardyb.doom.testfixture"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.chardyb.doom.testfixture"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-test-fixture"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
