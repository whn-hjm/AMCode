package com.devbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

/**
 * Manages the Termux-native environment embedded within VSBoxed.
 *
 * Architecture:
 * - All Termux packages run NATIVELY on Android's bionic libc — no proot, no ptrace overhead.
 * - Binary paths are patched from "com.termux" to "com.devbox" (same 10-char length).
 * - The embedded environment lives at: /data/data/com.devbox/files/usr/
 *
 * Environment variables set:
 *   PREFIX          = /data/data/com.devbox/files/usr
 *   PATH            = $PREFIX/bin:$PREFIX/bin/applets
 *   LD_LIBRARY_PATH = $PREFIX/lib
 *   HOME            = /data/data/com.devbox/files/home
 *   TMPDIR          = $PREFIX/tmp
 */
object TermuxManager {

    private const val TAG = "TermuxManager"

    /** TUR is the only source of code-server and nodejs-24. Keep this in sync
     *  with String constants used by the build-time offline bundle. */
    private const val TUR_SOURCE =
        "deb [trusted=yes] https://tur.kcubeterm.com tur-packages tur tur-on-device tur-continuous\n"

    /** Max simultaneous HTTP Range connections for one large .deb. */
    private const val MAX_RANGE_CONNECTIONS = 8

    /**
     * libc++ 30 added this symbol; libc++ 29 lacks it. TUR code-server native
     * modules (spdlog / @parcel/watcher) fail to load without it.
     */
    private const val LIBCPP_HASH_SYMBOL = "_ZNSt6__ndk113__hash_memoryEPKvm"

    /**
     * Bump when the pkg-setup repair contract changes. The completion marker
     * key includes this revision, so a code update re-runs the repair once.
     */
    private const val PKG_SETUP_REVISION = 2
    private const val PKG_SETUP_MARKER_SCHEMA = 1
    private const val PKG_SETUP_MARKER_FILE = ".amcode-pkg-setup"

    /** Assets that must be copied and executed by the pkg-setup repair. */
    private val PKG_SETUP_ASSETS = listOf(
        "amcode-dpkg-wrapper.sh",
        "amcode-pkg-setup.sh",
        "tur.gpg"
    )

    // Package name MUST be exactly 10 chars to match "com.termux" for binary patching
    const val PACKAGE_NAME = "com.devbox"

    // Set by initialize(); all install paths need the AssetManager for the
    // optional build-time TUR bundle and setup scripts.
    private lateinit var appContext: Context

    // Only inspect the (optional) bundled debs once per initialize() run.
    private var offlineBundleChecked = false

    // Last real download/apt error, surfaced to the setup UI instead of a
    // generic "check network" message.
    @Volatile
    private var lastInstallError: String = ""

    // Bootstrap asset file — selected by architecture at runtime.
    // Returns null on ABIs without a bundled bootstrap (armeabi-v7a, i686,
    // 32-bit x86): the aarch64 binaries cannot execute there, so failing
    // loudly beats shipping an environment that dies on the first exec.
    private fun bootstrapAsset(): String? {
        val primary = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        return when {
            primary.startsWith("x86_64") -> "bootstrap-x86_64.zip"
            primary.startsWith("arm64") || primary.startsWith("aarch64") ->
                "bootstrap-aarch64.zip"
            else -> null
        }
    }

    // Live terminal output for UI
    private val _terminalOutput = MutableStateFlow("")
    val terminalOutput: StateFlow<String> = _terminalOutput.asStateFlow()

    private var setupLogFile: File? = null

    /**
     * Persist the setup/terminal log next to the app files so bug reports do
     * not depend on logcat. One rotation level (setup.log.1), ~1 MB each;
     * any IO failure is swallowed — logging must never break the setup.
     */
    private fun persistSetupLog(line: String) {
        try {
            var file = setupLogFile
            if (file == null) {
                file = File(App.instance.filesDir, "logs/setup.log")
                file.parentFile?.mkdirs()
                setupLogFile = file
            }
            if (file.length() > 1_000_000) {
                val rotated = File(file.parentFile, "setup.log.1")
                rotated.delete()
                file.renameTo(rotated)
            }
            file.appendText(line)
        } catch (_: Exception) {
        }
    }

    fun emitOutput(line: String) {
        val current = _terminalOutput.value
        // Keep last ~5000 chars to avoid memory bloat
        val updated = if (current.length > 5000) current.takeLast(2000) + line else current + line
        _terminalOutput.value = updated
        Log.i(TAG, line.trimEnd())
        persistSetupLog(line)
    }

    // Setup states
    enum class State {
        UNINITIALIZED,
        DOWNLOADING_BOOTSTRAP,
        EXTRACTING,
        PATCHING,
        INSTALLING_PACKAGES,
        READY,
        FAILED
    }

    data class SetupProgress(
        val state: State = State.UNINITIALIZED,
        val message: String = "",
        val percent: Int = 0
    )

    private val _setupState = MutableStateFlow(SetupProgress())
    val setupState: StateFlow<SetupProgress> = _setupState.asStateFlow()

    // Derived paths based on our private app directory
    private lateinit var filesDir: File
    val usrDir: File get() = File(filesDir, "usr")
    val binDir: File get() = File(usrDir, "bin")
    /** Home directory — set after filesDir is initialized */
    private var _homeDir: File? = null
    val homeDir: File get() = _homeDir ?: File(filesDir, "home")
    val tmpDir: File get() = File(usrDir, "tmp")
    val etcDir: File get() = File(usrDir, "etc")
    val varDir: File get() = File(usrDir, "var")
    val aptDir: File get() = File(etcDir, "apt")

    // Environment variables for process spawning
    // dpkg needs /usr/bin:/usr/sbin:/sbin:/bin for maintainer scripts
    val environment: Map<String, String>
        get() {
            val env = linkedMapOf(
                "PREFIX" to usrDir.absolutePath,
                // termux-exec/libtermux-exec.so uses TERMUX__PREFIX (and many
                // Termux scripts use TERMUX_PREFIX) to rewrite standard shebangs.
                "TERMUX__PREFIX" to usrDir.absolutePath,
                "TERMUX_PREFIX" to usrDir.absolutePath,
                "PATH" to "${binDir.absolutePath}:/system/bin:/system/xbin:/usr/bin:/usr/sbin:/sbin:/bin",
                "LD_LIBRARY_PATH" to File(usrDir, "lib").absolutePath,
                "HOME" to homeDir.absolutePath,
                "TMPDIR" to tmpDir.absolutePath,
                "TERM" to "xterm-256color",
                "LANG" to "en_US.UTF-8",
                "ANDROID_DATA" to "/data",
                "ANDROID_ROOT" to "/system"
            )
            // termux-exec rewrites standard shebangs (#!/usr/bin/env ...,
            // #!/bin/sh) to the Termux prefix. Only preload it once the
            // bootstrap symlink exists, otherwise every child process fails
            // with "CANNOT LINK EXECUTABLE".
            val termuxExec = File(usrDir, "lib/libtermux-exec.so")
            if (termuxExec.exists()) {
                env["LD_PRELOAD"] = termuxExec.absolutePath
            }
            return env
        }

    /**
     * Initialize Termux environment. Call once from background thread.
     * Idempotent — if already initialized, returns immediately.
     */
    /** Serializes installs: a HOME-switch restart racing the first install
     *  must never run two bootstrap/apt pipelines over the same prefix. */
    private val initializeMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun initialize(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        initializeMutex.withLock { initializeLocked(context) }
    }

    private suspend fun initializeLocked(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        filesDir = context.filesDir
        appContext = context.applicationContext
        offlineBundleChecked = false
        lastInstallError = ""

        // Existing installations (including an APK installed over an older data
        // directory) must also get the keyring/SYMLINKS/pkg/dpkg repairs. This
        // is idempotent and does not touch code-server or re-download anything.
        if (File(binDir, "bash").exists()) {
            ensurePkgSetup(context)
            // Migrate an older installation that already has code-server but
            // predates the completion marker: do not reinstall it.
            if (isBootstrapReady() && File(binDir, "code-server").exists()) {
                // Repair installs created before libc++ 30 was available. TUR
                // code-server native modules (spdlog/@parcel/watcher) otherwise
                // fail with "_ZNSt6__ndk113__hash_memoryEPKvm" (libc++ 29).
                ensureLibCpp30()
                registerLegacyPackagesInDpkgDb()
                File(filesDir, ".amcode-ready").writeText("1")
            }
        }

        if (isReady()) {
            Log.i(TAG, "Termux environment already initialized")
            _setupState.value = SetupProgress(State.READY, "Environment ready", 100)
            return@withContext Result.success(Unit)
        }

        try {
            emitOutput("=== VSBoxed Environment Setup ===\n")

            // Step 1: Create directory structure
            _setupState.value = SetupProgress(State.EXTRACTING, "Creating directories...", 2)
            emitOutput("[1/6] Creating directory structure...\n")
            createDirectories()
            _setupState.value = SetupProgress(State.EXTRACTING, "Extracting bootstrap from APK...", 5)

            // Step 2: Extract bootstrap from APK (bundled, no network)
            val assetName = bootstrapAsset()
            if (assetName == null) {
                val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
                val message = "32-bit or unsupported ABI ($abi); AMCode requires arm64-v8a or x86_64"
                emitOutput("[FAILED] $message\n")
                _setupState.value = SetupProgress(State.FAILED, message, 0)
                return@withContext Result.failure(IllegalStateException(message))
            }
            val bootstrapZip = File(filesDir, assetName)
            if (!bootstrapZip.exists()) {
                _setupState.value = SetupProgress(State.EXTRACTING, "Extracting environment files...", 8)
                try {
                    // Copy to a temp file and verify the byte count before the
                    // rename: a truncated cache zip would otherwise be reused
                    // by every future launch until the user clears app data.
                    val tmp = File(filesDir, "${bootstrapZip.name}.tmp")
                    context.assets.openFd(assetName).use { fd ->
                        fd.createInputStream().use { input ->
                            FileOutputStream(tmp).use { output ->
                                val buffer = ByteArray(65536)
                                var total = 0L
                                var count: Int
                                while (input.read(buffer).also { count = it } != -1) {
                                    output.write(buffer, 0, count)
                                    total += count
                                }
                                if (total != fd.length) {
                                    throw IOException("bootstrap copy truncated ($total/${fd.length})")
                                }
                                Log.i(TAG, "Bootstrap extracted: $total bytes (from ${fd.length} in APK)")
                            }
                        }
                    }
                    if (!tmp.renameTo(bootstrapZip)) {
                        bootstrapZip.delete()
                        if (!tmp.renameTo(bootstrapZip)) {
                            throw IOException("cannot install bootstrap cache at $bootstrapZip")
                        }
                    }
                } catch (e: Exception) {
                    File(filesDir, "${bootstrapZip.name}.tmp").delete()
                    Log.e(TAG, "Bootstrap extraction failed", e)
                    _setupState.value = SetupProgress(State.FAILED, "Extraction failed: ${e.message}", 0)
                    return@withContext Result.failure(e)
                }
            }

            // Step 3: Extract bootstrap zip
            _setupState.value = SetupProgress(State.EXTRACTING, "Extracting environment...", 12)
            emitOutput("[2/6] Extracting Termux bootstrap...\n")
            try {
                extractBootstrap(bootstrapZip)
            } catch (e: Exception) {
                // An unreadable cache zip would brick every following launch
                // (the cache above is reused); drop it so the next attempt
                // re-copies a fresh copy from the APK.
                bootstrapZip.delete()
                throw e
            }

            // Step 4: Patch binary paths
            _setupState.value = SetupProgress(State.PATCHING, "Patching binary paths...", 20)
            emitOutput("[3/6] Patching binary paths (com.termux -> com.devbox)...\n")
            patchBinaries()

            // Step 5: Create symlinks first (needed by curl for mirror test)
            _setupState.value = SetupProgress(State.PATCHING, "Creating symlinks...", 25)
            emitOutput("[4/6] Creating symlinks...\n")
            createSymlinks()
            _setupState.value = SetupProgress(State.PATCHING, "Testing apt mirrors...", 28)
            emitOutput("[5/6] Testing mirrors + configuring apt...\n")
            setupAptSources()

            // Keep the known-good bootstrap install path unchanged: package
            // keys/SYMLINKS/the dpkg wrapper are repaired after the initial
            // `dpkg --root` install below finishes. On an existing install the
            // repair has already run at the top of initialize().

            // Step 6: Install packages — retry until successful
            emitOutput("[6/6] Installing packages (nodejs, code-server)...\n")
            var ok = false
            for (attempt in 0..2) {
                _setupState.value = SetupProgress(State.INSTALLING_PACKAGES,
                    if (attempt > 0) "Installing packages (attempt ${attempt+1}/3)..." else "Installing packages...",
                    30 + attempt * 10)
                ok = installPackages()
                if (ok) break
                if (attempt < 2) {
                    emitOutput("[retry] Download interrupted, retrying in 3s...\n")
                    Thread.sleep(3000)
                }
            }

            if (!ok) {
                val detail = lastInstallError.ifBlank { "download or package installation failed" }
                emitOutput("[FAILED] Could not install packages after 3 attempts: $detail\n")
                emitOutput("[hint] TUR is the slow part: build with -PofflineBundle=true to ship\n")
                emitOutput("       the pinned code-server/nodejs debs inside the APK.\n")
                _setupState.value = SetupProgress(State.FAILED, "Install failed: $detail", 0)
                return@withContext Result.failure(RuntimeException("Package installation failed: $detail"))
            }

            // The initial install unpacks under the package's com.termux
            // prefix and moves the payload into place by hand. Repair the
            // keyring/symlinks/wrapper and migrate the resulting dpkg database
            // paths so later `pkg install` runs see a consistent package DB.
            ensurePkgSetup(context)
            ensureLibCpp30()

            _setupState.value = SetupProgress(State.READY, "Environment ready", 100)
            File(filesDir, ".amcode-ready").writeText("1")
            // The ~32MB bootstrap cache is no longer needed once usr/ is built.
            // Also drop the legacy always-aarch64-named copy from older builds.
            bootstrapZip.delete()
            File(filesDir, "bootstrap-aarch64.zip").delete()
            Log.i(TAG, "Termux environment initialized successfully")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Termux environment", e)
            val detail = lastInstallError.ifBlank { e.message ?: e.javaClass.simpleName }
            _setupState.value = SetupProgress(State.FAILED, "Failed: $detail", 0)
            Result.failure(e)
        }
    }

