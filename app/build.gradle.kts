import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingPath = providers.gradleProperty("signingProperties")
    .orElse(providers.environmentVariable("AIHOT_SIGNING_PROPERTIES"))
val releaseCredentials = Properties()
if (signingPath.isPresent) {
    file(signingPath.get()).inputStream().use(releaseCredentials::load)
}

android {
    namespace = "dev.personal.aihotreader"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    testBuildType = providers.gradleProperty("deviceTestBuildType").orElse("debug").get()

    defaultConfig {
        applicationId = "dev.personal.aihotreader"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("appVersionCode").orElse("5").get().toInt()
        versionName = "1.0.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (!releaseCredentials.isEmpty) {
            create("personalRelease") {
                storeFile = file(requireNotNull(releaseCredentials.getProperty("storeFile")))
                storePassword = requireNotNull(releaseCredentials.getProperty("storePassword"))
                keyAlias = requireNotNull(releaseCredentials.getProperty("keyAlias"))
                keyPassword = requireNotNull(releaseCredentials.getProperty("keyPassword"))
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!releaseCredentials.isEmpty) {
                signingConfig = signingConfigs.getByName("personalRelease")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.6.1")
}
