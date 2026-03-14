plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.alkaline.taskbrain"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.alkaline.taskbrain"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keyStorePath = project.findProperty("RELEASE_STORE_FILE") as String?
            if (keyStorePath != null) {
                storeFile = file(keyStorePath)
                storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
                keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // -- not truly release
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            // -- actual release settings
            // isMinifyEnabled = true
            // isShrinkResources = true
            // signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        viewBinding = true
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Slow test configuration
// By default, exclude slow tests from regular test runs
// Run with: ./gradlew slowTest
tasks.withType<Test>().configureEach {
    if (name != "slowTest") {
        useJUnit {
            excludeCategories("org.alkaline.taskbrain.testutil.SlowTest")
        }
    }
}

// Custom task to run only slow tests
// Uses afterEvaluate to ensure testDebugUnitTest task exists
afterEvaluate {
    tasks.register<Test>("slowTest") {
        description = "Runs slow integration tests only"
        group = "verification"

        // Configure the test classpath from the debug unit test task
        val debugTestTask = tasks.named<Test>("testDebugUnitTest").get()
        classpath = debugTestTask.classpath
        testClassesDirs = debugTestTask.testClassesDirs

        useJUnit {
            includeCategories("org.alkaline.taskbrain.testutil.SlowTest")
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.ai)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.runtime.livedata)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}