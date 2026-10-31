plugins { alias(libs.plugins.android.test); alias(libs.plugins.kotlin.android) }
android {
    namespace = "com.poyal.yeobaek.benchmarks"
    compileSdk = 36
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    defaultConfig { minSdk = 30; targetSdk = 36; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    sourceSets.getByName("main").java.srcDir("../testing/src/main/java")
    buildTypes {
        create("benchmark") { isDebuggable = true; signingConfig = signingConfigs.getByName("debug"); matchingFallbacks += "release" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation(libs.test.junit); implementation(libs.test.runner); implementation(libs.uiautomator); implementation(libs.benchmark) }
