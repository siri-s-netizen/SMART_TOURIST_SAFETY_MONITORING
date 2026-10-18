import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * The Google Maps API key is *never* committed to source control.
 *
 * Resolution order (first non-blank wins):
 *   1. Gradle property  : -PMAPS_API_KEY=...   or   MAPS_API_KEY=... in gradle.properties
 *   2. Environment var  : $env:MAPS_API_KEY
 *   3. local.properties : MAPS_API_KEY=...      (git-ignored)
 *
 * When no key is configured the field is an empty string and the app still builds and runs;
 * the map screens show a "no API key configured" notice instead of a blank map.
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun configValue(key: String): String =
    (project.findProperty(key) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(key)?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: ""

val mapsApiKey: String = configValue("MAPS_API_KEY")
val escapedMapsApiKey: String = mapsApiKey.replace("\\", "\\\\").replace("\"", "\\\"")

/**
 * The authority service this build should talk to before anyone has configured one.
 *
 * Resolution follows the same order as the maps key: Gradle property
 * (`-PAUTHORITY_URL=http://192.168.1.20:8080`), then environment (`TSM_AUTHORITY_URL`), then
 * `local.properties`. Leaving it empty is legal and the app then asks the user to set the address in
 * Settings; shipping a *development* LAN address is only ever done for a local demo build, never by
 * default, because a stale IP baked into a release is worse than an empty field.
 */
val defaultAuthorityUrl: String = configValue("AUTHORITY_URL")
val escapedDefaultAuthorityUrl: String =
    defaultAuthorityUrl.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.touristsafety.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.touristsafety.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        buildConfigField("String", "MAPS_API_KEY", "\"$escapedMapsApiKey\"")

        // Seeded into the session store on first launch only, and always overridable in Settings.
        buildConfigField("String", "DEFAULT_AUTHORITY_URL", "\"$escapedDefaultAuthorityUrl\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Room schema export: guarantees that every schema revision is checked in under app/schemas,
    // which is what makes safe, non-destructive migrations possible.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

/**
 * Give the unit-test JVM real headroom.
 *
 * Gradle's default ceiling for a test worker is **512 MB**, and this suite is now ~275 tests, most of
 * them running under Robolectric — which builds a fresh instrumented classloader plus an in-memory Room
 * database per test class. At 512 MB the worker spends the run under heavy GC pressure, and the two SOS
 * tests, which poll for work happening on another thread against Room's executors, were the first to
 * notice: they passed when the class ran alone and exhausted their (60 s) ceiling in a full-suite run.
 *
 * Raising the ceiling does not reserve memory up front — the JVM grows into it as needed — so this is
 * headroom, not an allocation. Metaspace is capped explicitly because every Robolectric sandbox keeps
 * its own instrumented classes alive.
 */
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    maxHeapSize = "1536m"
    jvmArgs("-XX:MaxMetaspaceSize=512m")

    /**
     * Forward the end-to-end backend address into the test JVM.
     *
     * `AuthorityLiveBackendTest` is opt-in: with no address it skips, so an ordinary `testDebugUnitTest`
     * never depends on a server being up. When one has been published for the run, pass
     * `-Dtsm.e2e.baseUrl=http://127.0.0.1:8080` (and optionally
     * `-Dtsm.e2e.credentials=<path to DEV_CREDENTIALS.txt>`) on the Gradle command line and the test
     * exercises the production transport against the real Python service.
     */
    systemProperty("tsm.e2e.baseUrl", System.getProperty("tsm.e2e.baseUrl") ?: System.getenv("TSM_E2E_BASE_URL") ?: "")
    systemProperty(
        "tsm.e2e.credentials",
        System.getProperty("tsm.e2e.credentials") ?: System.getenv("TSM_E2E_CREDENTIALS") ?: "",
    )
}

dependencies {
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.play.services.location)
    implementation(libs.play.services.maps)
    implementation(libs.maps.compose)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