    fun isReady(): Boolean {
        if (!this::filesDir.isInitialized) return false
        return File(filesDir, ".amcode-ready").exists() &&
               isBootstrapReady() &&
               File(binDir, "code-server").exists()
    }

    /** Bootstrap is extracted and patched; packages may still be missing. */
    private fun isBootstrapReady(): Boolean {
        return File(binDir, "bash").exists() &&
               File(binDir, "apt").exists() &&
               File(usrDir, "lib").isDirectory
    }

    /**
     * Get the bash executable path for spawning processes.
     */
    /** Set a custom home directory (e.g. /sdcard/AMCode) */
    fun setHomeDir(dir: File) {
        _homeDir = dir
        dir.mkdirs()
        Log.i(TAG, "Home directory: ${dir.absolutePath}")
    }

    fun getShell(): String = File(binDir, "bash").absolutePath

    /**
     * Execute a command inside the Termux environment.
     *
     * targetSdk=28 avoids Android 10's W^X restriction that blocks
     * execution of binaries from app private data directory.
     */
    fun execCommand(
        command: String,
        args: List<String> = emptyList(),
        workDir: File = homeDir,
        env: Map<String, String> = emptyMap()
    ): Process {
        val fullEnv = environment.toMutableMap().apply { putAll(env) }
        val processBuilder = ProcessBuilder(listOf(command) + args).apply {
            directory(workDir)
            environment().putAll(fullEnv)
            redirectErrorStream(true)
        }
        return processBuilder.start()
    }

    /**
     * Execute a command via bash -c inside the Termux environment.
     */
    fun execShell(script: String, workDir: File = homeDir): Process {
        return execCommand(getShell(), listOf("-c", script), workDir)
    }

    // ─── Private helpers ───────────────────────────────────────

    private fun createDirectories() {
        listOf(usrDir, binDir, homeDir, tmpDir, etcDir, varDir, aptDir,
            File(binDir, "applets"),
            File(usrDir, "lib"),
            File(usrDir, "share"),
            File(usrDir, "include"),
            File(usrDir, "libexec"),
            File(varDir, "lib/apt/lists/partial"),
            File(varDir, "lib/dpkg"),
            File(varDir, "cache/apt/archives/partial"),
            File(varDir, "log/apt"),
            File(etcDir, "apt/apt.conf.d"),
            File(etcDir, "apt/preferences.d"),
            File(etcDir, "apt/sources.list.d"),
            File(etcDir, "apt/trusted.gpg.d"),
            File(etcDir, "profile.d"),
            File(etcDir, "termux"),
            File(homeDir, ".termux"),
        ).forEach { it.mkdirs() }

        // Create basic passwd/group for the environment
        File(etcDir, "passwd").writeText(
            "root:x:0:0:root:/data/data/${PACKAGE_NAME}/files/home:/data/data/${PACKAGE_NAME}/files/usr/bin/bash\n"
        )
        File(etcDir, "group").writeText(
            "root:x:0:root\n"
        )
        // Only seed DNS on first run: a user-provided resolv.conf (custom
        // resolver, private network) must survive reinstalls and retries.
        val resolv = File(etcDir, "resolv.conf")
        if (!resolv.exists()) {
            resolv.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
        }
    }

