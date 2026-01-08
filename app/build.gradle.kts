import java.util.Properties
import java.time.Duration

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.paparazzi)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.firebase.perf)
}

// Load version from properties file
val versionProps = Properties().apply {
    val versionFile = rootProject.file("version.properties")
    if (versionFile.exists()) {
        load(versionFile.inputStream())
    }
}

val versionMajor = versionProps.getProperty("VERSION_MAJOR", "1").toInt()
val versionMinor = versionProps.getProperty("VERSION_MINOR", "0").toInt()
val versionPatch = versionProps.getProperty("VERSION_PATCH", "0").toInt()
val appVersionCode = versionMajor * 10000 + versionMinor * 100 + versionPatch
val appVersionName = "$versionMajor.$versionMinor.$versionPatch"

// Load signing config from environment or local file
val signingPropsFile = rootProject.file("signing.properties")
val signingProps = Properties()
if (signingPropsFile.exists()) {
    signingPropsFile.inputStream().use { signingProps.load(it) }
}

val signingKeystoreFile: File? = System.getenv("KEYSTORE_FILE")?.let { rootProject.file(it) }
    ?: signingProps.getProperty("KEYSTORE_FILE")?.let { rootProject.file(it) }?.takeIf { it.exists() }
val signingKeystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
    ?: signingProps.getProperty("KEYSTORE_PASSWORD")
val signingKeyAlias: String? = System.getenv("KEY_ALIAS")
    ?: signingProps.getProperty("KEY_ALIAS")
val signingKeyPassword: String? = System.getenv("KEY_PASSWORD")
    ?: signingProps.getProperty("KEY_PASSWORD")

val canSign = signingKeystoreFile != null && signingKeystorePassword != null && signingKeyAlias != null && signingKeyPassword != null

android {
    namespace = "com.paperless.scanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.paperless.scanner"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "com.paperless.scanner.HiltTestRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = signingKeystoreFile
            storePassword = signingKeystorePassword ?: ""
            keyAlias = signingKeyAlias ?: ""
            keyPassword = signingKeyPassword ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (signingKeystoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
        }
    }

    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
        warningsAsErrors = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "4096m"
                it.jvmArgs("-XX:MaxMetaspaceSize=1024m", "-XX:+HeapDumpOnOutOfMemoryError")

                // Kill hanging tests - 3 minute timeout per test
                it.testLogging {
                    events("passed", "skipped", "failed", "standardError")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    showStandardStreams = false
                }

                // Fail fast - stop after first failure to prevent hanging
                it.failFast = true

                // Fork every test to isolate failures
                it.forkEvery = 10

                // Kill the whole test task after 5 minutes (300 seconds)
                it.timeout.set(Duration.ofMinutes(5))
            }
        }
    }
}

// Fix KSP duplicate classes issue by excluding byRounds directory
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    doFirst {
        // Exclude byRounds from Java compilation sourcepath
        val javaCompileTask = project.tasks.findByName("compileDebugJavaWithJavac") as? JavaCompile
        javaCompileTask?.exclude("**/byRounds/**")
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Explicitly exclude byRounds directory from Java compilation
    exclude("**/byRounds/**")
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.material3.windowSize)
    implementation(libs.androidx.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // DataStore
    implementation(libs.datastore.preferences)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // WorkManager
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Glance Widget
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // Biometric
    implementation(libs.biometric)

    // Image Loading
    implementation(libs.coil.compose)

    // Drag & Drop Reordering
    implementation(libs.reorderable)

    // MLKit Document Scanner
    implementation(libs.mlkit.document.scanner)

    // MLKit Text Recognition (OCR)
    implementation(libs.mlkit.text.recognition)

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // PDF Generation
    implementation(libs.itext.kernel)
    implementation(libs.itext.io)
    implementation(libs.itext.layout)

    // Firebase Analytics & Crashlytics
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.perf)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.work.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.navigation.testing)
    kspAndroidTest(libs.hilt.compiler)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// Detekt configuration
// Run: ./gradlew detekt
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/baseline.xml")
    parallel = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(true)
        txt.required.set(false)
        sarif.required.set(false)
    }
}

// KtLint configuration
// Run: ./gradlew ktlintCheck or ./gradlew ktlintFormat
ktlint {
    android.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(false)
    enableExperimentalRules.set(false)
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
    }
}
