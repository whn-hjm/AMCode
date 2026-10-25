import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

data class SigningCreds(
    val storeFile: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String
)

android {
    namespace = "com.devbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.devbox"
        minSdk = 26
        targetSdk = 28  // API 28 to bypass W^X restriction (apps targeting 29+ cannot exec from app data dir)
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    // Release signing credentials come from app/keystore.properties (gitignored)
    // or AMCODE_STORE_FILE/AMCODE_STORE_PASSWORD/AMCODE_KEY_ALIAS/AMCODE_KEY_PASSWORD
    // environment variables. Nothing is hardcoded in the repository. Without
    // either source the release APK is built unsigned (clean clones / CI).
    val releaseSigning = run {
        val props = Properties()
        val propsFile = File(projectDir, "keystore.properties")
        if (propsFile.isFile) {
            propsFile.inputStream().use { props.load(it) }
        }
        fun cred(prop: String, env: String): String? =
            props.getProperty(prop) ?: providers.environmentVariable(env).orNull
        val storeFile = cred("storeFile", "AMCODE_STORE_FILE")
        val storePassword = cred("storePassword", "AMCODE_STORE_PASSWORD")
        val keyAlias = cred("keyAlias", "AMCODE_KEY_ALIAS")
        val keyPassword = cred("keyPassword", "AMCODE_KEY_PASSWORD")
        if (propsFile.isFile && (storeFile == null || storePassword == null ||
                keyAlias == null || keyPassword == null)
        ) {
            // A half-filled keystore.properties would silently produce an
            // unsigned release; a local config mistake must fail loudly.
            throw GradleException(
                "app/keystore.properties is incomplete: needs storeFile, " +
                    "storePassword, keyAlias and keyPassword"
            )
        }
        if (storeFile != null && storePassword != null && keyAlias != null && keyPassword != null) {
            SigningCreds(storeFile, storePassword, keyAlias, keyPassword)
        } else {
            null
        }
    }

    signingConfigs {
        releaseSigning?.let { creds ->
            create("release") {
                storeFile = file(creds.storeFile)
                storePassword = creds.storePassword
                keyAlias = creds.keyAlias
                keyPassword = creds.keyPassword
            }
        }
    }

    buildTypes {
        release {
            releaseSigning?.let { signingConfig = signingConfigs.getByName("release") }
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    // Prevent AAPT from compressing/decompressing large asset files. The
    // bootstrap zips MUST stay uncompressed (TermuxManager reads them with
    // AssetFileDescriptor.openFd, which cannot span compressed entries); the
    // offline .debs are xz archives already, so deflate would only waste
    // packaging time.
    aaptOptions {
        noCompress += listOf("zip", "gz", "pkg", "deb")
    }
}

// ─── Download Termux bootstrap during build ──────────────
//
// Both ABI bootstrap zips are required runtime assets (TermuxManager copies
// them from the APK on first launch); a clean clone has none because the zips
// are gitignored (~32MB each). Zips are cached in <root>/bootstrap-cache and
// copied into generated assets by arch selection:
//
//   default                -> both zips (~61MB assets, universal APK)
//   -PbootstrapArch=aarch64 -> arm64-only APK (~33MB assets)
//   -PbootstrapArch=x86_64  -> emulator/x86-only APK (~33MB assets)
//
// Each archive is pinned by SHA-256 so a truncated or tampered download can
// never be bundled silently.

val bootstrapDir = File(rootDir, "bootstrap-cache")
val bootstrapGeneratedDir = layout.buildDirectory.dir("generated/bootstrap-assets")
val bootstrapArchFilter = providers.gradleProperty("bootstrapArch").orElse("").get()
val bootstrapTag = "bootstrap-2026.08.02-r1%2Bapt.android-7"
val bootstrapArchives = mapOf(
    "bootstrap-aarch64.zip" to
        ("90a352db1c79aa5b6773003553d52c71da68cbe3eca2d277d0c5683d5163e658" to 32_203_582L),
    "bootstrap-x86_64.zip" to
        ("c2d9892ff6284ada4943c0234296008098d70a73df3a419987ddd03e58af0424" to 32_112_380L)
)

tasks.register("downloadBootstrap") {
    group = "setup"
    description = "Download Termux bootstrap zips and stage them as APK assets"
    // Declared pins/filter/outputs let cached builds skip re-hashing 2x32MB assets.
    inputs.property("bootstrapArchives", bootstrapArchives.map { (name, pin) -> "$name=${pin.first}:${pin.second}" })
    inputs.property("bootstrapArchFilter", bootstrapArchFilter)
    outputs.dir(bootstrapGeneratedDir)

    doLast {
        val selected = when (bootstrapArchFilter) {
            "" -> bootstrapArchives.keys
            "aarch64" -> listOf("bootstrap-aarch64.zip")
            "x86_64" -> listOf("bootstrap-x86_64.zip")
            else -> throw GradleException("Unsupported -PbootstrapArch=$bootstrapArchFilter (aarch64, x86_64 or empty)")
        }
        bootstrapDir.mkdirs()
        val outDir = bootstrapGeneratedDir.get().asFile
        // Drop archs that a previous build staged (arch filter switching).
        outDir.listFiles()?.forEach { if (it.name !in selected) it.delete() }

        for ((name, pinned) in bootstrapArchives) {
            val (expectedSha, expectedSize) = pinned
            val cached = File(bootstrapDir, name)

            if (!(cached.isFile && cached.length() == expectedSize &&
                    offlineSha256(cached).equals(expectedSha, ignoreCase = true))
            ) {
                cached.delete()
                val urls = listOf(
                    "https://github.com/termux/termux-packages/releases/download/$bootstrapTag/$name",
                    "https://ghproxy.com/https://github.com/termux/termux-packages/releases/download/$bootstrapTag/$name",
                    "https://gh-proxy.com/https://github.com/termux/termux-packages/releases/download/$bootstrapTag/$name"
                )
                var downloaded = false
                for (url in urls) {
                    try {
                        println("Downloading $name from: $url")
                        val connection = URL(url).openConnection()
                        connection.connectTimeout = 15000
                        connection.readTimeout = 300000
                        connection.setRequestProperty("User-Agent", "VSBoxed-Build/1.0")

                        val tmp = File(bootstrapDir, "$name.part")
                        connection.getInputStream().use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (tmp.length() != expectedSize ||
                            !offlineSha256(tmp).equals(expectedSha, ignoreCase = true)
                        ) {
                            println("Checksum mismatch for $name (${tmp.length()} bytes); discarding")
                            tmp.delete()
                            continue
                        }
                        if (!tmp.renameTo(cached)) {
                            tmp.delete()
                            throw GradleException("Cannot rename $tmp to $cached")
                        }
                        downloaded = true
                        println("Bootstrap downloaded: ${cached.length()} bytes (sha256 verified)")
                        break
                    } catch (e: Exception) {
                        println("Failed from $url: ${e.message}")
                        cached.delete()
                        File(bootstrapDir, "$name.part").delete()
                    }
                }
                if (!downloaded) {
                    println("WARNING: Cannot download $name (network issue).")
                    println("The app requires this asset at first launch; the build would ship a broken APK.")
                    println("1. Download manually: https://github.com/termux/termux-packages/releases/download/$bootstrapTag/$name")
                    println("2. Place it at: ${cached.absolutePath}")
                    println("3. Rebuild")
                    throw GradleException("Missing bootstrap asset: $name")
                }
            }

            // Stage only the selected arch(s) as assets.
            if (name in selected) {
                val outDir = bootstrapGeneratedDir.get().asFile
                outDir.mkdirs()
                val dest = File(outDir, name)
                if (!(dest.isFile && dest.length() == expectedSize)) {
                    cached.copyTo(dest, overwrite = true)
                }
            }
        }
        println("Bootstrap assets staged: $selected")
    }
}

// Bootstrap zips come from the generated dir (never from src/main/assets).
android.sourceSets.getByName("main").assets.srcDir(bootstrapGeneratedDir.get().asFile)
tasks.named("preBuild") { dependsOn("downloadBootstrap") }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn("downloadBootstrap")
}

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

        // git is a main-repo package (not TUR), so it is resolved + downloaded
        // from the fast main mirror during the bundled first launch instead of
        // being pinned here: the offline APK stays the same size, never touches
        // TUR, and ships a working Source Control out of the box.
        val aptDeps = (debs.flatMap { it.aptDeps }.distinct() + "termux-keyring" + "git")
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

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
}