    private fun extractBootstrap(zipFile: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                // Termux bootstrap zip maps its root to $PREFIX (usrDir)
                // EXCEPT: home/ maps to filesDir/home/
                val targetFile = when {
                    entry.name.startsWith("home/") ->
                        File(homeDir, entry.name.removePrefix("home/"))
                    entry.name == "home" || entry.name == "home/" ->
                        homeDir
                    else ->
                        File(usrDir, entry.name)
                }

                if (entry.isDirectory) {
                    targetFile.mkdirs()
                } else {
                    targetFile.parentFile?.mkdirs()
                    FileOutputStream(targetFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        Log.i(TAG, "Bootstrap extracted. PREFIX=${usrDir.absolutePath}, HOME=${homeDir.absolutePath}")

        // Create library symlinks — ZIP extraction loses Unix symlinks
        // For libfoo.so.X.Y.Z: create libfoo.so.X.Y, libfoo.so.X, libfoo.so
        val libDir = File(usrDir, "lib")
        if (libDir.isDirectory) {
            libDir.listFiles()?.forEach { file ->
                val name = file.name
                // Match: libfoo.so.X.Y[.Z...] → capture base, X, Y
                val match = Regex("""^(.+)\.so\.(\d+)\.(\d+)(?:\.\d+)*$""").matchEntire(name)
                if (match != null) {
                    val baseName = match.groupValues[1]  // e.g., "libbz2"
                    val major = match.groupValues[2]      // e.g., "1"
                    val minor = match.groupValues[3]      // e.g., "0"
                    // Create all three levels of symlinks
                    val links = listOf(
                        "${baseName}.so.$major.$minor",  // libbz2.so.1.0
                        "${baseName}.so.$major",          // libbz2.so.1
                        "${baseName}.so"                  // libbz2.so
                    )
                    for (linkName in links) {
                        val link = File(libDir, linkName)
                        if (!link.exists() && linkName != name) {
                            try {
                                java.nio.file.Files.createSymbolicLink(link.toPath(), file.toPath())
                            } catch (_: Exception) { }
                        }
                    }
                }
            }
        }

        // Make ALL binaries and scripts executable (required on Android)
        listOf(binDir, File(binDir, "applets"), File(usrDir, "libexec"),
               File(usrDir, "lib/apt/methods")).forEach { dir ->
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        file.setExecutable(true, false)
                        file.setReadable(true, false)
                    }
                }
            }
        }
        // Also make any scripts in bin/ executable (shebang scripts)
        listOf(binDir, File(binDir, "applets")).forEach { dir ->
            if (dir.isDirectory) {
                dir.walkTopDown().filter { it.isFile }.forEach { it.setExecutable(true, false) }
            }
        }
    }

    /**
     * Patch hardcoded paths in termux binaries.
     * All binaries have "/data/data/com.termux/files/usr" baked in.
     * We replace it with "/data/data/com.devbox/files/usr" (same length: 10 chars).
     */
    private fun patchBinaries() {
        val oldPath = "/data/data/com.termux/files/usr".toByteArray()
        val newPath = "/data/data/${PACKAGE_NAME}/files/usr".toByteArray()

        require(oldPath.size == newPath.size) {
            "Path lengths must match! old='${String(oldPath)}'(${oldPath.size}) new='${String(newPath)}'(${newPath.size})"
        }

        val binaryDirs = listOf(
            binDir,
            File(binDir, "applets"),
            File(usrDir, "lib"),
            File(usrDir, "libexec"),
        )

        var patchedCount = 0
        for (dir in binaryDirs) {
            if (!dir.exists()) continue
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.canRead()) {
                    try {
                        val content = file.readBytes()
                        var idx = 0
                        var modified = false
                        val mutableContent = content.copyOf()

                        while (idx <= mutableContent.size - oldPath.size) {
                            var match = true
                            for (j in oldPath.indices) {
                                if (mutableContent[idx + j] != oldPath[j]) {
                                    match = false
                                    break
                                }
                            }
                            if (match) {
                                for (j in newPath.indices) {
                                    mutableContent[idx + j] = newPath[j]
                                }
                                modified = true
                                idx += oldPath.size
                            } else {
                                idx++
                            }
                        }

                        if (modified) {
                            file.writeBytes(mutableContent)
                            patchedCount++
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot patch ${file.name}: ${e.message}")
                    }
                }
            }
        }
        Log.i(TAG, "Patched $patchedCount binaries with new prefix")

        // Also patch text files (scripts, configs, etc.)
        val oldTextPath = "/data/data/com.termux/files/usr"
        val newTextPath = "/data/data/${PACKAGE_NAME}/files/usr"

        val textDirs = listOf(binDir, etcDir, File(binDir, "applets"))
        for (dir in textDirs) {
            if (!dir.exists()) continue
            dir.walkTopDown()
                .filter { it.isFile && !it.absolutePath.contains("/termux/") }
                .forEach { file ->
                try {
                    val content = file.readText()
                    if (oldTextPath in content) {
                        file.writeText(content.replace(oldTextPath, newTextPath))
                    }
                } catch (_: Exception) { }
            }
        }
    }

    private fun setupAptSources() {
        // Termux package repository sources
        // Test mirrors and pick the fastest
        val mirrors = listOf(
            "Tsinghua" to "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main",
            "USTC" to "https://mirrors.ustc.edu.cn/termux/apt/termux-main",
            "Official" to "https://packages.termux.dev/apt/termux-main"
        )
        var bestMirror = mirrors.first()
        var bestTime = Long.MAX_VALUE
        val curlBin = File(binDir, "curl").absolutePath

        for ((name, url) in mirrors) {
            try {
                val start = System.currentTimeMillis()
                // `%{http_code}` prints the status on stdout and the exit code
                // is curl's own, so a mirror that fails DNS immediately can no
                // longer win the speed test by being "fastest".
                val proc = execShell(
                    "$curlBin -s -o /dev/null -w '%{http_code}' " +
                        "--connect-timeout 5 --max-time 10 $url/dists/stable/Release",
                    workDir = usrDir
                )
                val status = proc.inputStream.bufferedReader().use { it.readText().trim() }
                proc.waitFor()
                val elapsed = System.currentTimeMillis() - start
                if (proc.exitValue() == 0 && status == "200" && elapsed < bestTime) {
                    bestTime = elapsed
                    bestMirror = name to url
                }
            } catch (_: Exception) { }
        }

        val sourcesList = File(aptDir, "sources.list")
        sourcesList.writeText(
            "deb [trusted=yes] ${bestMirror.second} stable main\n"
        )
        emitOutput("[setup] apt mirror: ${bestMirror.first} (${bestTime}ms)\n")

        // apt configuration for Termux environment
        val aptConfDir = File(etcDir, "apt/apt.conf.d")
        File(aptConfDir, "99-termux").writeText(
            """
            APT::Install-Recommends "false";
            APT::Install-Suggests "false";
            Acquire::Languages "none";
            Dir "${usrDir.absolutePath}";
            Dir::State "${varDir.absolutePath}/lib/apt";
            Dir::State::status "${varDir.absolutePath}/lib/dpkg/status";
            Dir::Cache "${varDir.absolutePath}/cache/apt";
            Dir::Log "${varDir.absolutePath}/log/apt";
            Dir::Etc "${etcDir.absolutePath}/apt";
            Dir::Bin::methods "${usrDir.absolutePath}/lib/apt/methods";
            """.trimIndent()
        )

        // Create dpkg status file
        File(varDir, "lib/dpkg/status").apply {
            parentFile?.mkdirs()
            if (!exists()) createNewFile()
        }
    }

    private fun createSymlinks() {
        // Create https→http symlink for apt
        val httpMethod = File(usrDir, "lib/apt/methods/http")
        val httpsLink = File(usrDir, "lib/apt/methods/https")
        if (httpMethod.exists() && !httpsLink.exists()) {
            try { java.nio.file.Files.createSymbolicLink(httpsLink.toPath(), httpMethod.toPath()) } catch (_: Exception) { }
        }

        // Create essential symlinks for dpkg maintainer scripts
        // dash → sh (dpkg scripts use #!/bin/sh)
        val dash = File(binDir, "dash")
        val sh = File(binDir, "sh")
        if (dash.exists() && !sh.exists()) {
            try { java.nio.file.Files.createSymbolicLink(sh.toPath(), dash.toPath()) } catch (_: Exception) {}
        }

        // coreutils applets go DIRECTLY in bin/ (dpkg chroot needs them there)
        val coreutils = File(binDir, "coreutils")
        if (coreutils.exists()) {
            val applets = listOf("rm", "cp", "mv", "ln", "mkdir", "rmdir", "chmod", "chown",
                                 "cat", "dd", "du", "df", "echo", "head", "tail", "touch",
                                 "sort", "uniq", "wc", "tr", "cut", "paste", "join",
                                 "basename", "dirname", "realpath", "readlink",
                                 "stat", "env", "nice", "nohup", "timeout", "sleep",
                                 "printf", "seq", "test", "true", "false", "yes")
            for (name in applets) {
                val link = File(binDir, name)
                if (!link.exists()) {
                    try { java.nio.file.Files.createSymbolicLink(link.toPath(), coreutils.toPath()) } catch (_: Exception) {}
                }
            }
            emitOutput("[setup] Created coreutils symlinks in bin/\n")
        }
    }

    /**
     * Idempotently repair the package-manager layer.
     *
     * Works both for a fresh install (after the bootstrap has been extracted)
     * and for an APK installed over an existing data directory. It:
     *  - installs the bundled Termux/TUR GPG keys into etc/apt/trusted.gpg.d
     *    (replacing the broken absolute com.termux symlinks),
     *  - recreates bootstrap symlinks from SYMLINKS.txt, including
     *    lib/libtermux-exec.so and the applet links,
     *  - fixes the cache paths hardcoded in bin/pkg,
     *  - points apt's Dir::Bin::dpkg at a wrapper that rewrites Termux .deb
     *    prefixes to com.devbox before invoking the real dpkg,
     *  - switches the TUR source to signed once its key is available,
     *  - migrates dpkg database paths left by the initial bootstrap install
     *    from com.termux to com.devbox.
     *
     * The actual work lives in app/src/main/assets/amcode-pkg-setup.sh so it can
     * be tested in a real Termux prefix. This method only syncs the assets and
     * streams the script log to Logcat.
     *
     * The repair takes several seconds (the SYMLINKS.txt scan and the dpkg
     * database walk dominate), so a healthy install is detected with a small
     * completion marker instead. The marker key contains the APK versionCode,
     * a hash of the three setup assets and the dpkg/termux-tools/termux-keyring
     * versions; on top of that a handful of cheap file checks (keys, wrapper,
     * pkg cache, .amcode-ready) must pass. Any mismatch/corruption logs
     * "ensurePkgSetup full repair" and runs the script once.
     */
    private fun ensurePkgSetup(context: Context): Boolean {
        val startedAt = System.currentTimeMillis()
        val fingerprint = currentPkgSetupFingerprint(context)
        var repairReason: String? = if (fingerprint == null) {
            "cannot read pkg-setup assets"
        } else {
            describePkgSetupMarkerMismatch(fingerprint, readPkgSetupMarker())
        }
        if (fingerprint != null && repairReason == null) {
            val unhealthy = pkgSetupHealthFailure(context, requireAmcodeReady = true)
            if (unhealthy == null) {
                Log.i(TAG, "ensurePkgSetup fast path: marker valid " +
                    "(versionCode=${fingerprint.versionCode} " +
                    "setup=${fingerprint.setupHash.take(12)} " +
                    "dpkg=${fingerprint.dpkgVersion} " +
                    "termux-tools=${fingerprint.termuxToolsVersion}) in " +
                    "${System.currentTimeMillis() - startedAt}ms")
                return true
            }
            repairReason = unhealthy
        }
        Log.i(TAG, "ensurePkgSetup full repair: $repairReason")
        // Do not trust a marker while the repair is in progress or if it fails.
        File(filesDir, PKG_SETUP_MARKER_FILE).delete()

        return try {
            val setupDir = File(usrDir, "libexec/amcode")
            setupDir.mkdirs()

            val assets = listOf(
                "amcode-dpkg-wrapper.sh" to File(setupDir, "dpkg"),
                "amcode-pkg-setup.sh" to File(setupDir, "pkg-setup.sh"),
                "tur.gpg" to File(setupDir, "tur.gpg")
            )
            var copied = 0
            for ((assetName, dest) in assets) {
                if (copyAsset(context, assetName, dest)) copied++
            }
            File(setupDir, "dpkg").setExecutable(true, false)
            File(setupDir, "pkg-setup.sh").setExecutable(true, false)
            File(setupDir, "dpkg").setReadable(true, false)
            File(setupDir, "pkg-setup.sh").setReadable(true, false)

            val bash = File(binDir, "bash")
            val setupScript = File(setupDir, "pkg-setup.sh")
            if (!bash.exists() || !setupScript.exists()) {
                Log.w(TAG, "ensurePkgSetup skipped (bash=$bash exists=${bash.exists()})")
                return false
            }

            val process = execCommand(
                bash.absolutePath,
                listOf(setupScript.absolutePath),
                usrDir
            )
            var warnings = 0
            val reader = process.inputStream.bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val text = line!!
                if (text.isBlank()) continue
                Log.i(TAG, text)
                if (text.contains("WARN")) {
                    warnings++
                    // Surface real problems in the setup UI, but keep the
                    // normal existing-install launch path quiet.
                    if (_setupState.value.state != State.READY) emitOutput("$text\n")
                }
            }
            val exitCode = process.waitFor()
            Log.i(TAG, "ensurePkgSetup done: exit=$exitCode assets=$copied warnings=$warnings")
            if (exitCode != 0) {
                Log.w(TAG, "ensurePkgSetup full repair failed: exit=$exitCode; marker not written")
                return false
            }

            // Finish packages left half-configured by an interrupted/failed
            // install. No-op (and fast) when the database is already clean;
            // the health check below only passes once the audit is clean.
            if (!finishDpkgConfigure()) {
                Log.w(TAG, "ensurePkgSetup: dpkg --configure -a did not fully succeed")
            }

            // The bundled bootstrap pkg still has the Termux cache path while
            // a pkg upgraded through the wrapper has the com.devbox form; the
            // shell repair only handles the latter.
            if (repairPkgCachePath()) {
                Log.i(TAG, "ensurePkgSetup repaired the bin/pkg cache path")
            }

            // Also catch the slash-less md5sums entries the shell repair does
            // not rewrite (it only matches "/data/data/com.termux").
            val migrated = migrateDpkgPathsToDevbox()
            Log.i(TAG, "ensurePkgSetup db paths migrated: files=$migrated")

            val fresh = currentPkgSetupFingerprint(context)
            val unhealthy = pkgSetupHealthFailure(context, requireAmcodeReady = false)
            if (fresh == null || unhealthy != null) {
                Log.w(TAG, "ensurePkgSetup full repair incomplete: " +
                    (unhealthy ?: "cannot fingerprint setup assets") +
                    "; marker not written")
                false
            } else {
                writePkgSetupMarker(fresh)
                Log.i(TAG, "ensurePkgSetup full repair done in " +
                    "${System.currentTimeMillis() - startedAt}ms " +
                    "(exit=$exitCode assets=$copied warnings=$warnings); marker written " +
                    "(versionCode=${fresh.versionCode} " +
                    "setup=${fresh.setupHash.take(12)} " +
                    "dpkg=${fresh.dpkgVersion} " +
                    "termux-tools=${fresh.termuxToolsVersion})")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensurePkgSetup failed", e)
            false
        }
    }

    private data class PkgSetupFingerprint(
        val revision: Int,
        val versionCode: Long,
        val setupHash: String,
        val dpkgVersion: String,
        val termuxToolsVersion: String,
        val keyringVersion: String,
        val pkgSize: Long,
        val pkgMtime: Long
    )

    private fun currentPkgSetupFingerprint(context: Context): PkgSetupFingerprint? {
        val setupHash = setupAssetsHash(context) ?: return null
        val versions = installedPackageVersions()
        val pkg = File(binDir, "pkg")
        return PkgSetupFingerprint(
            revision = PKG_SETUP_REVISION,
            versionCode = appVersionCode(context),
            setupHash = setupHash,
            dpkgVersion = versions["dpkg"].orEmpty(),
            termuxToolsVersion = versions["termux-tools"].orEmpty(),
            keyringVersion = versions["termux-keyring"].orEmpty(),
            pkgSize = pkg.length(),
            pkgMtime = pkg.lastModified()
        )
    }

    private fun appVersionCode(context: Context): Long {
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read APK versionCode: ${e.message}")
            -1L
        }
    }

    /** Stable SHA-256 over the three setup assets, including their names. */
    private fun setupAssetsHash(context: Context): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            for (assetName in PKG_SETUP_ASSETS) {
                digest.update(assetName.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
                context.assets.open(assetName).use { input ->
                    val buffer = ByteArray(8192)
                    var count: Int
                    while (input.read(buffer).also { count = it } != -1) {
                        digest.update(buffer, 0, count)
                    }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot hash pkg-setup assets: ${e.message}")
            null
        }
    }

    /**
     * bin/pkg looks for its .deb cache under a hardcoded path. The bootstrap
     * copy still has the Termux prefix, while an upgraded pkg (rewritten by
     * the dpkg wrapper) has com.devbox/cache/apt; both must point at
     * $PREFIX/var/cache/apt like apt does.
     */
    private fun repairPkgCachePath(): Boolean {
        val pkg = File(binDir, "pkg")
        if (!pkg.isFile) return false
        return try {
            val original = pkg.readText()
            val fixed = original
                .replace(
                    "/data/data/com.termux/cache/apt",
                    "/data/data/com.devbox/files/usr/var/cache/apt"
                )
                .replace(
                    "/data/data/com.devbox/cache/apt",
                    "/data/data/com.devbox/files/usr/var/cache/apt"
                )
            if (fixed == original) {
                false
            } else {
                pkg.writeText(fixed)
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot repair bin/pkg cache path: ${e.message}")
            false
        }
    }

    private fun readPkgSetupMarker(): PkgSetupFingerprint? {
        val marker = File(filesDir, PKG_SETUP_MARKER_FILE)
        if (!marker.isFile) return null
        return try {
            val json = JSONObject(marker.readText())
            if (json.optInt("schema", 0) != PKG_SETUP_MARKER_SCHEMA) return null
            PkgSetupFingerprint(
                revision = json.optInt("revision", -1),
                versionCode = json.optLong("versionCode", -1L),
                setupHash = json.optString("setupHash"),
                dpkgVersion = json.optString("dpkgVersion"),
                termuxToolsVersion = json.optString("termuxToolsVersion"),
                keyringVersion = json.optString("keyringVersion"),
                pkgSize = json.optLong("pkgSize", -1L),
                pkgMtime = json.optLong("pkgMtime", -1L)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read pkg-setup marker: ${e.message}")
            null
        }
    }

    private fun writePkgSetupMarker(fingerprint: PkgSetupFingerprint) {
        val marker = File(filesDir, PKG_SETUP_MARKER_FILE)
        val tmp = File(filesDir, "$PKG_SETUP_MARKER_FILE.tmp")
        try {
            val json = JSONObject()
                .put("schema", PKG_SETUP_MARKER_SCHEMA)
                .put("revision", fingerprint.revision)
                .put("versionCode", fingerprint.versionCode)
                .put("setupHash", fingerprint.setupHash)
                .put("dpkgVersion", fingerprint.dpkgVersion)
                .put("termuxToolsVersion", fingerprint.termuxToolsVersion)
                .put("keyringVersion", fingerprint.keyringVersion)
                .put("pkgSize", fingerprint.pkgSize)
                .put("pkgMtime", fingerprint.pkgMtime)
                .put("writtenAt", System.currentTimeMillis())
            tmp.writeText(json.toString())
            tmp.setReadable(true, true)
            if (!tmp.renameTo(marker)) {
                marker.delete()
                if (!tmp.renameTo(marker)) {
                    tmp.delete()
                    Log.w(TAG, "Cannot install pkg-setup marker at $marker")
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            Log.w(TAG, "Cannot write pkg-setup marker: ${e.message}")
        }
    }

    private fun describePkgSetupMarkerMismatch(
        current: PkgSetupFingerprint,
        marker: PkgSetupFingerprint?
    ): String? {
        if (marker == null) return "no completion marker"
        if (marker.revision != current.revision) {
            return "setup revision ${marker.revision} -> ${current.revision}"
        }
        if (marker.versionCode != current.versionCode) {
            return "APK versionCode ${marker.versionCode} -> ${current.versionCode}"
        }
        if (marker.setupHash != current.setupHash) return "pkg-setup assets changed"
        if (marker.dpkgVersion != current.dpkgVersion) {
            return "dpkg ${marker.dpkgVersion} -> ${current.dpkgVersion}"
        }
        if (marker.termuxToolsVersion != current.termuxToolsVersion) {
            return "termux-tools ${marker.termuxToolsVersion} -> ${current.termuxToolsVersion}"
        }
        if (marker.keyringVersion != current.keyringVersion) {
            return "termux-keyring ${marker.keyringVersion} -> ${current.keyringVersion}"
        }
        if (marker.pkgSize != current.pkgSize) return "bin/pkg size changed"
        if (marker.pkgMtime != current.pkgMtime) return "bin/pkg was modified"
        return null
    }

    /** Returns version strings for the packages whose upgrades invalidate the marker. */
    private fun installedPackageVersions(): Map<String, String> {
        val wanted = setOf("dpkg", "termux-tools", "termux-keyring")
        val versions = mutableMapOf<String, String>()
        val status = File(varDir, "lib/dpkg/status")
        if (!status.isFile) return versions
        return try {
            var pkg = ""
            status.forEachLine { line ->
                when {
                    line.startsWith("Package: ") ->
                        pkg = line.substringAfter("Package: ").trim()
                    line.startsWith("Version: ") && pkg in wanted ->
                        versions[pkg] = line.substringAfter("Version: ").trim()
                }
            }
            versions
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read dpkg package versions: ${e.message}")
            versions
        }
    }

    /**
     * Cheap correctness checks for the fast path. Returns null when healthy,
     * otherwise a human-readable reason for the full repair.
     */
    private fun pkgSetupHealthFailure(context: Context, requireAmcodeReady: Boolean): String? {
        val wrapper = File(usrDir, "libexec/amcode/dpkg")
        val setupScript = File(usrDir, "libexec/amcode/pkg-setup.sh")
        val turKey = File(usrDir, "libexec/amcode/tur.gpg")
        val aptConf = File(etcDir, "apt/apt.conf.d/99-amcode-dpkg")
        val mainKey = File(etcDir, "apt/trusted.gpg.d/termux-autobuilds.gpg")
        val turKeyring = File(etcDir, "apt/trusted.gpg.d/tur.gpg")
        val status = File(varDir, "lib/dpkg/status")
        val required = linkedMapOf(
            "bash" to File(binDir, "bash"),
            "apt" to File(binDir, "apt"),
            "dpkg" to File(binDir, "dpkg"),
            "dpkg-deb" to File(binDir, "dpkg-deb"),
            "pkg" to File(binDir, "pkg"),
            "libtermux-exec.so" to File(usrDir, "lib/libtermux-exec.so"),
            "dpkg wrapper" to wrapper,
            "pkg setup script" to setupScript,
            "TUR key" to turKey,
            "termux-autobuilds key" to mainKey,
            "TUR keyring" to turKeyring,
            "dpkg wrapper apt config" to aptConf,
            "dpkg status" to status
        )
        for ((label, file) in required) {
            if (!file.exists()) return "missing $label (${file.absolutePath})"
        }
        if (!wrapper.canExecute()) return "dpkg wrapper is not executable"
        // Bootstrap applet symlinks used by the wrapper, apt and install
        // scripts. The full repair recreates these from SYMLINKS.txt.
        for (name in listOf(
            "sh", "rm", "cp", "mv", "mkdir", "chmod", "stat", "readlink"
        )) {
            if (!File(binDir, name).exists()) return "missing bin/$name symlink"
        }
        if (mainKey.length() == 0L) return "termux-autobuilds key is empty"
        if (turKeyring.length() == 0L) return "TUR keyring is empty"
        if (status.length() == 0L) return "empty dpkg status"
        if (!File(varDir, "cache/apt/archives").isDirectory) {
            return "missing apt archive cache"
        }
        if (!File(varDir, "lib/dpkg/info").isDirectory) return "missing dpkg info directory"
        if (requireAmcodeReady && !File(filesDir, ".amcode-ready").isFile) {
            return "missing .amcode-ready completion marker"
        }
        return try {
            val statusText = status.readText()
            val pkgText = File(binDir, "pkg").readText()
            val aptConfText = aptConf.readText()
            when {
                statusText.contains("/data/data/com.termux") ->
                    "dpkg status still references /data/data/com.termux"
                statusText.contains("Status: install ok unpacked") ||
                    statusText.contains("half-configured") ||
                    statusText.contains("half-installed") ->
                    "dpkg status has unconfigured packages (pending --configure -a)"
                pkgText.contains("/data/data/com.devbox/cache/apt") ||
                    pkgText.contains("/data/data/com.termux/cache/apt") ->
                    "bin/pkg still uses the broken cache path"
                !aptConfText.contains("Dir::Bin::dpkg") ||
                    !aptConfText.contains(wrapper.absolutePath) ->
                    "apt is not configured to use the dpkg wrapper"
                !installedFileMatchesAsset(context, "amcode-dpkg-wrapper.sh", wrapper) ->
                    "dpkg wrapper does not match the bundled asset"
                !installedFileMatchesAsset(context, "amcode-pkg-setup.sh", setupScript) ->
                    "pkg setup script does not match the bundled asset"
                !installedFileMatchesAsset(context, "tur.gpg", turKey) ->
                    "TUR key does not match the bundled asset"
                !installedFileMatchesAsset(context, "tur.gpg", turKeyring) ->
                    "installed TUR keyring does not match the bundled key"
                else -> null
            }
        } catch (e: Exception) {
            "cannot validate package-manager files: ${e.message}"
        }
    }

    private fun installedFileMatchesAsset(
        context: Context,
        assetName: String,
        installed: File
    ): Boolean {
        return try {
            val assetBytes = context.assets.open(assetName).use { it.readBytes() }
            val installedBytes = installed.readBytes()
            val digest = MessageDigest.getInstance("SHA-256")
            MessageDigest.isEqual(digest.digest(assetBytes), digest.digest(installedBytes))
        } catch (e: Exception) {
            Log.w(TAG, "Cannot compare $assetName with $installed: ${e.message}")
            false
        }
    }

    private fun copyAsset(context: Context, assetName: String, dest: File): Boolean {
        return try {
            dest.parentFile?.mkdirs()
            // Write to a sibling temp file first: a process kill mid-copy must
            // not leave a truncated dpkg wrapper/setup script behind.
            val tmp = File(dest.parentFile, "${dest.name}.amcode-tmp")
            context.assets.open(assetName).use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            tmp.setReadable(true, false)
            if (!tmp.renameTo(dest)) {
                dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.delete()
                    Log.w(TAG, "Could not install asset $assetName at $dest")
                    return false
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy asset $assetName: ${e.message}")
            false
        }
    }

    /**
     * The TUR code-server debs contain native modules built against libc++ 30.
     * Bootstrap/old installs can still have libc++ 29, which lacks
     * `_ZNSt6__ndk113__hash_memoryEPKvm` and makes spdlog/@parcel/watcher fail.
     * Check the actual shared library (not just the dpkg version) and upgrade
     * from the fast main mirror if needed. Idempotent and safe on every launch.
     */
    private fun ensureLibCpp30(): Boolean {
        // The full-file symbol scan is pure setup cost once the check has
        // passed; a tiny marker file caches the positive result.
        val marker = File(filesDir, ".amcode-libcpp30")
        val lib = File(usrDir, "lib/libc++_shared.so")
        if (marker.isFile && lib.isFile) return true
        if (libcxxHasHashSymbol(lib)) {
            marker.writeText("1")
            Log.i(TAG, "libc++_shared.so already has the libc++ 30 hash_memory symbol")
            return true
        }
        if (!lib.exists()) {
            emitOutput("[fix] libc++_shared.so missing; installing libc++...\n")
        } else {
            emitOutput("[fix] libc++ is older than 30 (missing hash_memory symbol); upgrading...\n")
        }
        if (!File(binDir, "apt").exists()) {
            emitOutput("[fix] WARN apt is missing; cannot install libc++ 30\n")
            return false
        }
        _setupState.value = SetupProgress(
            State.INSTALLING_PACKAGES,
            "Updating libc++ for code-server...",
            92
        )
        var installed = try {
            runApt("install", "-y", "libc++", quick = true).exitCode == 0
        } catch (e: Exception) {
            Log.w(TAG, "apt install libc++ failed to start: ${e.message}")
            false
        }
        if (!installed) {
            // A cleaned/empty apt list is common after pkg maintenance. Refresh
            // once (quick timeouts) and retry before giving up.
            emitOutput("[fix] apt install libc++ failed; refreshing package index...\n")
            try {
                runApt("update", quick = true)
            } catch (e: Exception) {
                Log.w(TAG, "apt update for libc++ failed to start: ${e.message}")
            }
            installed = try {
                runApt("install", "-y", "libc++", quick = true).exitCode == 0
            } catch (e: Exception) {
                Log.w(TAG, "apt install libc++ retry failed to start: ${e.message}")
                false
            }
        }
        if (!installed) {
            emitOutput("[fix] WARN could not install libc++: " +
                lastInstallError.ifBlank { "check the main mirror" } + "\n")
            return false
        }
        if (libcxxHasHashSymbol(lib)) {
            marker.writeText("1")
            emitOutput("[fix] libc++ 30 installed.\n")
            return true
        }
        // A stale/odd dpkg database may think 29 already satisfies the
        // candidate; force the main-repo 30 package to be reinstalled.
        emitOutput("[fix] libc++ still lacks the symbol; forcing reinstall...\n")
        try {
            runApt("install", "-y", "--reinstall", "libc++", quick = true)
        } catch (e: Exception) {
            Log.w(TAG, "apt reinstall libc++ failed to start: ${e.message}")
        }
        if (libcxxHasHashSymbol(lib)) {
            marker.writeText("1")
            emitOutput("[fix] libc++ 30 installed (forced).\n")
            return true
        }
        emitOutput("[fix] WARN libc++ 30 symbol still missing at ${lib.absolutePath}\n")
        return false
    }

    private fun libcxxHasHashSymbol(file: File): Boolean {
        if (!file.isFile) return false
        val needle = LIBCPP_HASH_SYMBOL.toByteArray(Charsets.US_ASCII)
        return try {
            val bytes = file.readBytes()
            if (bytes.size < needle.size) return false
            outer@ for (i in 0..(bytes.size - needle.size)) {
                for (j in needle.indices) {
                    if (bytes[i + j] != needle[j]) continue@outer
                }
                return true
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read ${file.absolutePath}: ${e.message}")
            false
        }
    }

    /**
     * Install nodejs and code-server.
     *
     * Two paths are supported:
     *  1. Optional build-time TUR bundle (`-PofflineBundle=true`): the pinned
     *     code-server + nodejs-24 debs are in APK assets. Only the small,
     *     fast-moving dependencies come from the main Termux mirror. No TUR
     *     download is needed on first launch.
     *  2. Online: apt resolves the TUR dependency graph, then the two large
     *     TUR debs are fetched with parallel HTTP Range requests + resume
     *     instead of apt's single slow connection. If that fails, apt's own
     *     downloader is used as a fallback; its error output is no longer
     *     swallowed.
     *
     * Uses dpkg directly for reliable installation.
     */
    fun installPackages(): Boolean = tryInstall(0)

    private fun tryInstall(attempt: Int): Boolean {
        if (!isBootstrapReady()) return false
        val retrying = attempt > 0
        lastInstallError = ""

        // 1. Try the opt-in bundle once. It only exists when the APK was built
        //    with -PofflineBundle=true and for this device ABI.
        if (!offlineBundleChecked) {
            offlineBundleChecked = true
            val bundled = tryOfflineBundleInstall()
            if (bundled == true) {
                emitOutput("[done] All packages installed from bundled debs.\n")
                _setupState.value = SetupProgress(State.READY, "Ready", 100)
                return true
            }
            if (bundled == false) {
                lastInstallError = ""
                emitOutput("[offline] Bundled install failed; falling back to online TUR install.\n")
            }
        }

        _setupState.value = SetupProgress(State.INSTALLING_PACKAGES,
            if (retrying) "Retrying..." else "Updating package index...",
            if (retrying) 30 else 32)
        if (!runAptCommand("update")) {
            lastInstallError = lastInstallError.ifBlank { "apt update (main mirror) failed" }
            emitOutput("[apt] Main mirror index update failed; continuing with existing index.\n")
        }

        // Configure TUR only for the online path. The bundled path keeps TUR
        // out of apt update so first install never touches the slow host.
        ensureTurSource()
        emitOutput(if (retrying) "[apt] Retrying TUR package index...\n" else "[apt] Updating TUR package index...\n")
        if (!runAptCommand("update")) {
            lastInstallError = lastInstallError.ifBlank { "apt update (TUR) failed" }
            emitOutput("[apt] TUR index update failed. The TUR host is slow/blocked on this network.\n")
            return false
        }

        _setupState.value = SetupProgress(State.INSTALLING_PACKAGES, "Downloading packages...", 38)
        // Install GPG keyring first so pkg update works in terminal.
        if (!runAptCommand("install", "-y", "-d", "termux-keyring")) {
            emitOutput("[apt] termux-keyring download failed; continuing (setup will retry later).\n")
        }

        // Resolve package URLs without downloading, then grab the big TUR debs
        // with multiple HTTP Range connections. At ~31 KB/s per connection
        // this is the difference between ~75 minutes and a handful of minutes.
        _setupState.value = SetupProgress(State.INSTALLING_PACKAGES, "Resolving TUR download URLs...", 42)
        val specs = resolveDownloadUris("code-server")
        var downloaded = false
        if (specs.isNotEmpty()) {
            emitOutput("[net] Downloading ${specs.size} package(s) with HTTP Range" +
                " (up to $MAX_RANGE_CONNECTIONS connections)...\n")
            downloaded = downloadDebs(specs)
        } else {
            emitOutput("[net] Could not resolve individual .deb URLs; using apt downloader.\n")
        }
        if (!downloaded) {
            if (specs.isNotEmpty()) {
                emitOutput("[net] Parallel download failed; falling back to apt (single connection, may be slow).\n")
            }
            _setupState.value = SetupProgress(State.INSTALLING_PACKAGES,
                "Downloading from TUR (slow link)...", 48)
            if (!runAptCommand("install", "-y", "-d", "code-server")) {
                lastInstallError = lastInstallError.ifBlank { "TUR download failed" }
                emitOutput("[apt] code-server download failed: $lastInstallError\n")
                return false
            }
            // apt completed the download; the parallel .partN files are now
            // redundant. Keep them on failure so the next retry can resume.
            cleanupPartialDownloads(specs)
        }

        // Install via dpkg. Dependencies are listed before nodejs/code-server
        // so dpkg configures them in the right order.
        _setupState.value = SetupProgress(State.INSTALLING_PACKAGES, "Extracting packages...", 72)
        if (!installDownloadedDebs(collectArchiveDebs())) {
            return false
        }

        val ok = isNodeInstalled() && isCodeServerInstalled()
        if (ok) {
            emitOutput("[done] All packages installed.\n")
            _setupState.value = SetupProgress(State.READY, "Ready", 100)
            return true
        }
        lastInstallError = if (lastInstallError.isBlank()) {
            "code-server or nodejs binary is missing after dpkg"
        } else {
            "code-server or nodejs binary is missing after dpkg (${lastInstallError})"
        }
        if (attempt < 1) return tryInstall(attempt + 1)
        return false
    }

    /** Write the TUR source in the form that works before its keyring is usable. */
    private fun ensureTurSource() {
        val turListDir = File(etcDir, "apt/sources.list.d")
        turListDir.mkdirs()
        File(turListDir, "tur.list").writeText(TUR_SOURCE)
    }

    // ─── Optional build-time TUR bundle ───────────────────────

    private data class OfflinePackage(
        val name: String,
        val version: String,
        val filename: String,
        val size: Long,
        val sha256: String
    )

    private data class OfflineBundle(
        val arch: String,
        val aptDependencies: List<String>,
        val packages: List<OfflinePackage>
    )

    private fun offlineArchName(): String {
        val primary = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when {
            primary.startsWith("x86_64") -> "x86_64"
            primary.startsWith("x86") -> "i686"
            else -> "aarch64"
        }
    }

    /** Remove the staged bundled .deb copies after a failed bundle install. */
    private fun deleteStagedBundledDebs(bundle: OfflineBundle) {
        val stageDir = File(varDir, "cache/apt/archives")
        bundle.packages.forEach { File(stageDir, it.filename).delete() }
    }

    /**
     * Install the pinned TUR debs that were bundled at build time.
     *
     * Returns null when this APK has no bundle for this ABI, false when a
     * bundle exists but could not be installed (caller then uses the online
     * path), true on success.
     *
     * The bundle deliberately pins only code-server and nodejs-24: both are
     * TUR-only and account for ~127 of the ~145 MB first install. git is not
     * pinned but is added to the dependency list by the build script, so the
     * bundled first launch also pulls it (with its deps) from the fast main
     * mirror and the offline APK works as a self-sufficient dev environment.
     */
    private fun tryOfflineBundleInstall(): Boolean? {
        val bundle = readOfflineBundle() ?: return null
        if (!isBootstrapReady()) return null

        val bundledSize = bundle.packages.sumOf { it.size }
        emitOutput("[offline] Using bundled TUR packages (${bundledSize / (1024 * 1024)} MB): " +
            bundle.packages.joinToString { "${it.name} ${it.version}" } + "\n")

        val stageDir = File(varDir, "cache/apt/archives")
        stageDir.mkdirs()
        for ((index, pkg) in bundle.packages.withIndex()) {
            val dest = File(stageDir, pkg.filename)
            if (!offlineFileValid(dest, pkg)) {
                try {
                    copyOfflineAsset("offline-debs/${bundle.arch}/${pkg.filename}", dest, pkg)
                } catch (e: Exception) {
                    lastInstallError = "bundled ${pkg.name}: ${e.message}"
                    emitOutput("[offline] ${lastInstallError}\n")
                    return false
                }
            }
            _setupState.value = SetupProgress(
                State.INSTALLING_PACKAGES,
                "Verifying bundled ${pkg.name}...",
                30 + ((index + 1) * 8 / bundle.packages.size)
            )
        }

        // apt must see only the main repository while it fetches dependencies:
        // otherwise it may choose the slow TUR source for a dependency. Move
        // the TUR list aside and restore it in finally.
        val turList = File(etcDir, "apt/sources.list.d/tur.list")
        val turBackup = File(turList.parentFile, "tur.list.amcode-bundle-bak")
        if (!turList.exists() && turBackup.exists()) {
            turBackup.renameTo(turList)
        }
        var moved = false
        try {
            if (turList.exists()) {
                turBackup.delete()
                moved = turList.renameTo(turBackup)
                if (!moved) {
                    lastInstallError = "cannot temporarily disable the TUR apt source"
                    emitOutput("[offline] $lastInstallError; falling back to online install.\n")
                    deleteStagedBundledDebs(bundle)
                    return false
                }
            }
            // Drop cached TUR indexes too: the bundled path must resolve the
            // small dependencies from the main mirror only.
            File(varDir, "lib/apt/lists").listFiles { f ->
                f.name.contains("tur.kcubeterm.com")
            }?.forEach { it.delete() }
            if (!runAptCommand("update")) {
                lastInstallError = "apt update (main mirror) failed during bundled install"
                emitOutput("[offline] Main mirror update failed; falling back to online install.\n")
                deleteStagedBundledDebs(bundle)
                return false
            }
            _setupState.value = SetupProgress(
                State.INSTALLING_PACKAGES,
                "Downloading dependencies from main mirror...",
                42
            )
            val deps = bundle.aptDependencies.toTypedArray()
            if (!runAptCommand("install", "-y", "-d", *deps)) {
                lastInstallError = "dependency download from main mirror failed"
                emitOutput("[offline] Dependency download failed; falling back to online install.\n")
                deleteStagedBundledDebs(bundle)
                return false
            }
        } catch (e: Exception) {
            lastInstallError = "bundled dependency setup: ${e.message}"
            emitOutput("[offline] ${lastInstallError}\n")
            deleteStagedBundledDebs(bundle)
            return false
        } finally {
            if (moved) {
                if (!turList.exists()) turBackup.renameTo(turList)
                else turBackup.delete()
            } else if (turBackup.exists() && !turList.exists()) {
                turBackup.renameTo(turList)
            }
        }

        // Keep TUR available for pkg/extension installs after first launch.
        ensureTurSource()
        turBackup.delete()

        // Drop any older code-server/nodejs deb left by a failed online
        // attempt; otherwise dpkg would be asked to install two versions.
        val expectedDebs = bundle.packages.map { it.filename }.toSet()
        stageDir.listFiles { f ->
            f.isFile && (f.name.startsWith("code-server_") || f.name.startsWith("nodejs-24_")) &&
                f.name !in expectedDebs
        }?.forEach { it.delete() }

        val depDebs = stageDir.listFiles { f ->
            f.isFile && f.extension == "deb" && f.length() > 0 &&
                !f.name.startsWith("code-server_") && !f.name.startsWith("nodejs-24_")
        }?.sortedBy { it.name } ?: emptyList()
        val bundledDebs = bundle.packages.map { File(stageDir, it.filename) }
        if (bundledDebs.any { !it.isFile || it.length() == 0L }) {
            lastInstallError = "bundled .deb missing from apt cache"
            deleteStagedBundledDebs(bundle)
            return false
        }

        _setupState.value = SetupProgress(State.INSTALLING_PACKAGES, "Installing bundled packages...", 60)
        if (!installDebFiles(depDebs + bundledDebs)) {
            deleteStagedBundledDebs(bundle)
            return false
        }
        if (!isNodeInstalled() || !isCodeServerInstalled()) {
            lastInstallError = "bundled install finished but code-server/nodejs binary is missing"
            emitOutput("[offline] $lastInstallError\n")
            deleteStagedBundledDebs(bundle)
            return false
        }
        // The two large debs are still in the APK; do not keep a second
        // ~127 MB copy in the apt cache after a verified install.
        bundle.packages.forEach { File(stageDir, it.filename).delete() }
        emitOutput("[offline] Bundled packages installed without TUR download.\n")
        return true
    }

    private fun readOfflineBundle(): OfflineBundle? {
        if (!this::appContext.isInitialized) return null
        val arch = offlineArchName()
        val assetDir = "offline-debs/$arch"
        val entries = try {
            appContext.assets.list(assetDir)
        } catch (_: Exception) {
            null
        }
        if (entries == null || entries.isEmpty() || !entries.contains("manifest.json")) return null

        val text = try {
            appContext.assets.open("$assetDir/manifest.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read $assetDir/manifest.json: ${e.message}")
            return null
        }

        return try {
            val root = JSONObject(text)
            if (root.optInt("schema", 0) != 1) return null
            if (root.optString("arch") != arch) return null
            val array = root.getJSONArray("packages")
            val packages = mutableListOf<OfflinePackage>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val filename = obj.getString("filename")
                if (filename.contains('/') || filename.contains("..") || !filename.endsWith(".deb")) {
                    return null
                }
                val sha = obj.getString("sha256").lowercase()
                if (!Regex("^[0-9a-f]{64}$").matches(sha)) return null
                packages += OfflinePackage(
                    name = obj.getString("name"),
                    version = obj.optString("version", ""),
                    filename = filename,
                    size = obj.getLong("size"),
                    sha256 = sha
                )
            }
            val depsArray = root.optJSONArray("aptDependencies") ?: return null
            val dependencies = mutableListOf<String>()
            for (i in 0 until depsArray.length()) {
                val dep = depsArray.getString(i)
                if (Regex("^[a-z0-9][a-z0-9+.-]*$").matches(dep)) dependencies += dep
            }
            if (packages.isEmpty() || dependencies.isEmpty()) return null
            OfflineBundle(arch, dependencies, packages)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid offline bundle manifest: ${e.message}")
            null
        }
    }

    private fun offlineFileValid(file: File, pkg: OfflinePackage): Boolean {
        if (!file.isFile) return false
        if (pkg.size > 0 && file.length() != pkg.size) return false
        return sha256File(file).equals(pkg.sha256, ignoreCase = true)
    }

    private fun copyOfflineAsset(assetPath: String, dest: File, pkg: OfflinePackage) {
        val tmp = File(dest.parentFile, "${dest.name}.amcode-tmp")
        tmp.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            appContext.assets.open(assetPath).use { input ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var count: Int
                    while (input.read(buffer).also { count = it } != -1) {
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        total += count
                    }
                }
            }
            if (pkg.size > 0 && total != pkg.size) {
                throw IOException("size mismatch (${total}/${pkg.size}) for $assetPath")
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (!actual.equals(pkg.sha256, ignoreCase = true)) {
                throw IOException("SHA-256 mismatch for $assetPath")
            }
            if (!tmp.renameTo(dest)) {
                dest.delete()
                if (!tmp.renameTo(dest)) throw IOException("cannot rename $tmp to $dest")
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    // ─── dpkg install / post-install fixups ───────────────────

    private fun collectArchiveDebs(): List<File> {
        val archives = File(varDir, "cache/apt/archives")
        val all = archives.listFiles { f -> f.isFile && f.extension == "deb" && f.length() > 0 }
            ?.toList() ?: emptyList()
        // Only the newest cached version of each package: a repo bump can
        // leave an older deb next to a fresh download, and handing both to
        // one `dpkg --force-all --unpack` risks downgrades/ordering issues.
        // The lexically larger version string wins, which matches Debian
        // ordering for same-epoch point updates.
        val newest = all.groupBy { file ->
            val name = file.name
            val idx = name.indexOf('_')
            if (idx > 0) name.substring(0, idx) else name
        }.mapNotNull { (_, files) ->
            files.maxByOrNull { it.name.substringAfter('_', "") }
        }
        val deps = newest.filterNot {
            it.name.startsWith("code-server_") || it.name.startsWith("nodejs-24_")
        }.sortedBy { it.name }
        val node = newest.filter { it.name.startsWith("nodejs-24_") }
        val codeServer = newest.filter { it.name.startsWith("code-server_") }
        return deps + node + codeServer
    }

    private fun installDownloadedDebs(debs: List<File>): Boolean {
        if (debs.isEmpty()) {
            lastInstallError = "no .deb files were downloaded"
            emitOutput("[dpkg] No .deb files in apt cache.\n")
            return false
        }
        return installDebFiles(debs)
    }

    /**
     * Install .deb files with dpkg.
     *
     * Termux .debs contain ./data/data/com.termux/files/usr/... payload paths,
     * but this app cannot create /data/data/com.termux. The installer therefore
     * unpacks under $PREFIX (the only writable root), moves the payload up and
     * rewrites the database. Two dpkg details matter:
     *
     *  - `--root=$PREFIX` joins the root with dpkg's compiled-in ADMINDIR
     *    (`$PREFIX/var/lib/dpkg`), producing the doubled
     *    `$PREFIX/data/data/com.devbox/files/usr/var/lib/dpkg`. `--instdir`
     *    changes only the install dir, so the real admin dir is used.
     *  - `--unpack` leaves postinst scripts pending, which lets us move the
     *    payload and rewrite the com.termux prefix before `--configure -a`
     *    executes them from the real admin dir with a working shebang.
     */
    private fun installDebFiles(debs: List<File>): Boolean {
        val dpkg = File(binDir, "dpkg").absolutePath
        val env = mapOf("DEBIAN_FRONTEND" to "noninteractive")

        // $PREFIX/data is only ever the nested com.termux payload from an
        // interrupted/previous run; it is not a real package path.
        File(usrDir, "data").deleteRecursively()

        // The bootstrap database records /data/data/com.termux/... while the
        // bootstrap itself was extracted to the real com.devbox prefix. Fix
        // those entries before unpacking upgrades so their preinst/prerm
        // scripts also have a working shebang.
        val preMigrated = migrateDpkgPathsToDevbox()
        if (preMigrated > 0) {
            Log.i(TAG, "dpkg db: pre-unpack migration updated $preMigrated file(s)")
        }

        val unpackArgs = mutableListOf(
            "--instdir=${usrDir.absolutePath}",
            "--force-all",
            // Same reason as the wrapper: app UIDs cannot chroot, and the
            // install/admin dir spellings differ (/data/user/0 vs /data/data).
            "--force-script-chrootless",
            "--unpack"
        )
        unpackArgs += debs.map { it.absolutePath }
        emitOutput("[dpkg] unpacking ${debs.size} package(s)...\n")
        val unpack = try {
            consumeProcess(execCommand(dpkg, unpackArgs, usrDir, env))
        } catch (e: Exception) {
            lastInstallError = "dpkg --unpack failed to start: ${e.message}"
            emitOutput("[dpkg] ${lastInstallError}\n")
            return false
        }
        if (unpack.exitCode != 0) {
            Log.w(TAG, "dpkg --unpack exit=${unpack.exitCode}:\n" +
                unpack.lines.filter { it.isNotBlank() }.takeLast(8).joinToString("\n"))
        }

        // Move $PREFIX/data/data/com.termux/files/usr/* up into $PREFIX and
        // rewrite com.termux strings in the installed payload.
        applyPostInstallFixups()

        // The unpacked .list/conffiles/info scripts still use the package's
        // com.termux prefix. Rewrite them now so --configure finds the real
        // files and the maintainer scripts have com.devbox shebangs.
        val postMigrated = migrateDpkgPathsToDevbox()
        Log.i(TAG, "dpkg db: post-unpack migration updated $postMigrated file(s)")

        emitOutput("[dpkg] configuring packages (postinst)...\n")
        val configure = try {
            consumeProcess(
                execCommand(
                    dpkg,
                    listOf("--force-all", "--force-script-chrootless", "--configure", "-a"),
                    usrDir,
                    env
                )
            )
        } catch (e: Exception) {
            lastInstallError = "dpkg --configure failed to start: ${e.message}"
            emitOutput("[dpkg] ${lastInstallError}\n")
            return false
        }

        val audit = try {
            consumeProcess(execCommand(dpkg, listOf("--audit"), usrDir, env))
        } catch (e: Exception) {
            Log.w(TAG, "dpkg --audit failed to start: ${e.message}")
            null
        }
        val auditProblems = audit?.lines?.filter {
            it.isNotBlank() && !it.contains(".dpkg.cfg")
        }.orEmpty()
        val auditClean = audit != null && audit.exitCode == 0 && auditProblems.isEmpty()
        val detail = "unpack=${unpack.exitCode} configure=${configure.exitCode} " +
            "audit=${if (auditClean) "clean" else "${auditProblems.size} problem(s)"}"
        if (unpack.exitCode != 0) {
            // Payload is not reliably on disk: hard failure so the caller can
            // retry or fall back instead of persisting a broken environment.
            emitOutput("[dpkg] exit=1 ($detail)\n")
            lastInstallError = lastInstallError.ifBlank { "dpkg --unpack failed" }
            val tail = (unpack.lines + configure.lines + auditProblems)
                .filter { it.isNotBlank() }
                .takeLast(8)
            if (tail.isNotEmpty()) Log.w(TAG, "dpkg failure tail:\n${tail.joinToString("\n")}")
            return false
        }
        if (!configureClean(configure.exitCode, auditClean)) {
            // Binaries are in place, but some maintainer script failed or the
            // audit is dirty. Half-configured packages are detected by
            // pkgSetupHealthFailure (this same launch, via ensurePkgSetup)
            // which re-runs dpkg --configure -a until the database is clean.
            emitOutput("[dpkg] WARN configure incomplete ($detail); repair will re-configure\n")
            if (lastInstallError.isBlank()) {
                lastInstallError = "dpkg configure incomplete ($detail)"
            }
        } else {
            emitOutput("[dpkg] exit=0 ($detail; ${debs.size} package(s))\n")
        }
        return true
    }

    private fun configureClean(configureExit: Int, auditClean: Boolean): Boolean =
        configureExit == 0 && auditClean

    /** Finish any pending package configuration (no-op when the DB is clean). */
    private fun finishDpkgConfigure(): Boolean {
        return try {
            val dpkg = File(binDir, "dpkg").absolutePath
            val env = mapOf("DEBIAN_FRONTEND" to "noninteractive")
            consumeProcess(
                execCommand(
                    dpkg,
                    listOf("--force-all", "--force-script-chrootless", "--configure", "-a"),
                    usrDir,
                    env
                )
            ).exitCode == 0
        } catch (e: Exception) {
            Log.w(TAG, "dpkg --configure -a failed to start: ${e.message}")
            false
        }
    }

    /**
     * Rewrite /data/data/com.termux -> /data/data/com.devbox in the dpkg
     * database (status, .list, conffiles, maintainer scripts, alternatives,
     * triggers and updates).
     *
     * Both the leading-slash form used by status/.list/conffiles and the bare
     * "data/data/..." form used by .md5sums are handled. The replacement is
     * byte-wise and same-length, so it is safe for any database file.
     */
    private fun migrateDpkgPathsToDevbox(): Int {
        val db = File(varDir, "lib/dpkg")
        if (!db.isDirectory) return 0
        val targets = mutableListOf<File>()
        for (name in listOf(
            "status", "status-old", "available", "diversions", "statoverride"
        )) {
            val file = File(db, name)
            if (file.isFile) targets += file
        }
        for (dir in listOf("info", "alternatives", "triggers", "updates")) {
            File(db, dir).listFiles()?.forEach { if (it.isFile) targets += it }
        }
        val patterns = listOf(
            "/data/data/com.termux" to "/data/data/com.devbox",
            "data/data/com.termux" to "data/data/com.devbox"
        )
        var changed = 0
        for (file in targets) {
            try {
                var bytes = file.readBytes()
                var modified = false
                for ((oldText, newText) in patterns) {
                    val updated = replaceBytes(
                        bytes,
                        oldText.toByteArray(Charsets.UTF_8),
                        newText.toByteArray(Charsets.UTF_8)
                    )
                    if (updated != null) {
                        bytes = updated
                        modified = true
                    }
                }
                if (modified) {
                    file.writeBytes(bytes)
                    changed++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot migrate dpkg db file ${file.absolutePath}: ${e.message}")
            }
        }
        return changed
    }

    /** Replace every occurrence of [old] with the same-length [new]. */
    private fun replaceBytes(source: ByteArray, old: ByteArray, new: ByteArray): ByteArray? {
        if (old.isEmpty() || old.size != new.size || source.size < old.size) return null
        var found = false
        var index = 0
        val result = source.copyOf()
        while (index <= result.size - old.size) {
            var match = true
            for (j in old.indices) {
                if (result[index + j] != old[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                for (j in new.indices) result[index + j] = new[j]
                found = true
                index += old.size
            } else {
                index++
            }
        }
        return if (found) result else null
    }

    private fun applyPostInstallFixups() {
        _setupState.value = SetupProgress(State.INSTALLING_PACKAGES, "Finalizing...", 82)

        // dpkg unpacks under the package's baked-in prefix. A raw Termux deb
        // uses com.termux; a deb already rewritten by the pkg wrapper uses
        // com.devbox. Move either payload up into the real PREFIX. (The apt
        // cache can contain both kinds, e.g. an old pkg-installed hello.deb
        // next to a freshly downloaded glib.deb.)
        var movedNestedPayload = false
        var copiedNestedPayload = true
        // Rewritten (older cached) debs first, raw (current download) debs
        // last, so a current package wins if the cache holds a stale copy of
        // the same package.
        for (nestedPrefix in listOf(
            File(usrDir, "data/data/com.devbox/files/usr"),
            File(usrDir, "data/data/com.termux/files/usr")
        )) {
            if (!nestedPrefix.isDirectory) continue
            movedNestedPayload = true
            val copied = runShellQuiet(
                "SRC=\"$nestedPrefix\"; " +
                    "if [ -d \"\$SRC\" ]; then cp -r \"\$SRC\"/* ${usrDir.absolutePath}/; fi"
            )
            if (!copied) copiedNestedPayload = false
        }
        // The nested tree is never reused: the next install re-unpacks every
        // deb from its .deb file, and a leftover tree would just shadow the
        // payload during patching.
        if (movedNestedPayload) {
            runShellQuiet("rm -rf ${usrDir.absolutePath}/data")
            if (!copiedNestedPayload) {
                Log.w(TAG, "Nested payload copy reported a failure; continuing with rewrite")
            }
        }

        // Fix paths that packages baked in as text. opt/ (nodejs-24), share/
        // and include/ are included so the first-install path matches what
        // the dpkg wrapper rewrites for every later package install.
        runShellQuiet(
            "for d in ${binDir.absolutePath} ${File(usrDir, "lib").absolutePath} " +
                "${File(usrDir, "opt").absolutePath} ${File(usrDir, "share").absolutePath} " +
                "${File(usrDir, "include").absolutePath}; do " +
                "[ -d \"\$d\" ] && grep -rl 'com.termux' \"\$d\" 2>/dev/null; done | " +
                "xargs -r sed -i 's|com.termux|com.devbox|g' 2>/dev/null"
        )
        // Also patch etc/ but exclude mirrors/ (pkg uses mirror URLs with 'termux' in domain).
        runShellQuiet(
            "grep -rl 'com.termux' ${etcDir.absolutePath} 2>/dev/null | grep -v '/termux/' | " +
                "xargs -r sed -i 's|com.termux|com.devbox|g' 2>/dev/null"
        )

        stripForeignArchBinaries()

        // nodejs-24 installs into opt/nodejs-24/bin; code-server expects both
        // bin/node and lib/code-server/lib/node.
        val nodeJs24 = File(usrDir, "opt/nodejs-24/bin/node")
        val nodeLink = File(binDir, "node")
        if (nodeJs24.exists()) {
            if (!nodeLink.exists()) {
                try {
                    java.nio.file.Files.createSymbolicLink(nodeLink.toPath(), nodeJs24.toPath())
                } catch (_: Exception) {
                }
            }
            val csLink = File(usrDir, "lib/code-server/lib/node")
            csLink.parentFile?.mkdirs()
            try {
                csLink.delete()
                java.nio.file.Files.createSymbolicLink(csLink.toPath(), nodeJs24.toPath())
            } catch (_: Exception) {
            }
        }
    }

    /**
     * The TUR code-server package ships npm prebuilds for every platform;
     * on arm64 the x86_64/arm32 copies are dead weight (~154 MB, ~30% of the
     * unpacked tree) that can never load. Delete them right after unpack and
     * prune the matching lines from the dpkg .list so `dpkg --verify` and
     * future upgrades stay consistent (found by the 16KB page-size audit;
     * arm64 devices only — emulators keep the stock tree for fidelity).
     */
    private fun stripForeignArchBinaries() {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: return
        val codeServerRoot = File(usrDir, "lib/code-server")
        if (!codeServerRoot.isDirectory) return

        // Per-ABI dead-weight list from the 16KB audit: npm prebuilds for the
        // other architectures (and glibc builds that cannot load on bionic).
        val deadPaths = if (abi.startsWith("arm64")) listOf(
            "lib/code-server/lib/vscode/extensions/microsoft-authentication/dist/libmsalruntime.so",
            "lib/code-server/lib/vscode/extensions/microsoft-authentication/dist/msal-node-runtime.node",
            "lib/code-server/lib/vscode/node_modules/@github/copilot-sdk-linux-x64",
            "lib/code-server/lib/vscode/node_modules/@koromix/koffi-linux-x64",
            "lib/code-server/lib/vscode/node_modules/@parcel/watcher-linux-x64-glibc",
            "lib/code-server/lib/vscode/node_modules/@parcel/watcher-linux-x64-musl",
            "lib/code-server/lib/vscode/node_modules/@vscode/sandbox-runtime/vendor/seccomp/x64/apply-seccomp",
            "lib/code-server/node_modules/argon2/prebuilds/freebsd-x64",
            "lib/code-server/node_modules/argon2/prebuilds/linux-x64",
            "lib/code-server/node_modules/argon2/prebuilds/linux-arm"
        ) else listOf(
            "lib/code-server/node_modules/argon2/prebuilds/freebsd-arm64",
            "lib/code-server/node_modules/argon2/prebuilds/linux-arm64",
            "lib/code-server/node_modules/argon2/prebuilds/linux-arm"
        )
        var freed = 0L
        var files = 0
        val deletedPrefixes = mutableListOf<String>()
        for (rel in deadPaths) {
            val target = File(usrDir, rel)
            if (!target.exists()) continue
            deletedPrefixes.add(usrDir.absolutePath + "/" + rel.trimEnd('/') + "/")
            if (target.isDirectory) {
                target.walkTopDown().forEach {
                    if (it.isFile) {
                        freed += it.length()
                        files++
                    }
                }
                target.deleteRecursively()
            } else {
                freed += target.length()
                files++
                target.delete()
            }
        }
        if (files == 0) return

        // Keep the dpkg database consistent: drop the .list lines that point
        // into the removed trees (directories have a trailing slash).
        val listFile = File(varDir, "lib/dpkg/info/code-server.list")
        if (listFile.isFile) {
            try {
                val kept = listFile.readLines().filter { line ->
                    deletedPrefixes.none { line.startsWith(it) }
                }
                listFile.writeText(kept.joinToString("", postfix = "\n"))
            } catch (e: Exception) {
                Log.w(TAG, "Cannot prune code-server.list after stripping: ${e.message}")
            }
        }
        Log.i(TAG, "stripForeignArchBinaries: removed $files file(s), ${(freed / (1024 * 1024))} MB of foreign-arch binaries")
    }

    private fun runShellQuiet(script: String): Boolean {
        return try {
            consumeProcess(execShell(script, usrDir)).exitCode == 0
        } catch (e: Exception) {
            Log.w(TAG, "Shell fixup failed: ${e.message}")
            false
        }
    }

    private data class ProcessResult(val exitCode: Int, val lines: List<String>)

    private fun consumeProcess(process: Process): ProcessResult {
        val lines = mutableListOf<String>()
        process.inputStream.bufferedReader().use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val text = line!!
                lines += text
                if (text.isNotBlank()) Log.i(TAG, text)
            }
        }
        return ProcessResult(process.waitFor(), lines)
    }

    fun isNodeInstalled(): Boolean = File(binDir, "node").exists()
    fun isCodeServerInstalled(): Boolean = File(binDir, "code-server").exists()

    /**
     * Installations created before the dpkg-wrapper flow never registered
     * code-server/nodejs-24 in the dpkg database (their binaries were moved
     * into place by hand), so `dpkg -l`, `pkg upgrade` and dependency
     * resolution treat them as unknown files. Register a minimal, accurate
     * stanza once; real installs through the wrapper keep writing full
     * metadata. Idempotent and fail-safe.
     */
    private fun registerLegacyPackagesInDpkgDb() {
        val status = File(varDir, "lib/dpkg/status")
        if (!status.isFile) return
        try {
            val statusText = status.readText()
            val entries = mutableListOf<Pair<String, List<File>>>()
            val codeServerRoot = File(usrDir, "lib/code-server")
            if (File(binDir, "code-server").isFile &&
                !statusText.contains("\nPackage: code-server\n")
            ) {
                entries.add("code-server" to listOfNotNull(codeServerRoot.takeIf { it.isDirectory }, File(binDir, "code-server")))
            }
            val nodeRoot = File(usrDir, "opt/nodejs-24")
            if (nodeRoot.isDirectory &&
                !statusText.contains("\nPackage: nodejs-24\n")
            ) {
                entries.add("nodejs-24" to listOf(nodeRoot, File(binDir, "node")))
            }
            if (entries.isEmpty()) return

            if (!File(varDir, "lib/dpkg/status.amcode-legacy-bak").exists()) {
                status.copyTo(File(varDir, "lib/dpkg/status.amcode-legacy-bak"), overwrite = false)
            }
            val arch = when (val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull()) {
                "arm64-v8a" -> "arm64"
                "x86_64" -> "x86_64"
                else -> abi
            }
            var appended = StringBuilder()
            for ((name, roots) in entries) {
                val version = when (name) {
                    "code-server" -> packageJsonVersion(codeServerRoot) ?: "4.143.1"
                    else -> packageJsonVersion(File(nodeRoot, "lib/node_modules/nodejs-24"))
                        ?: nodeRoot.name.removePrefix("nodejs-").ifBlank { "24" }
                }
                appended.append("\nPackage: $name\n")
                    .append("Version: $version\n")
                    .append("Architecture: ${arch ?: "all"}\n")
                    .append("Maintainer: AMCode legacy registration\n")
                    .append("Status: install ok installed\n\n")

                val listFile = File(varDir, "lib/dpkg/info/$name.list")
                if (!listFile.exists()) {
                    val lines = StringBuilder()
                    for (root in roots) {
                        if (root == null || !root.exists()) continue
                        if (root.isFile) lines.append(root.absolutePath).append('\n')
                        else root.walkTopDown().filter { it.isFile || java.nio.file.Files.isSymbolicLink(it.toPath()) }
                            .forEach { lines.append(it.absolutePath).append('\n') }
                    }
                    writeAtomically(listFile, lines.toString())
                }
                Log.i(TAG, "dpkg db: registered legacy $name $version")
            }
            writeAtomically(status, statusText.dropLastWhile { it == '\n' } + "\n" + appended)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot register legacy packages in the dpkg db: ${e.message}")
        }
    }

    /** Best-effort "version" from a package.json inside [root]. */
    private fun packageJsonVersion(root: File?): String? {
        if (root == null) return null
        return try {
            val text = File(root, "package.json").readText()
            Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeAtomically(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.amcode-tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                Log.w(TAG, "Cannot write ${target.absolutePath}")
            }
        }
    }

    // ─── apt wrapper / URL resolution ─────────────────────────

    private data class AptResult(val exitCode: Int, val lines: List<String>)

    private fun runAptCommand(vararg args: String): Boolean = runApt(*args).exitCode == 0

    private fun runApt(vararg args: String, quiet: Boolean = false, quick: Boolean = false): AptResult {
        val env = environment.toMutableMap()
        env["DEBIAN_FRONTEND"] = "noninteractive"

        val fullArgs = mutableListOf(
            "-o", "Acquire::Retries=${if (quick) 1 else 5}",
            "-o", "Acquire::http::Timeout=${if (quick) 15 else 30}",
            "-o", "Acquire::https::Timeout=${if (quick) 15 else 30}"
        )
        fullArgs += args
        val aptPath = File(binDir, "apt").absolutePath
        val process = execCommand(aptPath, fullArgs, homeDir, env)

        val lines = mutableListOf<String>()
        val reader = process.inputStream.bufferedReader()
        var line: String?
        // Only truly benign Termux/bionic noise is hidden. In particular
        // "W: Failed to fetch" and GPG/fetch errors are shown: they are the
        // only evidence of why TUR failed.
        val skipPatterns = listOf(
            "Bad system call", "pre-installation script", "post-installation script",
            "dependency problems, but configuring anyway",
            "installed tur-repo package", "new nodejs package"
        )
        while (reader.readLine().also { line = it } != null) {
            val text = line!!
            lines += text
            if (text.isBlank()) continue
            if (text.contains("Failed to fetch") ||
                text.contains("Unable to fetch") ||
                text.contains("Could not connect") ||
                text.contains("Temporary failure") ||
                text.startsWith("E:")
            ) {
                lastInstallError = text.trim()
            }
            if (quiet) {
                Log.i(TAG, text)
                continue
            }
            if (!skipPatterns.any { text.contains(it) }) {
                emitOutput("  $text\n")
                if (text.startsWith("Get:") || text.startsWith("Fetched ")) {
                    _setupState.value = SetupProgress(
                        State.INSTALLING_PACKAGES,
                        text.take(140),
                        50
                    )
                }
            }
        }
        val exitCode = process.waitFor()
        return AptResult(exitCode, lines)
    }

    private data class DownloadSpec(
        val url: String,
        val filename: String,
        val size: Long,
        val sha256: String?
    )

    /**
     * Ask apt for the exact URLs of everything code-server needs without
     * downloading it. Lines look like:
     *   'https://tur.../code-server_4.143.1_aarch64.deb' code-server_...deb 102502792 SHA256:04e...
     *
     * TUR currently 302-redirects each .deb to a GitHub release asset.
     * HttpURLConnection follows that redirect for every Range request, so
     * each worker gets a fresh signed URL instead of one that can expire
     * during a very slow download.
     */
    private fun resolveDownloadUris(pkg: String): List<DownloadSpec> {
        val result = runApt("install", "-y", "-d", "--print-uris", pkg, quiet = true)
        if (result.exitCode != 0) {
            lastInstallError = result.lines.lastOrNull {
                it.contains("Failed to fetch") || it.startsWith("E:")
            }?.trim() ?: "apt --print-uris failed"
            return emptyList()
        }
        val regex = Regex("""'([^']+)'\s+(\S+\.deb)\s+(\d+)(?:\s+(\S+))?""")
        val specs = mutableListOf<DownloadSpec>()
        for (line in result.lines) {
            val m = regex.find(line) ?: continue
            val url = m.groupValues[1]
            val rawName = m.groupValues[2]
            val uriName = try {
                URI(url).path?.substringAfterLast('/')
            } catch (_: Exception) {
                null
            }
            val filename = (uriName ?: rawName).takeIf {
                it.endsWith(".deb") && !it.contains('/') && !it.contains("..")
            } ?: continue
            val size = m.groupValues[3].toLongOrNull() ?: 0L
            val token = m.groupValues[4]
            val sha = token.substringAfter(':', token).lowercase().takeIf {
                it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' }
            }
            specs += DownloadSpec(url, filename, size, sha)
        }
        return specs.distinctBy { it.filename }
    }

    // ─── Parallel Range downloader ────────────────────────────

    private class RangeUnsupportedException(message: String) : IOException(message)

    private fun cleanupPartialDownloads(specs: List<DownloadSpec>) {
        val dir = File(varDir, "cache/apt/archives")
        for (spec in specs) {
            File(dir, "${spec.filename}.part").delete()
            for (i in 0 until MAX_RANGE_CONNECTIONS) {
                File(dir, "${spec.filename}.part$i").delete()
            }
            // Range-named parts (.part.<start>-<end>) from this or an older
            // build are never reused across a repo version change.
            dir.listFiles { f ->
                f.name.startsWith("${spec.filename}.part.")
            }?.forEach { it.delete() }
        }
    }

    private fun downloadDebs(specs: List<DownloadSpec>): Boolean {
        val archivesDir = File(varDir, "cache/apt/archives").apply { mkdirs() }
        val totalBytes = specs.sumOf { if (it.size > 0) it.size else 1L }
        var completedBefore = 0L
        val startedAt = System.nanoTime()

        for (spec in specs) {
            val dest = File(archivesDir, spec.filename)
            if (isCompleteDeb(dest, spec)) {
                cleanupPartialDownloads(listOf(spec))
                completedBefore += if (spec.size > 0) spec.size else dest.length()
                continue
            }
            val lastReport = AtomicLong(0L)
            val ok = try {
                downloadOne(spec, dest) { done, _ ->
                    val now = System.nanoTime()
                    if (now - lastReport.get() >= 2_000_000_000L) {
                        lastReport.set(now)
                        val overall = completedBefore + done
                        val pct = if (totalBytes > 0) {
                            ((overall * 100) / totalBytes).toInt().coerceIn(0, 100)
                        } else 0
                        val seconds = ((now - startedAt) / 1e9).coerceAtLeast(0.1)
                        val speed = (overall / 1024.0 / seconds).toInt()
                        _setupState.value = SetupProgress(
                            State.INSTALLING_PACKAGES,
                            "Downloading ${spec.filename}... $pct% " +
                                "(${overall / (1024 * 1024)}/${totalBytes / (1024 * 1024)} MB, $speed KB/s)",
                            42 + pct * 28 / 100
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Download failed for ${spec.filename}: ${e.message}", e)
                lastInstallError = "download ${spec.filename}: ${e.message}"
                false
            }
            if (!ok) {
                emitOutput("[net] Failed while downloading ${spec.filename}.\n")
                return false
            }
            completedBefore += if (spec.size > 0) spec.size else dest.length()
            emitOutput("[net] Downloaded ${spec.filename} (${dest.length() / (1024 * 1024)} MB)\n")
        }
        return true
    }

    private fun downloadOne(
        spec: DownloadSpec,
        dest: File,
        onProgress: (Long, Long) -> Unit
    ): Boolean {
        if (dest.exists() && !isCompleteDeb(dest, spec)) dest.delete()
        val size = if (spec.size > 0) spec.size else contentLength(spec.url)
        if (size <= 0) return downloadSingle(spec, dest, null, onProgress)

        val connections = when {
            size >= 32L * 1024 * 1024 -> MAX_RANGE_CONNECTIONS
            size >= 8L * 1024 * 1024 -> 6
            size >= 2L * 1024 * 1024 -> 4
            else -> 1
        }
        if (connections <= 1) return downloadSingle(spec, dest, size, onProgress)

        return try {
            downloadRanged(spec, dest, size, connections, onProgress)
        } catch (e: RangeUnsupportedException) {
            Log.i(TAG, "Range not supported for ${spec.filename}; using single stream")
            cleanupPartialDownloads(listOf(spec))
            downloadSingle(spec, dest, size, onProgress)
        }
    }

    private fun downloadRanged(
        spec: DownloadSpec,
        dest: File,
        size: Long,
        connections: Int,
        onProgress: (Long, Long) -> Unit
    ): Boolean {
        val segments = minOf(connections, ((size + 4L * 1024 * 1024 - 1) / (4L * 1024 * 1024)).toInt())
            .coerceAtLeast(1)
        if (segments <= 1) return downloadSingle(spec, dest, size, onProgress)
        val segmentSize = (size + segments - 1) / segments
        val downloaded = AtomicLong(0L)
        val abort = AtomicBoolean(false)

        // Resume any parts left by a previous app run. The part file name
        // encodes its exact byte range, so a repo version bump (new size ->
        // shifted segment boundaries) can never mis-read an old layout: only
        // parts whose name matches this run's ranges are resumed, everything
        // else is garbage-collected by cleanupPartialDownloads.
        for (i in 0 until segments) {
            val start = i.toLong() * segmentSize
            val end = minOf(size - 1, start + segmentSize - 1)
            val expected = end - start + 1
            val part = rangePartFile(dest, start, end)
            if (part.length() > expected) part.delete()
            downloaded.addAndGet(part.length())
        }

        val pool = Executors.newFixedThreadPool(segments)
        try {
            val tasks: List<Callable<Boolean>> = (0 until segments).map { index ->
                Callable {
                    val start = index.toLong() * segmentSize
                    val end = minOf(size - 1, start + segmentSize - 1)
                    downloadSegment(
                        spec.url, dest, index, start, end,
                        downloaded, abort, onProgress, size
                    )
                }
            }
            val futures = pool.invokeAll(tasks)
            var rangeUnsupported = false
            for (future in futures) {
                try {
                    if (!future.get()) {
                        abort.set(true)
                        return false
                    }
                } catch (e: ExecutionException) {
                    if (e.cause is RangeUnsupportedException) {
                        rangeUnsupported = true
                    } else {
                        Log.w(TAG, "Segment failure: ${e.cause?.message}", e.cause)
                        abort.set(true)
                        return false
                    }
                }
            }
            if (rangeUnsupported) throw RangeUnsupportedException("server ignored Range")
            if (!mergeSegments(spec, dest, segments, size)) return false
            for (i in 0 until segments) {
                File(dest.parentFile, "${dest.name}.part$i").delete()
            }
            return true
        } finally {
            pool.shutdownNow()
        }
    }

    /** Part file whose name encodes the exact byte range it holds. */
    private fun rangePartFile(dest: File, start: Long, end: Long): File =
        File(dest.parentFile, "${dest.name}.part.$start-$end")

    private fun downloadSegment(
        url: String,
        dest: File,
        index: Int,
        start: Long,
        end: Long,
        downloaded: AtomicLong,
        abort: AtomicBoolean,
        onProgress: (Long, Long) -> Unit,
        total: Long
    ): Boolean {
        val part = rangePartFile(dest, start, end)
        val expected = end - start + 1
        if (part.length() > expected) part.delete()
        var attempt = 0
        while (attempt < 5 && !abort.get()) {
            val have = if (part.exists()) part.length() else 0L
            if (have >= expected) return true
            var conn: HttpURLConnection? = null
            try {
                conn = openHttp(url)
                conn.setRequestProperty("Range", "bytes=${start + have}-$end")
                when (val code = conn.responseCode) {
                    HttpURLConnection.HTTP_PARTIAL -> {
                        val contentRange = conn.getHeaderField("Content-Range")
                        if (contentRange != null && !contentRange.startsWith("bytes ${start + have}-")) {
                            throw IOException("unexpected Content-Range: $contentRange")
                        }
                        var remaining = expected - have
                        conn.inputStream.use { input ->
                            FileOutputStream(part, true).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (remaining > 0 && !abort.get()) {
                                    val count = input.read(buffer)
                                    if (count == -1) break
                                    val toWrite = minOf(count.toLong(), remaining).toInt()
                                    output.write(buffer, 0, toWrite)
                                    downloaded.addAndGet(toWrite.toLong())
                                    remaining -= toWrite
                                    onProgress(downloaded.get(), total)
                                }
                            }
                        }
                        if (remaining > 0) throw IOException("short segment, missing $remaining bytes")
                        return true
                    }
                    HttpURLConnection.HTTP_OK -> {
                        abort.set(true)
                        throw RangeUnsupportedException("server returned 200 for a Range request")
                    }
                    HttpURLConnection.HTTP_ENTITY_TOO_LARGE,
                    416 -> {
                        if (part.length() == expected) return true
                        part.delete()
                        throw IOException("HTTP $code for segment")
                    }
                    else -> throw IOException("HTTP $code")
                }
            } catch (e: RangeUnsupportedException) {
                throw e
            } catch (e: Exception) {
                attempt++
                Log.w(TAG, "Segment $index attempt $attempt failed: ${e.message}")
                if (attempt >= 5) return false
                try {
                    Thread.sleep(minOf(5000L, 1000L * attempt))
                } catch (_: InterruptedException) {
                    return false
                }
            } finally {
                conn?.disconnect()
            }
        }
        return part.length() == expected
    }

    private fun mergeSegments(spec: DownloadSpec, dest: File, segments: Int, size: Long): Boolean {
        val segmentSize = (size + segments - 1) / segments
        val parts = (0 until segments).map { i ->
            val start = i.toLong() * segmentSize
            val end = minOf(size - 1, start + segmentSize - 1)
            rangePartFile(dest, start, end)
        }
        if (parts.any { !it.isFile }) return false
        val total = parts.sumOf { it.length() }
        if (total != size) {
            lastInstallError = "download size mismatch for ${spec.filename} ($total/$size)"
            return false
        }
        // Append into part0 instead of allocating a second full-size temp file:
        // code-server is 102 MB and phone storage is often tight.
        val first = parts[0]
        try {
            FileOutputStream(first, true).use { output ->
                for (i in 1 until parts.size) {
                    parts[i].inputStream().use { input -> input.copyTo(output) }
                }
            }
        } catch (e: Exception) {
            lastInstallError = "cannot merge ${spec.filename}: ${e.message}"
            return false
        }
        if (spec.sha256 != null) {
            val actual = sha256File(first)
            if (!actual.equals(spec.sha256, ignoreCase = true)) {
                lastInstallError = "SHA-256 mismatch for ${spec.filename}"
                parts.forEach { it.delete() }
                return false
            }
        }
        dest.delete()
        if (!first.renameTo(dest)) return false
        parts.drop(1).forEach { it.delete() }
        return true
    }

    private fun downloadSingle(
        spec: DownloadSpec,
        dest: File,
        knownSize: Long?,
        onProgress: (Long, Long) -> Unit
    ): Boolean {
        val part = File(dest.parentFile, "${dest.name}.part")
        var attempt = 0
        while (attempt < 5) {
            var have = if (part.exists()) part.length() else 0L
            if (knownSize != null && knownSize > 0 && have == knownSize) {
                if (finishSingle(spec, part, dest)) return true
                have = 0L
            }
            var conn: HttpURLConnection? = null
            try {
                conn = openHttp(spec.url)
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                var code = conn.responseCode
                if (code == HttpURLConnection.HTTP_OK && have > 0) {
                    // Server ignored the Range header: restart from byte 0.
                    part.delete()
                    have = 0L
                }
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    throw IOException("HTTP $code")
                }
                if (code == HttpURLConnection.HTTP_PARTIAL) {
                    val contentRange = conn.getHeaderField("Content-Range")
                    if (contentRange != null && !contentRange.startsWith("bytes $have-")) {
                        part.delete()
                        have = 0L
                        throw IOException("unexpected Content-Range: $contentRange")
                    }
                }
                val contentLength = conn.contentLengthLong
                val total = when {
                    knownSize != null && knownSize > 0 -> knownSize
                    contentLength > 0 -> have + contentLength
                    else -> -1L
                }
                conn.inputStream.use { input ->
                    FileOutputStream(part, true).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var count: Int
                        while (input.read(buffer).also { count = it } != -1) {
                            output.write(buffer, 0, count)
                            have += count
                            onProgress(have, total)
                        }
                    }
                }
                if (total > 0 && part.length() != total) {
                    throw IOException("short download (${part.length()}/$total)")
                }
                if (finishSingle(spec, part, dest)) return true
                throw IOException("checksum/rename failed")
            } catch (e: Exception) {
                attempt++
                Log.w(TAG, "Download ${spec.filename} attempt $attempt failed: ${e.message}")
                if (attempt >= 5) return false
                try {
                    Thread.sleep(minOf(5000L, 1000L * attempt))
                } catch (_: InterruptedException) {
                    return false
                }
            } finally {
                conn?.disconnect()
            }
        }
        return false
    }

    private fun finishSingle(spec: DownloadSpec, part: File, dest: File): Boolean {
        if (!part.isFile || part.length() == 0L) return false
        if (spec.size > 0 && part.length() != spec.size) return false
        if (spec.sha256 != null && !sha256File(part).equals(spec.sha256, ignoreCase = true)) {
            part.delete()
            lastInstallError = "SHA-256 mismatch for ${spec.filename}"
            return false
        }
        dest.delete()
        return part.renameTo(dest)
    }

    private fun contentLength(url: String): Long {
        var conn: HttpURLConnection? = null
        return try {
            conn = openHttp(url)
            conn.requestMethod = "HEAD"
            conn.responseCode
            conn.contentLengthLong
        } catch (_: Exception) {
            -1L
        } finally {
            conn?.disconnect()
        }
    }

    private fun openHttp(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", "AMCode/1.0 (Android)")
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Encoding", "identity")
        return conn
    }

    private fun isCompleteDeb(file: File, spec: DownloadSpec): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        if (spec.size > 0 && file.length() != spec.size) return false
        if (spec.sha256 != null && !sha256File(file).equals(spec.sha256, ignoreCase = true)) {
            return false
        }
        return true
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var count: Int
            while (input.read(buffer).also { count = it } != -1) {
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
