package com.devbox

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
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

    // Package name MUST be exactly 10 chars to match "com.termux" for binary patching
    const val PACKAGE_NAME = "com.devbox"

    // Bootstrap asset file — selected by architecture at runtime
    private fun bootstrapAsset(): String {
        val primary = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when {
            primary.startsWith("x86_64") -> "bootstrap-x86_64.zip"
            primary.startsWith("x86") -> "bootstrap-i686.zip"
            else -> "bootstrap-aarch64.zip"
        }
    }

    // Live terminal output for UI
    private val _terminalOutput = MutableStateFlow("")
    val terminalOutput: StateFlow<String> = _terminalOutput.asStateFlow()

    fun emitOutput(line: String) {
        val current = _terminalOutput.value
        // Keep last ~5000 chars to avoid memory bloat
        val updated = if (current.length > 5000) current.takeLast(2000) + line else current + line
        _terminalOutput.value = updated
        Log.i(TAG, line.trimEnd())
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
        get() = mapOf(
            "PREFIX" to usrDir.absolutePath,
            "PATH" to "${binDir.absolutePath}:/system/bin:/system/xbin:/usr/bin:/usr/sbin:/sbin:/bin",
            "LD_LIBRARY_PATH" to File(usrDir, "lib").absolutePath,
            "HOME" to homeDir.absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8",
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system"
        )

    /**
     * Initialize Termux environment. Call once from background thread.
     * Idempotent — if already initialized, returns immediately.
     */
    suspend fun initialize(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        filesDir = context.filesDir

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
            val bootstrapZip = File(filesDir, "bootstrap-aarch64.zip")
            if (!bootstrapZip.exists()) {
                _setupState.value = SetupProgress(State.EXTRACTING, "Extracting environment files...", 8)
                try {
                    context.assets.openFd(bootstrapAsset()).use { fd ->
                        fd.createInputStream().use { input ->
                            FileOutputStream(bootstrapZip).use { output ->
                                val buffer = ByteArray(65536)
                                var total = 0L
                                var count: Int
                                while (input.read(buffer).also { count = it } != -1) {
                                    output.write(buffer, 0, count)
                                    total += count
                                }
                                Log.i(TAG, "Bootstrap extracted: $total bytes (from ${fd.length} in APK)")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Bootstrap extraction failed", e)
                    _setupState.value = SetupProgress(State.FAILED, "Extraction failed: ${e.message}", 0)
                    return@withContext Result.failure(e)
                }
            }

            // Step 3: Extract bootstrap zip
            _setupState.value = SetupProgress(State.EXTRACTING, "Extracting environment...", 12)
            emitOutput("[2/6] Extracting Termux bootstrap...\n")
            extractBootstrap(bootstrapZip)

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
                emitOutput("[FAILED] Could not install packages after 3 attempts.\n")
                _setupState.value = SetupProgress(State.FAILED, "Installation failed. Check network.", 0)
                return@withContext Result.failure(RuntimeException("Package installation failed"))
            }

            _setupState.value = SetupProgress(State.READY, "Environment ready", 100)
            Log.i(TAG, "Termux environment initialized successfully")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Termux environment", e)
            _setupState.value = SetupProgress(State.FAILED, "Failed: ${e.message}", 0)
            Result.failure(e)
        }
    }

    fun isReady(): Boolean {
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
        File(etcDir, "resolv.conf").writeText(
            "nameserver 8.8.8.8\nnameserver 8.8.4.4\n"
        )
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
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
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
                val proc = execShell("$curlBin -sI --connect-timeout 5 --max-time 10 $url/dists/stable/Release 2>/dev/null | head -1", workDir = usrDir)
                proc.waitFor()
                val elapsed = System.currentTimeMillis() - start
                if (proc.exitValue() == 0 && elapsed < bestTime) {
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
     * Install nodejs and code-server from Termux repos.
     * Uses dpkg directly for reliable installation.
     */
    fun installPackages(): Boolean = tryInstall(0)

    private fun tryInstall(attempt: Int): Boolean {
        if (!isReady()) return false
        val retrying = attempt > 0

        _setupState.value = SetupProgress(State.INSTALLING_PACKAGES,
            if (retrying) "Retrying..." else "Updating package index...",
            if (retrying) 30 else 32)
        runAptCommand("update")

        // Step 2: Configure TUR + download
        emitOutput(if (retrying) "[apt] Retrying download...\n" else "[apt] Downloading packages (~236MB)...\n")
        val turListDir = File(etcDir, "apt/sources.list.d")
        turListDir.mkdirs()
        File(turListDir, "tur.list").writeText(
            "deb [trusted=yes] https://tur.kcubeterm.com tur-packages tur tur-on-device tur-continuous\n"
        )
        runAptCommand("update")
        _setupState.value = SetupProgress(State.INSTALLING_PACKAGES, "Downloading packages...", 48)
        runAptCommand("install", "-y", "-d", "code-server")

        // Step 3: Install via dpkg
        _setupState.value = SetupProgress(State.INSTALLING_PACKAGES, "Extracting packages...", 55)
        val dpkgBin = File(binDir, "dpkg").absolutePath
        val archivesDir = File(varDir, "cache/apt/archives").absolutePath
        execShell("cd $archivesDir && $dpkgBin --root=$usrDir --force-all -i *.deb 2>&1",
            workDir = usrDir).waitFor()

        // Step 4: Post-install fixups
        _setupState.value = SetupProgress(State.INSTALLING_PACKAGES, "Finalizing...", 75)
        execShell("SRC=\"$usrDir/data/data/com.termux/files/usr\"; " +
            "if [ -d \"\$SRC\" ]; then cp -r \"\$SRC\"/* $usrDir/ && rm -rf $usrDir/data; fi",
            workDir = usrDir).waitFor()
        execShell("grep -rl 'com.termux' $usrDir/bin $usrDir/lib $usrDir/etc 2>/dev/null | " +
            "xargs -r sed -i 's|com.termux|com.devbox|g' 2>/dev/null", workDir = usrDir).waitFor()

        // Node symlinks
        val nodeJs24 = File(usrDir, "opt/nodejs-24/bin/node")
        val nodeLink = File(binDir, "node")
        if (nodeJs24.exists()) {
            if (!nodeLink.exists()) try { java.nio.file.Files.createSymbolicLink(nodeLink.toPath(), nodeJs24.toPath()) } catch (_: Exception) {}
            val csLink = File(usrDir, "lib/code-server/lib/node")
            csLink.parentFile?.mkdirs()
            try { csLink.delete(); java.nio.file.Files.createSymbolicLink(csLink.toPath(), nodeJs24.toPath()) } catch (_: Exception) {}
        }

        // Check + retry
        val ok = isNodeInstalled() && isCodeServerInstalled()
        if (ok) {
            emitOutput("[done] All packages installed.\n")
            _setupState.value = SetupProgress(State.READY, "Ready", 100)
            return true
        }
        if (attempt < 1) return tryInstall(attempt + 1)
        return false
    }

    /** Extract .deb to PREFIX — handles com.termux → com.devbox path mapping */
    private fun runDpkgInstall(debFile: File): Boolean {
        // dpkg-deb extracts to ./data/data/com.termux/files/usr/... (hardcoded in package)
        // Since our PREFIX is /data/data/com.devbox/files/usr, we extract and then
        // move files from the termux path to the devbox path.
        val termuxPrefixInDeb = File(usrDir, "data/data/com.termux/files/usr")

        // Extract data files
        val dpkgDeb = File(binDir, "dpkg-deb").absolutePath
        val proc1 = execCommand(dpkgDeb, listOf("-x", debFile.absolutePath, usrDir.absolutePath), usrDir, environment.toMutableMap())
        proc1.waitFor()

        // Move files from com.termux path to actual PREFIX
        if (termuxPrefixInDeb.isDirectory) {
            termuxPrefixInDeb.listFiles()?.forEach { dir ->
                val target = File(usrDir, dir.name)
                dir.copyRecursively(target, overwrite = true)
            }
            termuxPrefixInDeb.parentFile?.deleteRecursively()
            // Remove the stray data/ directory if empty
            val dataDir = File(usrDir, "data")
            if (dataDir.isDirectory && dataDir.listFiles()?.isEmpty() == true) dataDir.delete()
            return true
        }
        return false
    }

    /** Install code-server via npm */
    private fun runNpmInstall(): Boolean {
        val env = environment.toMutableMap()
        // Use npm from Termux bin
        val npmPath = File(binDir, "npm").absolutePath
        if (!File(npmPath).exists()) {
            emitOutput("[npm] npm not found, installing...\n")
            // npm should come with nodejs
            return false
        }
        val process = execCommand(npmPath,
            listOf("install", "-g", "code-server", "--unsafe-perm"),
            homeDir, env)
        val reader = process.inputStream.bufferedReader()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val text = line!!
            if (text.isNotBlank() && !text.startsWith("npm WARN")) {
                emitOutput("  $text\n")
            }
        }
        val exitCode = process.waitFor()
        return exitCode == 0
    }

    fun isNodeInstalled(): Boolean = File(binDir, "node").exists()
    fun isCodeServerInstalled(): Boolean = File(binDir, "code-server").exists()

    private fun runAptCommand(vararg args: String): Boolean {
        val env = environment.toMutableMap()
        env["DEBIAN_FRONTEND"] = "noninteractive"

        val aptArgs = args.toList()
        val aptPath = File(binDir, "apt").absolutePath
        val process = execCommand(aptPath, aptArgs, homeDir, env)

        // Stream output, filtering known-benign seccomp/GPG warnings
        val reader = process.inputStream.bufferedReader()
        var line: String?
        val skipPatterns = listOf(
            "Bad system call", "pre-installation script", "post-installation script",
            "dependency problems, but configuring anyway",
            "Errors were encountered while processing:",
            "W: GPG error:", "Couldn't execute", "apt-key",
            "W: Failed to fetch", "The following signatures couldn't be verified",
            "installed tur-repo package", "new nodejs package"
        )
        while (reader.readLine().also { line = it } != null) {
            val text = line!!
            if (text.isNotBlank() && !skipPatterns.any { text.contains(it) }) {
                emitOutput("  $text\n")
            }
        }
        val exitCode = process.waitFor()
        return exitCode == 0
    }
}
