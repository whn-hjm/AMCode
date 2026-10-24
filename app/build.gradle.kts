import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    signingConfigs {
        create("release") {
            storeFile = file("amcode-release.jks")
            storePassword = "amcode123"
            keyAlias = "amcode"
            keyPassword = "amcode123"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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

// ─── Optional first-install TUR bypass bundle ────────────
//
// TUR (tur.kcubeterm.com) is the only source of code-server/nodejs-24 and is
// very slow/blocked on some networks. This opt-in bundle embeds those two
// pinned debs in the APK, so first launch only needs the fast main Termux
// mirror for their small dependencies. The default APK does not change.
//
//   ./gradlew assembleDebug -PofflineBundle=true
//   ./gradlew assembleDebug -PofflineBundle=true -PofflineBundleArch=x86_64
//
// If the two debs are already downloaded elsewhere, avoid the build-time
// download with -PofflineBundleSourceDir=C:\path\to\debs (filename match
// required). Expected APK growth: ~127 MB (aarch64).

data class OfflineDeb(
    val name: String,
    val version: String,
    val filename: String,
    val size: Long,
    val sha256: String,
    val url: String,
    val aptDeps: List<String>
)

val offlineDebsAarch64 = listOf(
    OfflineDeb(
        "nodejs-24", "24.18.0", "nodejs-24_24.18.0_aarch64.deb", 24518244L,
        "f412bd475111cb1741f02487d95b392545349362db4b6bbfea57d9f527d17832",
        "https://tur.kcubeterm.com/pool/tur/nodejs-24_24.18.0_aarch64.deb",
        listOf("libc++", "openssl", "c-ares", "zlib")
    ),
    OfflineDeb(
        "code-server", "4.143.1", "code-server_4.143.1_aarch64.deb", 102502792L,
        "04e97dbba98634dc70385a5e7ef387abbe2d2f6b6bed94ed0a3cf5373d1cefcc",
        "https://tur.kcubeterm.com/pool/tur/code-server_4.143.1_aarch64.deb",
        listOf("libandroid-spawn", "libsecret", "krb5", "ripgrep")
    )
)

val offlineDebsX86_64 = listOf(
    OfflineDeb(
        "nodejs-24", "24.18.0", "nodejs-24_24.18.0_x86_64.deb", 24093024L,
        "79f92e082dda484cbd7842e4e415c95869806cbd8bca01e17ef6764fe7c5d16e",
        "https://tur.kcubeterm.com/pool/tur/nodejs-24_24.18.0_x86_64.deb",
        listOf("libc++", "openssl", "c-ares", "zlib")
    ),
    OfflineDeb(
        "code-server", "4.143.1", "code-server_4.143.1_x86_64.deb", 102576712L,
        "04eee080ab5c113ac5c8c9be745f553a3245b10a941a15ba3ca0c48008aeceec",
        "https://tur.kcubeterm.com/pool/tur/code-server_4.143.1_x86_64.deb",
        listOf("libandroid-spawn", "libsecret", "krb5", "ripgrep")
    )
)

val offlineBundleRequested = providers.gradleProperty("offlineBundle")
    .map { it.toBoolean() }
    .getOrElse(false)
val offlineBundleArch = providers.gradleProperty("offlineBundleArch").getOrElse("aarch64")
val offlineBundleSourceDir = providers.gradleProperty("offlineBundleSourceDir").orNull
val offlineAssetsRoot = layout.buildDirectory.dir("generated/offline-assets/$offlineBundleArch")

fun offlineSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(256 * 1024)
        var count: Int
        while (input.read(buffer).also { count = it } != -1) {
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

fun downloadOfflineDeb(urlText: String, dest: File, expectedSize: Long) {
    val partial = File(dest.parentFile, "${dest.name}.part")
    var conn: HttpURLConnection? = null
    try {
        var have = if (partial.isFile) partial.length() else 0L
        if (have == expectedSize) {
            dest.delete()
            if (!partial.renameTo(dest)) throw GradleException("Cannot rename $partial to $dest")
            return
        }
        conn = URL(urlText).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 600_000
        conn.setRequestProperty("User-Agent", "AMCode-OfflineBundle/1.0")
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Encoding", "identity")
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        val code = conn.responseCode
        if (code == HttpURLConnection.HTTP_OK && have > 0) {
            partial.delete()
            have = 0L
        }
        if (code == 416 && have == expectedSize) {
            dest.delete()
            if (!partial.renameTo(dest)) throw GradleException("Cannot rename $partial to $dest")
            return
        }
        if (code == 416) {
            partial.delete()
            throw GradleException("HTTP 416 with inconsistent partial download for $urlText")
        }
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            throw GradleException("HTTP $code while downloading $urlText")
        }
        val contentLength = conn.contentLengthLong
        val total = if (contentLength > 0) have + contentLength else -1L
        var lastPrint = 0L
        conn.inputStream.use { input ->
            FileOutputStream(partial, true).use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    output.write(buffer, 0, count)
                    have += count
                    val now = System.currentTimeMillis()
                    if (now - lastPrint > 5000) {
                        lastPrint = now
                        println("  ... ${have / (1024 * 1024)} MB" +
                            if (total > 0) " / ${total / (1024 * 1024)} MB" else "")
                    }
                }
            }
        }
        dest.delete()
        if (!partial.renameTo(dest)) {
            throw GradleException("Cannot rename $partial to $dest")
        }
    } finally {
        conn?.disconnect()
    }
}

