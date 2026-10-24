package com.devbox

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Manages the code-server process lifecycle.
 *
 * Uses code-server installed via Termux apt (pkg install code-server).
 * The binary is at $PREFIX/bin/code-server (bionic-native, no glibc issues).
 *
 * code-server is bound to 127.0.0.1 only and gets a free high port chosen at
 * start time. This prevents LAN exposure and avoids collisions with an orphan
 * code-server that may still be listening on the old port.
 */
object CodeServerLauncher {

    private const val TAG = "CodeServerLauncher"
    private const val DEFAULT_PORT = 8080
    private const val BIND_HOST = "127.0.0.1"
    private const val READY_TIMEOUT_SECONDS = 60
    private const val STOP_GRACE_SECONDS = 5L

    // ─── Android-as-Linux platform shim (experimental, on by default) ────────
    // code-server maps process.platform=android to target platform "web", so
    // the server refuses to install/run non-Web extensions.  The official
    // code-server/Termux workaround is to require a script that makes
    // process.platform report "linux".  It is scoped to the code-server main
    // process and the extension host only (see android-as-linux.js), and can be
    // killed with $HOME/.amcode/disable-platform-shim or
    // AMCODE_ANDROID_PLATFORM_SHIM=0.  Linux native extensions (glibc) still
    // cannot run on bionic.
    private const val PLATFORM_SHIM_ASSET = "android-as-linux.js"
    private const val PLATFORM_SHIM_PATH = "libexec/amcode/android-as-linux.js"
    private const val PLATFORM_SHIM_ENV = "AMCODE_ANDROID_PLATFORM_SHIM"
    private const val PLATFORM_SHIM_DISABLE_FILE = ".amcode/disable-platform-shim"
    private const val PLATFORM_SHIM_APP_DISABLE_FILE = ".amcode-disable-platform-shim"
    private const val PLATFORM_SHIM_DEFAULT_ENABLED = true

    enum class ServerState {
        STOPPED, STARTING, RUNNING, STOPPING, ERROR
    }

    data class ServerStatus(
        val state: ServerState = ServerState.STOPPED,
        val message: String = "",
        val port: Int = DEFAULT_PORT,
        val url: String = "http://$BIND_HOST:$DEFAULT_PORT",
        val pid: Int = -1
    )

    private val _status = MutableStateFlow(ServerStatus())
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    @Volatile
    private var serverProcess: Process? = null

    @Volatile
    private var monitorJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Generation token invalidates late process callbacks after stop/restart.
    private val generationLock = Any()
    private var generation = 0

    private fun nextGeneration(): Int = synchronized(generationLock) { ++generation }

    private fun isCurrent(candidate: Int): Boolean =
        synchronized(generationLock) { generation == candidate }

    /** Path to Termux-installed code-server binary */
    private val codeServerBin: File
        get() = File(TermuxManager.binDir, "code-server")

    fun isInstalled(): Boolean = codeServerBin.exists()

    suspend fun prepareAndStart(): Result<Unit> {
        return try {
            if (!isInstalled()) {
                return Result.failure(
                    IllegalStateException("code-server not installed. Run: pkg install code-server")
                )
            }
            start()
            Result.success(Unit)
        } catch (e: CancellationException) {
            // The coroutine may be cancelled while a process has already been
            // spawned; make sure it does not survive as an orphan.
            stop()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start code-server", e)
            // Kill any half-started process; stop() is asynchronous for the caller.
            stop()
            Result.failure(e)
        }
    }

