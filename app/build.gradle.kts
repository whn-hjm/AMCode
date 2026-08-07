import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.devbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.devbox"
        minSdk = 26
        targetSdk = 28  // API 28 to bypass W^X restriction (apps targeting 29+ cannot exec from app data dir)
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
    // Prevent AAPT from compressing/decompressing large asset files
    aaptOptions {
        noCompress += listOf("zip", "gz", "pkg")
    }
}

// ─── Download Termux bootstrap during build ──────────────

val bootstrapDir = File(projectDir, "src/main/assets")
val bootstrapFile = File(bootstrapDir, "bootstrap-aarch64.zip")
val bootstrapUrl = "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.08.02-r1%2Bapt.android-7/bootstrap-aarch64.zip"
val bootstrapMirrorUrl = "https://ghproxy.com/$bootstrapUrl"

tasks.register("downloadBootstrap") {
    group = "setup"
    description = "Download Termux bootstrap for bundling in APK assets"

    doLast {
        if (bootstrapFile.exists() && bootstrapFile.length() > 10_000_000) {
            println("Bootstrap already cached: ${bootstrapFile.absolutePath} (${bootstrapFile.length()} bytes)")
            return@doLast
        }

        bootstrapDir.mkdirs()
        val urls = listOf(bootstrapUrl, bootstrapMirrorUrl)

        for (url in urls) {
            try {
                println("Downloading bootstrap from: $url")
                val connection = URL(url).openConnection()
                connection.connectTimeout = 15000
                connection.readTimeout = 300000
                connection.setRequestProperty("User-Agent", "VSBoxed-Build/1.0")

                connection.getInputStream().use { input ->
                    bootstrapFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                println("Bootstrap downloaded: ${bootstrapFile.length()} bytes")
                return@doLast
            } catch (e: Exception) {
                println("Failed from $url: ${e.message}")
                bootstrapFile.delete()
            }
        }
        println("WARNING: Cannot download bootstrap (network issue).")
        println("The app will download it on first launch, or you can:")
        println("1. Download manually: $bootstrapUrl")
        println("2. Place it at: ${bootstrapFile.absolutePath}")
        println("3. Rebuild")
        println("Continuing build without bundled bootstrap...")
    }
}

// Download bootstrap during build (cached in assets after first build)
tasks.named("preBuild") { dependsOn("downloadBootstrap") }

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-service:2.6.2")

    // WebView
    implementation("androidx.webkit:webkit:1.8.0")

    // Material Design
    implementation("com.google.android.material:material:1.10.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