val downloadOfflineBundle = tasks.register("downloadOfflineBundle") {
    group = "setup"
    description = "Download pinned TUR code-server/nodejs debs into generated assets (opt-in)"
    inputs.property("offlineBundleArch", offlineBundleArch)
    inputs.property("offlineBundleSourceDir", offlineBundleSourceDir ?: "")
    outputs.dir(offlineAssetsRoot)

    doLast {
        if (!offlineBundleRequested) {
            println("offlineBundle is not set; skipping TUR bundle")
            return@doLast
        }
        val arch = offlineBundleArch
        val debs = when (arch) {
            "aarch64" -> offlineDebsAarch64
            "x86_64" -> offlineDebsX86_64
            else -> throw GradleException("Unsupported -PofflineBundleArch=$arch (aarch64 or x86_64)")
        }
        val outDir = offlineAssetsRoot.get().asFile.resolve("offline-debs/$arch")
        outDir.mkdirs()

        for (deb in debs) {
            val dest = File(outDir, deb.filename)
            if (dest.isFile && dest.length() == deb.size &&
                offlineSha256(dest).equals(deb.sha256, ignoreCase = true)
            ) {
                File(outDir, "${deb.filename}.part").delete()
                println("Cached ${deb.filename}")
                continue
            }
            dest.delete()
            val source = offlineBundleSourceDir?.let { File(it, deb.filename) }
            if (source != null) {
                if (!source.isFile) {
                    throw GradleException("offlineBundleSourceDir does not contain ${deb.filename}")
                }
                println("Copying ${deb.filename} from ${source.absolutePath}")
                source.copyTo(dest, overwrite = true)
            } else {
                println("Downloading ${deb.filename} (${deb.size / (1024 * 1024)} MB) from TUR...")
                downloadOfflineDeb(deb.url, dest, deb.size)
            }
            if (dest.length() != deb.size ||
                !offlineSha256(dest).equals(deb.sha256, ignoreCase = true)
            ) {
                val actualSize = dest.length()
                val actual = if (dest.isFile) offlineSha256(dest) else "<missing>"
                dest.delete()
                throw GradleException(
                    "Checksum mismatch for ${deb.filename}: size=$actualSize sha256=$actual"
                )
            }
            File(outDir, "${deb.filename}.part").delete()
        }

        val aptDeps = (debs.flatMap { it.aptDeps }.distinct() + "termux-keyring")
        val manifest = buildString {
            append("{\n")
            append("  \"schema\": 1,\n")
            append("  \"arch\": \"").append(arch).append("\",\n")
            append("  \"aptDependencies\": [")
            append(aptDeps.joinToString(", ") { "\"$it\"" })
            append("],\n")
            append("  \"packages\": [\n")
            debs.forEachIndexed { index, deb ->
                append("    {\"name\": \"").append(deb.name).append("\", ")
                append("\"version\": \"").append(deb.version).append("\", ")
                append("\"filename\": \"").append(deb.filename).append("\", ")
                append("\"size\": ").append(deb.size).append(", ")
                append("\"sha256\": \"").append(deb.sha256).append("\"}")
                if (index != debs.lastIndex) append(",")
                append("\n")
            }
            append("  ]\n}\n")
        }
        File(outDir, "manifest.json").writeText(manifest)
        println("Offline bundle ready: ${outDir.absolutePath}")
    }
}

if (offlineBundleRequested) {
    android.sourceSets.getByName("main").assets.srcDir(offlineAssetsRoot.get().asFile)
    tasks.named("preBuild") { dependsOn(downloadOfflineBundle) }
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
        dependsOn(downloadOfflineBundle)
    }
}

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