    suspend fun start() {
        val alreadyRunning = serverProcess
        if (alreadyRunning?.isAlive == true &&
            _status.value.state == ServerState.RUNNING
        ) {
            _status.value = ServerStatus(
                ServerState.RUNNING,
                "Already running",
                _status.value.port,
                _status.value.url,
                getPid(alreadyRunning!!)
            )
            return
        }

        val generation = nextGeneration()
        monitorJob?.cancel()
        monitorJob = null
        _status.value = ServerStatus(
            ServerState.STARTING,
            "Starting code-server...",
            DEFAULT_PORT,
            "http://$BIND_HOST:$DEFAULT_PORT"
        )

        withContext(Dispatchers.IO) {
            // Prefer a real free port; 8080 remains only a last-resort fallback.
            val port = findFreePort() ?: DEFAULT_PORT
            val url = "http://$BIND_HOST:$port"

            if (!isCurrent(generation)) {
                Log.i(TAG, "Start superseded before spawn")
                return@withContext
            }
            _status.value = ServerStatus(ServerState.STARTING, "Starting code-server...", port, url)

            val env = TermuxManager.environment.toMutableMap()
            env["USER"] = "devbox"
            env["SHELL"] = TermuxManager.getShell()
            env["CS_DISABLE_TELEMETRY"] = "true"
            env["CS_DISABLE_UPDATE_CHECK"] = "true"

            // The TUR code-server package ships `rg` as an absolute symlink to
            // /data/data/com.termux/..., which does not exist in our rebranded
            // prefix.  Repair the links before node/VS Code resolves them.
            repairRipgrep()
            configurePlatformShim(env)

            val userDataDir = File(App.instance.filesDir, ".code-server-data").absolutePath
            val extDir = File(App.instance.filesDir, ".code-server-extensions").absolutePath
            val home = TermuxManager.homeDir

            val process = ProcessBuilder(
                codeServerBin.absolutePath,
                "--bind-addr", "$BIND_HOST:$port",
                "--auth", "none",
                "--disable-telemetry",
                "--disable-update-check",
                "--user-data-dir", userDataDir,
                "--extensions-dir", extDir,
                home.absolutePath
            ).apply {
                directory(home)
                environment().putAll(env)
                redirectErrorStream(true)
            }.start()

            serverProcess = process
            val pid = getPid(process)
            Log.i(TAG, "code-server PID=$pid bound to $BIND_HOST:$port")

            // Monitor output + process exit. Use a local process reference so a
            // restart can never have its status overwritten by the old process.
            monitorJob = scope.launch {
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d(TAG, "code-server: ${line!!}")
                        if (line!!.contains("HTTP server listening") ||
                            line!!.contains("Server listening on")
                        ) {
                            Log.i(TAG, "code-server reported listening")
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "code-server output closed: ${e.message}")
                }
                val exitCode = try { process.waitFor() } catch (_: Exception) { -1 }
                Log.w(TAG, "code-server process exited with code $exitCode")
                if (serverProcess === process && isCurrent(generation)) {
                    serverProcess = null
                    _status.value = ServerStatus(
                        ServerState.STOPPED,
                        "Process exited (code $exitCode)",
                        port,
                        url
                    )
                }
            }

            if (!isCurrent(generation)) {
                Log.i(TAG, "Start superseded after spawn; killing code-server")
                process.destroyForcibly()
                if (serverProcess === process) serverProcess = null
                return@withContext
            }

            waitForServerReady(process, port, generation)

            if (!isCurrent(generation)) {
                Log.i(TAG, "Start superseded while waiting; killing code-server")
                process.destroyForcibly()
                if (serverProcess === process) serverProcess = null
                return@withContext
            }

            _status.value = ServerStatus(ServerState.RUNNING, "Server running", port, url, pid)
        }
    }

    // ─── Runtime repairs / compatibility ─────────────────────────────────────

    /**
     * TUR's code-server package installs @vscode/ripgrep-universal with one
     * absolute symlink per platform directory:
     *
     *   bin/<platform>/rg -> /data/data/com.termux/files/usr/bin/rg
     *
     * Our rebranded prefix is /data/data/com.devbox/files/usr, so those links
     * are dangling and VS Code search fails with ENOENT.  Repair every link
     * (android-x64 today; linux-x64/linux-arm64 once the platform shim is on)
     * so it points at the Termux rg that ships in $PREFIX/bin.  If symlinks are
     * not supported by the filesystem, fall back to copying the real binary.
     */
    private fun repairRipgrep() {
        val realRg = File(TermuxManager.binDir, "rg")
        if (!realRg.isFile) {
            Log.w(TAG, "ripgrep repair skipped: $realRg is missing")
            return
        }
        if (!realRg.canExecute()) realRg.setExecutable(true, false)

        var repaired = 0
        var candidates = 0
        for (root in codeServerPackageRoots()) {
            val binRoot = File(root, "lib/vscode/node_modules/@vscode/ripgrep-universal/bin")
            if (binRoot.isDirectory) {
                binRoot.listFiles()?.forEach { platformDir ->
                    if (platformDir.isDirectory) {
                        candidates++
                        if (repairRipgrepLink(File(platformDir, "rg"), realRg)) repaired++
                    }
                }
            }
            // Older @vscode/ripgrep layout (single binary, no platform dirs).
            val legacy = File(root, "lib/vscode/node_modules/@vscode/ripgrep/bin/rg")
            if (legacy.parentFile?.isDirectory == true) {
                candidates++
                if (repairRipgrepLink(legacy, realRg)) repaired++
            }
        }
        Log.i(TAG, "ripgrep repair: $repaired/$candidates link(s) updated -> $realRg")
        if (repaired > 0) verifyRipgrep(realRg)
    }

    /** Known locations for the code-server package (apt/TUR and npm -g). */
    private fun codeServerPackageRoots(): List<File> {
        val lib = File(TermuxManager.usrDir, "lib")
        return listOf(
            File(lib, "code-server"),              // Termux/TUR apt package
            File(lib, "node_modules/code-server")  // npm install -g code-server
        ).distinct()
    }

    private fun repairRipgrepLink(link: File, realRg: File): Boolean {
        val parent = link.parentFile ?: return false
        if (!parent.isDirectory) return false
        val linkPath = link.toPath()
        val realPath = realRg.toPath().toAbsolutePath().normalize()

        if (Files.isSymbolicLink(linkPath)) {
            val target = try {
                Files.readSymbolicLink(linkPath)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read ripgrep symlink $link: ${e.message}")
                null
            }
            if (target != null) {
                val resolved = try {
                    if (target.isAbsolute) target.normalize()
                    else parent.toPath().resolve(target).normalize()
                } catch (_: Exception) {
                    null
                }
                val pointsAtReal = resolved != null && resolved == realPath
                // /data/user/0/... and /data/data/... are the same file; compare
                // inode identity as well as the textual path.
                val sameFile = try {
                    Files.exists(linkPath) && Files.isSameFile(linkPath, realPath)
                } catch (_: Exception) {
                    false
                }
                if (pointsAtReal || sameFile) return false
            }
        } else if (link.exists()) {
            // A real binary is already present (some packages copy rg instead
            // of symlinking).  Just make sure it is executable.
            if (!link.canExecute()) link.setExecutable(true, false)
            return false
        }

        return try {
            Files.deleteIfExists(linkPath)
            Files.createSymbolicLink(linkPath, realPath)
            Log.i(TAG, "ripgrep: relinked $link -> $realPath")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cannot relink $link (${e.message}); copying rg instead")
            try {
                Files.deleteIfExists(linkPath)
                Files.copy(realPath, linkPath, StandardCopyOption.REPLACE_EXISTING)
                link.setExecutable(true, false)
                link.setReadable(true, false)
                true
            } catch (e2: Exception) {
                Log.e(TAG, "Cannot repair ripgrep path $link", e2)
                false
            }
        }
    }

    /** Log `rg --version` after a repair; failures must not abort startup. */
    private fun verifyRipgrep(realRg: File) {
        try {
            val process = ProcessBuilder(realRg.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            val firstLine = output.lineSequence().firstOrNull().orEmpty()
            if (finished && process.exitValue() == 0) {
                Log.i(TAG, "rg --version OK: $firstLine")
            } else {
                Log.w(TAG, "rg --version failed: $firstLine")
            }
        } catch (e: Exception) {
            Log.w(TAG, "rg --version could not run: ${e.message}")
        }
    }

    /**
     * Adds `NODE_OPTIONS=--require .../android-as-linux.js` so code-server sees
     * platform linux and stops treating Android as target platform "web".
     * Enabled by default; the kill switch is
     * $HOME/.amcode/disable-platform-shim or AMCODE_ANDROID_PLATFORM_SHIM=0.
     * The shim script only changes the main code-server process and the
     * extension host; the integrated terminal, npm, file watcher and pty host
     * keep reporting android.  VS Code strips NODE_OPTIONS from the extension
     * host environment, so the shim also forwards itself through
     * process.execArgv (code-server passes execArgv to forked children).
     */
    private fun configurePlatformShim(env: MutableMap<String, String>) {
        if (!isPlatformShimEnabled(env)) {
            env[PLATFORM_SHIM_ENV] = "0"
            Log.i(
                TAG,
                "Android-as-Linux platform shim disabled; kill switch is " +
                    "${File(TermuxManager.homeDir, PLATFORM_SHIM_DISABLE_FILE)} " +
                    "(or $PLATFORM_SHIM_ENV=0)"
            )
            return
        }

        val shim = installPlatformShimAsset()
        if (shim == null) {
            env[PLATFORM_SHIM_ENV] = "0"
            Log.w(TAG, "Platform shim requested but could not be installed; keeping android platform")
            return
        }

        env[PLATFORM_SHIM_ENV] = "1"
        val existing = env["NODE_OPTIONS"]?.trim().orEmpty()
        val nodeOptions = if (existing.isEmpty()) {
            "--require ${shim.absolutePath}"
        } else {
            "$existing --require ${shim.absolutePath}"
        }
        env["NODE_OPTIONS"] = nodeOptions
        Log.i(TAG, "Android-as-Linux platform shim enabled: NODE_OPTIONS=$nodeOptions")
    }

    private fun isPlatformShimEnabled(env: Map<String, String>): Boolean {
        val appFiles = App.instance.filesDir
        val home = TermuxManager.homeDir

        // Explicit opt-out always wins.
        val disabled = File(home, PLATFORM_SHIM_DISABLE_FILE).exists() ||
            File(appFiles, PLATFORM_SHIM_APP_DISABLE_FILE).exists()
        if (disabled) return false

        val requested = (env[PLATFORM_SHIM_ENV] ?: System.getenv(PLATFORM_SHIM_ENV))
            ?.trim()
            ?.lowercase()
        when (requested) {
            "0", "false", "off", "no" -> return false
            "1", "true", "on", "yes" -> return true
        }

        return PLATFORM_SHIM_DEFAULT_ENABLED
    }

    private fun installPlatformShimAsset(): File? {
        val dest = File(TermuxManager.usrDir, PLATFORM_SHIM_PATH)
        val parent = dest.parentFile ?: return null
        return try {
            parent.mkdirs()
            // Temp file + rename so a killed process cannot leave a truncated
            // require() target behind.
            val tmp = File(parent, "${dest.name}.tmp")
            App.instance.assets.open(PLATFORM_SHIM_ASSET).use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            tmp.setReadable(true, false)
            if (!tmp.renameTo(dest)) {
                dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.delete()
                    Log.w(TAG, "Cannot install platform shim at $dest")
                    return null
                }
            }
            dest.setReadable(true, false)
            dest
        } catch (e: Exception) {
            Log.e(TAG, "Cannot install platform shim from assets", e)
            null
        }
    }

    /**
     * Non-blocking stop: the caller (including the main thread) only requests
     * the stop; destroy/waitFor/kill happen on the launcher's IO scope.
     */
    fun stop() {
        val stopGeneration = nextGeneration()
        monitorJob?.cancel()
        monitorJob = null

        val process = serverProcess
        serverProcess = null
        val previous = _status.value
        _status.value = ServerStatus(
            ServerState.STOPPING,
            "Stopping...",
            previous.port,
            previous.url,
            previous.pid
        )

        if (process == null) {
            _status.value = ServerStatus(
                ServerState.STOPPED,
                "Stopped",
                previous.port,
                previous.url
            )
            return
        }

        // Send the terminate signal immediately (cheap), but do the blocking
        // wait/force-kill on the launcher's IO scope.
        try {
            process.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error sending destroy to code-server", e)
        }

        scope.launch {
            try {
                if (!process.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) {
                    Log.w(TAG, "code-server did not exit in ${STOP_GRACE_SECONDS}s; killing")
                    process.destroyForcibly()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error while waiting for code-server to stop", e)
                try { process.destroyForcibly() } catch (_: Exception) { }
            }
            if (isCurrent(stopGeneration)) {
                _status.value = ServerStatus(
                    ServerState.STOPPED,
                    "Stopped",
                    previous.port,
                    previous.url
                )
            }
        }
    }

    /**
     * Health check verifies BOTH the process and the exact port this launcher
     * owns. A stale/orphan listener on another port can never look healthy.
     */
    suspend fun healthCheck(): Boolean {
        val process = serverProcess ?: return false
        if (!process.isAlive) return false
        val current = _status.value
        if (current.state != ServerState.RUNNING) return false
        return probeHealth(current.port)
    }

    private suspend fun probeHealth(port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://$BIND_HOST:$port/healthz")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            connection.requestMethod = "GET"
            val ok = connection.responseCode == 200
            connection.disconnect()
            ok
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun waitForServerReady(
        process: Process,
        port: Int,
        startGeneration: Int
    ) = withContext(Dispatchers.IO) {
        for (i in 1..READY_TIMEOUT_SECONDS) {
            if (!isCurrent(startGeneration)) return@withContext
            if (!process.isAlive) {
                val exitCode = try { process.exitValue() } catch (_: Exception) { -1 }
                throw RuntimeException(
                    "code-server exited before becoming ready (code $exitCode)"
                )
            }
            if (probeHealth(port)) {
                Log.i(TAG, "Server ready after ${i}s on port $port")
                return@withContext
            }
            delay(1000)
        }
        throw RuntimeException(
            "code-server did not become ready within ${READY_TIMEOUT_SECONDS}s"
        )
    }

    private fun findFreePort(): Int? = try {
        ServerSocket(0, 1, InetAddress.getByName(BIND_HOST)).use { it.localPort }
    } catch (e: Exception) {
        Log.w(TAG, "Cannot allocate a free loopback port", e)
        null
    }

    private fun getPid(p: Process): Int = try {
        val f = p.javaClass.getDeclaredField("pid")
        f.isAccessible = true
        f.getInt(p)
    } catch (_: Exception) {
        -1
    }
}
