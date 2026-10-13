import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}
val signing = Properties().apply {
    rootProject.file("signing.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
android {
    namespace = "com.poyal.yeobaek"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.poyal.yeobaek"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (signing.isNotEmpty()) create("personal") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signing.isNotEmpty()) signingConfig = signingConfigs.getByName("personal")
        }
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        create("profile") {
            initWith(getByName("release"))
            applicationIdSuffix = ".profile"
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":core:txt"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material)
    implementation(libs.compose.icons)
    implementation(libs.activity)
    implementation(libs.lifecycle)
    implementation(libs.viewmodel)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.datastore)
    implementation(libs.coroutines)
    implementation(libs.profileinstaller)
    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.compose.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.test.junit)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.espresso)
}
