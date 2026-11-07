plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val auralynxApplicationId: String = providers.gradleProperty("auralynx.applicationId").get()
val auralynxVersionName: String = providers.gradleProperty("auralynx.versionName").get()
val auralynxVersionCode: Int = providers.gradleProperty("auralynx.versionCode").get().toInt()

// Release signing is supplied exclusively through environment variables (GitHub Actions secrets).
// When they are absent the release build is produced UNSIGNED and is labelled as such.
val signingKeystorePath: String? = providers.environmentVariable("AURALYNX_KEYSTORE_PATH").orNull
val signingKeystorePassword: String? = providers.environmentVariable("AURALYNX_KEYSTORE_PASSWORD").orNull
val signingKeyAlias: String? = providers.environmentVariable("AURALYNX_KEY_ALIAS").orNull
val signingKeyPassword: String? = providers.environmentVariable("AURALYNX_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(signingKeystorePath, signingKeystorePassword, signingKeyAlias, signingKeyPassword)
    .all { !it.isNullOrBlank() } && file(signingKeystorePath!!).exists()

android {
    namespace = "app.auralynx"
    compileSdk = 37

    defaultConfig {
        applicationId = auralynxApplicationId
        minSdk = 26
        targetSdk = 36
        versionCode = auralynxVersionCode
        versionName = auralynxVersionName
        testInstrumentationRunner = "app.auralynx.HiltTestRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingKeystorePath!!)
                storePassword = signingKeystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
    }

    lint {
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = false
        lintConfig = file("lint.xml")
        sarifReport = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    dependenciesInfo {
        // Do not embed the encrypted Google dependency-metadata block; keeps builds F-Droid friendly.
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:protocol"))
    implementation(project(":core:automation"))
    implementation(project(":core:dsp"))
    implementation(project(":core:bluetooth"))
    implementation(project(":core:data"))
    implementation(project(":core:audio"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:translation"))
    implementation(project(":experimental:research"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
