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

    val useFirebaseEmulator = (project.findProperty("useFirebaseEmulator") as String?)
        ?.toBoolean() ?: false

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("Boolean", "AGENT_COMMAND_ENABLED", "false")
            buildConfigField(
                "Boolean", "USE_FIREBASE_EMULATOR", useFirebaseEmulator.toString()
            )
        }
        release {
            buildConfigField("Boolean", "AGENT_COMMAND_ENABLED", "false")
            buildConfigField("Boolean", "USE_FIREBASE_EMULATOR", "false")
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
        buildConfig = true
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
    implementation(libs.androidx.lifecycle.process)
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
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Guard: instrumentation tests with `-PuseFirebaseEmulator=true` MUST
// run on an AVD. The Firebase emulator listens on the host's
// localhost; the AVD reaches it via 10.0.2.2, but a physical device
// can't — and depending on Firebase config could silently hit
// production Firestore instead and mutate real user data.
//
// This task pre-flights `connectedDebugAndroidTest`: it queries every
// connected device via `adb` and fails fast if any of them is a real
// device, so the test launcher never even installs the APK on it.
val useFirebaseEmulatorFlag = (project.findProperty("useFirebaseEmulator") as String?)
    ?.toBoolean() ?: false

val checkAllDevicesAreEmulators = tasks.register("checkAllDevicesAreEmulators") {
    group = "verification"
    description =
        "Fails if any connected adb device is a physical device. Required when " +
            "running connectedDebugAndroidTest with -PuseFirebaseEmulator=true."
    doLast {
        val androidHome = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: "${System.getProperty("user.home")}/Library/Android/sdk"
        val adb = file("$androidHome/platform-tools/adb")
        check(adb.exists()) { "adb not found at $adb" }

        val devicesOutput = providers.exec {
            commandLine(adb.absolutePath, "devices")
        }.standardOutput.asText.get()
        val serials = devicesOutput.lines()
            .drop(1) // header line "List of devices attached"
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts[1] == "device") parts[0] else null
            }

        check(serials.isNotEmpty()) {
            "No connected adb devices. Start an AVD: scripts/test-env-up.sh --with-avd"
        }

        val realDevices = mutableListOf<Pair<String, String>>()
        for (serial in serials) {
            val hwOutput = providers.exec {
                commandLine(adb.absolutePath, "-s", serial, "shell", "getprop", "ro.hardware")
            }.standardOutput.asText.get().trim().lowercase()
            val isEmulator = hwOutput.contains("ranchu") || hwOutput.contains("goldfish")
            if (!isEmulator) realDevices += serial to hwOutput
        }

        check(realDevices.isEmpty()) {
            "Refusing to run emulator-bound tests on a physical device. " +
                "Disconnect or 'adb -s <serial> tcpip 0' the following: " +
                realDevices.joinToString { "${it.first}(ro.hardware=${it.second})" } +
                "\nUse only an AVD: scripts/test-env-up.sh --with-avd"
        }
    }
}

if (useFirebaseEmulatorFlag) {
    afterEvaluate {
        tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
            dependsOn(checkAllDevicesAreEmulators)
        }
    }
}