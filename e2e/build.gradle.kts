plugins { alias(libs.plugins.android.test); alias(libs.plugins.kotlin.android) }
android {
    namespace = "com.poyal.yeobaek.e2e"
    compileSdk = 36
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    defaultConfig { minSdk = 30; targetSdk = 36; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation(libs.test.junit); implementation(libs.test.runner); implementation(libs.uiautomator) }
