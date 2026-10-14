plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI sets these from scripts/version.sh; local builds use the dev defaults.
val heyMuseVersionName = providers.environmentVariable("HEYMUSE_VERSION_NAME").orElse("0.1.0-dev").get()
val heyMuseVersionCode = providers.environmentVariable("HEYMUSE_VERSION_CODE").orElse("1").get().toInt()
// Release signing: CI decodes the keystore from repository secrets. Without it, release APKs are unsigned.
val releaseKeystorePath = providers.environmentVariable("HEYMUSE_KEYSTORE_PATH").orNull?.takeIf { it.isNotEmpty() }

android {
    namespace = "io.github.pgodlews.heymuse"
    compileSdk = 35
    ndkVersion = "27.3.13750724"
    signingConfigs {
        if (releaseKeystorePath != null) {
            fun signingEnv(name: String): String =
                providers.environmentVariable(name).orNull?.takeIf { it.isNotEmpty() }
                    ?: throw GradleException("HEYMUSE_KEYSTORE_PATH is set but $name is missing; set all release signing variables")
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = signingEnv("HEYMUSE_KEYSTORE_PASSWORD")
                keyAlias = signingEnv("HEYMUSE_KEY_ALIAS")
                keyPassword = signingEnv("HEYMUSE_KEY_PASSWORD")
            }
        }
    }
    defaultConfig {
        applicationId = "io.github.pgodlews.heymuse"
        minSdk = 28
        targetSdk = 29
        versionCode = heyMuseVersionCode
        versionName = heyMuseVersionName
        // Every Portal is 64-bit ARM, and the Go core is built for it alone.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { targets("pico") } }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    androidResources {
        // The voice data is read by path, so it is copied out of the APK once; compressing it only
        // slows that copy.
        noCompress += "bin"
    }
    lint {
        // targetSdk 29 is deliberate (Portal runs API 28–29; APKs are sideloaded, not published on Play).
        warning += "ExpiredTargetSdkVersion"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// The Go core: core/heymuse bound with gomobile (scripts/build-core.sh). Rebuilt whenever core/ changes.
val coreAar = layout.projectDirectory.file("libs/heymuse-core.aar")
val buildCore by tasks.registering(Exec::class) {
    description = "Builds the Go core into libs/heymuse-core.aar with gomobile."
    inputs.dir(rootProject.layout.projectDirectory.dir("core"))
        .withPropertyName("core").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(coreAar)
    val ndkDir = providers.provider { android.ndkDirectory.absolutePath }
    val sdkDir = providers.provider { android.sdkDirectory.absolutePath }
    doFirst {
        environment("ANDROID_NDK_HOME", ndkDir.get())
        environment("ANDROID_HOME", sdkDir.get())
    }
    commandLine(rootProject.file("scripts/build-core.sh").absolutePath, coreAar.asFile.absolutePath)
}
tasks.named("preBuild") { dependsOn(buildCore) }

dependencies {
    implementation(files(coreAar))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
