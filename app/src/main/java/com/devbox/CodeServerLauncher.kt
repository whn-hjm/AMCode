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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
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

    // ─── Loopback authentication ─────────────────────────────────────────────
    // code-server is started with `--auth password` and a fresh random token
    // written to an app-private 0600 YAML config file. The token is never put
    // on the command line and never logged. After the server is ready the
    // launcher performs the normal code-server login POST, verifies the
    // session cookie, and hands only the cookie value to the WebView.
    private const val AUTH_COOKIE_NAME = "code-server-session"
    private const val AUTH_CONFIG_FILE = "code-server.yaml"
    private const val AUTH_LOGIN_ATTEMPTS = 3
    private const val AUTH_TOKEN_BYTES = 32
    private const val HEX_CHARS = "0123456789abcdef"
    private const val HTTP_SEE_OTHER = 303

    // ─── Orphan process cleanup ──────────────────────────────────────────────
    private const val RUN_DIR = "run"
    private const val PID_FILE = "code-server.pid"
    private const val ORPHAN_TERM_GRACE_MS = 3000L

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

    /** Random per-launch password. Never log this value. */
    @Volatile
    private var authToken: String? = null

    /** Validated `code-server-session` cookie value for [sessionPort]. */
    @Volatile
    private var sessionCookie: String? = null

    @Volatile
    private var sessionPort: Int = -1

    /** HOME the live server was started with; null when no server is known. */
    @Volatile
    private var activeHomePath: String? = null

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

    /**
     * Cookie value for the currently running server, or null when no session
     * has been established. The WebView must install this cookie before its
     * first navigation; the token itself is never exposed to the caller.
     */
    fun currentSessionCookie(): String? = sessionCookie

    /** Absolute HOME path of the currently running server, if known. */
    fun activeHomePath(): String? = activeHomePath

    /**
     * Re-run the loopback login POST and return a fresh cookie. Used by the
     * Activity when a WebView navigation unexpectedly reaches /login (for
     * example after a stale cookie survived a port change).
     */
    suspend fun refreshSessionCookie(): String? = withContext(Dispatchers.IO) {
        val token = authToken ?: return@withContext null
        val port = sessionPort.takeIf { it > 0 } ?: return@withContext null
        val cookie = loginAndVerify(port, token) ?: return@withContext null
        sessionCookie = cookie
        Log.i(TAG, "Refreshed code-server session cookie (value redacted)")
        cookie
    }

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
        if (alreadyRunning != null && alreadyRunning.isAlive &&
            _status.value.state == ServerState.RUNNING
        ) {
            _status.value = ServerStatus(
                ServerState.RUNNING,
                "Already running",
                _status.value.port,
                _status.value.url,
                getPid(alreadyRunning)
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
            // Kill any code-server left behind by a previous app process before
            // choosing a port or writing the new auth config. This also removes
            // an old `--auth none` server that would otherwise stay reachable
            // on loopback.
            cleanupStaleCodeServerProcesses()

            // Prefer a real free port; 8080 remains only a last-resort fallback.
            val port = findFreePort() ?: DEFAULT_PORT
            val url = "http://$BIND_HOST:$port"

            if (!isCurrent(generation)) {
                Log.i(TAG, "Start superseded before spawn")
                return@withContext
            }

            // Fresh random token per launch. Only its SHA-256 is written to
            // the app-private 0600 config; the token itself stays in memory
            // and is never in argv or logcat.
            val token = generateAuthToken()
            val authConfig = writeAuthConfig(port, token)
            if (authConfig == null) {
                throw RuntimeException("Cannot write code-server authentication config")
            }
            authToken = token
            sessionCookie = null
            sessionPort = port

            _status.value = ServerStatus(ServerState.STARTING, "Starting code-server...", port, url)

            val env = TermuxManager.environment.toMutableMap()
            env["USER"] = "devbox"
            env["SHELL"] = TermuxManager.getShell()
            env["CS_DISABLE_TELEMETRY"] = "true"
            env["CS_DISABLE_UPDATE_CHECK"] = "true"
            // Do not inherit a plaintext password from the app environment;
            // authentication comes from the 0600 config file instead.
            env.remove("PASSWORD")
            env.remove("HASHED_PASSWORD")

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
                "--auth", "password",
                "--config", authConfig.absolutePath,
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
            activeHomePath = home.absolutePath
            val pid = getPid(process)
            writePidFile(pid, port)
            Log.i(TAG, "code-server PID=$pid bound to $BIND_HOST:$port (auth=password)")

            // Monitor output + process exit. Use a local process reference so a
            // restart can never have its status overwritten by the old process.
            monitorJob = scope.launch {
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val raw = line!!
                        // Defence in depth: code-server redacts passwords
                        // itself, but never let our token reach logcat if a
                        // future version prints it.
                        val safe = if (raw.contains(token)) {
                            raw.replace(token, "<redacted>")
                        } else {
                            raw
                        }
                        Log.d(TAG, "code-server: $safe")
                        if (raw.contains("HTTP server listening") ||
                            raw.contains("Server listening on")
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
                    activeHomePath = null
                    removePidFile(pid)
                    sessionCookie = null
                    sessionPort = -1
                    authToken = null
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
                abortStartedProcess(process, pid)
                return@withContext
            }

            waitForServerReady(process, port, generation)

            if (!isCurrent(generation)) {
                Log.i(TAG, "Start superseded while waiting; killing code-server")
                abortStartedProcess(process, pid)
                return@withContext
            }

            // Perform the same login POST a browser would, then verify the
            // resulting session cookie against an authenticated endpoint. A
            // server that is up but cannot be authenticated must never be
            // exposed to the WebView as "ready".
            val cookie = loginAndVerify(port, token)
            if (cookie == null) {
                abortStartedProcess(process, pid)
                throw RuntimeException("code-server password authentication failed")
            }
            sessionCookie = cookie
            sessionPort = port
            Log.i(TAG, "code-server session established (cookie len=${cookie.length}, value redacted)")

            _status.value = ServerStatus(ServerState.RUNNING, "Server running", port, url, pid)
        }
    }

    /** Kill a process that was spawned but must not become the live server. */
    private fun abortStartedProcess(process: Process, pid: Int) {
        try {
            process.destroyForcibly()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot kill aborted code-server", e)
        }
        if (serverProcess === process) serverProcess = null
        if (serverProcess == null) activeHomePath = null
        removePidFile(pid)
        sessionCookie = null
        sessionPort = -1
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
     *
     * The PID file and the 0600 auth config are removed after the process tree
     * is gone. If there is no live Process reference (for example the service
     * is destroyed after the app process restarted) a validated orphan sweep
     * still runs so a previous `code-server` cannot stay reachable on loopback.
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

        // Send the terminate signal immediately (cheap), but the blocking
        // wait/force-kill happens on the launcher's IO scope.
        try {
            process?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error sending destroy to code-server", e)
        }

        scope.launch {
            val proc = process
            val pid = if (proc != null) getPid(proc) else previous.pid
            if (proc != null) {
                try {
                    if (!proc.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) {
                        Log.w(TAG, "code-server did not exit in ${STOP_GRACE_SECONDS}s; killing")
                        proc.destroyForcibly()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error while waiting for code-server to stop", e)
                    try { proc.destroyForcibly() } catch (_: Exception) { }
                }
            }

            val livePid = serverProcess?.let { getPid(it) } ?: -1
            // A global signature sweep is only safe while this stop still owns
            // the launcher generation; a superseded stop may otherwise kill the
            // server a new start just spawned.
            val sweep = isCurrent(stopGeneration)
            killCodeServerProcessTree(pid, livePid, sweep)
            if (pid > 0) {
                removePidFile(pid)
            } else if (sweep) {
                // Never delete the PID file a newer start may just have written.
                removePidFile()
            }

            if (isCurrent(stopGeneration)) {
                deleteAuthConfig()
                sessionCookie = null
                authToken = null
                sessionPort = -1
                activeHomePath = null
                _status.value = ServerStatus(
                    ServerState.STOPPED,
                    "Stopped",
                    previous.port,
                    previous.url
                )
            } else {
                Log.d(TAG, "Stop superseded by a new start; keeping new runtime files")
            }
        }
    }

    // ─── Orphan / PID file cleanup ───────────────────────────────────────────

    private data class PidRecord(
        val pid: Int,
        val startTime: String?,
        val port: Int
    )

    private fun runDir(): File = File(App.instance.filesDir, RUN_DIR)

    private fun pidFile(): File = File(runDir(), PID_FILE)

    /**
     * The auth config lives in the cache directory: it is re-created for every
     * launch, and the cache domain is excluded from Android Auto Backup so a
     * token can never be restored to another device. The process reads it only
     * during startup; the token is kept in memory afterwards.
     */
    private fun authConfigFile(): File =
        File(File(App.instance.cacheDir, RUN_DIR), AUTH_CONFIG_FILE)

    private fun authConfigTmpFile(): File =
        File(authConfigFile().parentFile, "$AUTH_CONFIG_FILE.tmp")

    /** Old location from earlier builds; removed on cleanup as well. */
    private fun legacyAuthConfigFile(): File = File(runDir(), AUTH_CONFIG_FILE)

    private fun writePidFile(pid: Int, port: Int) {
        if (pid <= 1) {
            Log.w(TAG, "Not writing PID file for invalid pid=$pid")
            return
        }
        try {
            val dir = runDir()
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "Cannot create ${dir.absolutePath}")
                return
            }
            val record = "pid=$pid\n" +
                "start=${readProcStartTime(pid) ?: ""}\n" +
                "port=$port\n" +
                "bin=${codeServerBin.absolutePath}\n"
            val tmp = File(dir, "$PID_FILE.tmp")
            tmp.writeText(record)
            restrictRuntimeFile(tmp)
            val dest = pidFile()
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                Log.w(TAG, "Cannot write PID file ${dest.absolutePath}")
                return
            }
            restrictRuntimeFile(dest)
            Log.i(TAG, "Wrote ${dest.absolutePath} (pid=$pid port=$port)")
        } catch (e: Exception) {
            Log.w(TAG, "Cannot write PID file", e)
        }
    }

    private fun readPidRecord(): PidRecord? {
        return try {
            val file = pidFile()
            if (!file.isFile) return null
            var pid = -1
            var start: String? = null
            var port = -1
            file.readLines().forEach { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) return@forEach
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                when (key) {
                    "pid" -> pid = value.toIntOrNull() ?: -1
                    "start" -> start = value.ifBlank { null }
                    "port" -> port = value.toIntOrNull() ?: -1
                }
            }
            if (pid > 1) PidRecord(pid, start, port) else null
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read PID file", e)
            null
        }
    }

    /**
     * Remove the PID file only when it still describes [pid] (or when [pid] is
     * negative, i.e. unconditional cleanup).
     */
    private fun removePidFile(pid: Int = -1) {
        try {
            val file = pidFile()
            if (!file.exists()) return
            if (pid > 0) {
                val record = readPidRecord()
                if (record != null && record.pid != pid) {
                    Log.d(TAG, "Keeping PID file for newer pid=${record.pid}")
                    return
                }
            }
            if (file.delete()) {
                Log.i(TAG, "Removed PID file ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot remove PID file", e)
        }
    }

    private fun deleteAuthConfig() {
        for (file in listOf(authConfigFile(), authConfigTmpFile(), legacyAuthConfigFile())) {
            try {
                if (file.exists() && file.delete()) {
                    Log.d(TAG, "Removed code-server auth config")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot remove code-server auth config", e)
            }
        }
    }

    /** Kill stale code-server processes before spawning a new one. */
    private fun cleanupStaleCodeServerProcesses() {
        val recorded = readPidRecord()
        val targets = LinkedHashSet<Int>()
        recorded?.pid?.let { if (it > 1) targets.add(it) }
        targets.addAll(collectSignedCodeServerPids())

        val livePid = serverProcess?.let { getPid(it) } ?: -1
        val exclude = setOf(android.os.Process.myPid(), livePid)
        val validated = targets.filter { pid ->
            if (pid <= 1 || pid in exclude) {
                false
            } else {
                val cmd = readCmdline(pid)
                if (cmd == null) {
                    // Cannot validate: never signal an unknown process.
                    Log.w(TAG, "Skipping unreadable process $pid during cleanup")
                    false
                } else {
                    val recordedMatch = recorded?.pid == pid &&
                        (recorded.startTime == null ||
                            recorded.startTime == readProcStartTime(pid))
                    isCodeServerCmdline(cmd) || recordedMatch
                }
            }
        }

        if (validated.isNotEmpty()) {
            Log.i(TAG, "Cleaning up ${validated.size} stale code-server process(es)")
            signalAndWait(validated)
        }
        removePidFile()
        deleteAuthConfig()
    }

    /**
     * Kill [rootPid] and (optionally) every process whose command line matches
     * this app's code-server installation. [livePid] is never signalled.
     */
    private fun killCodeServerProcessTree(rootPid: Int, livePid: Int, sweep: Boolean) {
        val candidates = LinkedHashSet<Int>()
        if (rootPid > 1) candidates.add(rootPid)
        if (sweep) candidates.addAll(collectSignedCodeServerPids())
        if (rootPid > 1) candidates.addAll(collectDescendantPids(rootPid))

        val exclude = setOf(android.os.Process.myPid(), livePid)
        val validated = candidates.filter { pid ->
            if (pid <= 1 || pid in exclude) {
                false
            } else {
                // Re-validate immediately before signalling: a recycled PID
                // that is not code-server must never be killed.
                readCmdline(pid)?.let { isCodeServerCmdline(it) } == true
            }
        }
        if (validated.isEmpty()) return
        Log.i(TAG, "Stopping ${validated.size} code-server process(es): $validated")
        signalAndWait(validated)
    }

    /** SIGTERM, wait up to [ORPHAN_TERM_GRACE_MS], then SIGKILL survivors. */
    private fun signalAndWait(pids: Collection<Int>) {
        val ordered = pids.distinct().sortedDescending()
        ordered.forEach { sendSignal(it, "TERM") }

        val deadline = System.currentTimeMillis() + ORPHAN_TERM_GRACE_MS
        var survivors = ordered.filter { isProcessAlive(it) }
        while (survivors.isNotEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            survivors = survivors.filter { isProcessAlive(it) }
        }
        if (survivors.isNotEmpty()) {
            Log.w(TAG, "Force-killing code-server process(es): $survivors")
            survivors.forEach { sendSignal(it, "KILL") }
        }
    }

    private fun sendSignal(pid: Int, signal: String): Boolean {
        if (pid <= 1 || pid == android.os.Process.myPid()) return false
        return try {
            val process = ProcessBuilder("/system/bin/kill", "-$signal", pid.toString())
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            finished && process.exitValue() == 0
        } catch (e: Exception) {
            Log.w(TAG, "Cannot send SIG$signal to $pid", e)
            false
        }
    }

    private fun isProcessAlive(pid: Int): Boolean {
        val cmd = readCmdline(pid) ?: return false
        return cmd.isNotBlank()
    }

    private fun readCmdline(pid: Int): String? {
        if (pid <= 1) return null
        return try {
            val bytes = File("/proc/$pid/cmdline").readBytes()
            if (bytes.isEmpty()) return null
            String(bytes, StandardCharsets.UTF_8)
                .replace('\u0000', ' ')
                .trim()
        } catch (_: Exception) {
            null
        }
    }

    private fun readProcStartTime(pid: Int): String? {
        if (pid <= 1) return null
        return try {
            val stat = File("/proc/$pid/stat").readText()
            val end = stat.lastIndexOf(')')
            if (end < 0) return null
            // Field 3 is the first token after "comm)": field 22 (starttime)
            // is therefore at index 22 - 3 = 19.
            stat.substring(end + 2).trim().split(' ').getOrNull(19)
        } catch (_: Exception) {
            null
        }
    }

    private fun collectSignedCodeServerPids(): List<Int> {
        val result = ArrayList<Int>()
        val proc = File("/proc")
        val entries = proc.listFiles() ?: return result
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (pid <= 1 || pid == android.os.Process.myPid()) continue
            val cmd = readCmdline(pid) ?: continue
            if (isCodeServerCmdline(cmd)) result.add(pid)
        }
        return result
    }

    /** Collect direct/indirect children of [rootPid] (best effort). */
    private fun collectDescendantPids(rootPid: Int): List<Int> {
        val result = ArrayList<Int>()
        val queue = ArrayDeque<Int>()
        queue.add(rootPid)
        val seen = HashSet<Int>()
        while (queue.isNotEmpty() && seen.size < 128) {
            val parent = queue.removeFirst()
            if (!seen.add(parent)) continue
            try {
                val children = File("/proc/$parent/task/$parent/children")
                    .readText()
                    .trim()
                    .split(' ')
                    .mapNotNull { it.toIntOrNull() }
                for (child in children) {
                    if (child > 1 && child != android.os.Process.myPid()) {
                        result.add(child)
                        queue.add(child)
                    }
                }
            } catch (_: Exception) {
                // Process already gone or children file unavailable.
            }
        }
        return result
    }

    /**
     * A process belongs to this app's code-server installation when its
     * command line contains our package data path AND a code-server path.
     * This intentionally does NOT match plain Termux shells or editors, so an
     * unrelated user terminal survives a cleanup.
     */
    private fun isCodeServerCmdline(cmd: String): Boolean {
        if (cmd.isBlank()) return false
        if (!cmd.contains(App.instance.packageName)) return false
        return cmd.contains("/usr/lib/code-server/") ||
            cmd.contains("/.code-server-data") ||
            cmd.contains("libexec/amcode/android-as-linux.js")
    }

    // ─── Authentication helpers ──────────────────────────────────────────────

    /** 32 random bytes as 64 lowercase hex characters. */
    private fun generateAuthToken(): String {
        val bytes = ByteArray(AUTH_TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        val builder = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val value = b.toInt() and 0xff
            builder.append(HEX_CHARS[value ushr 4])
            builder.append(HEX_CHARS[value and 0x0f])
        }
        return builder.toString()
    }

    /** SHA-256 of the login token, lowercase hex (code-server SHA256 mode). */
    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
        val builder = StringBuilder(digest.size * 2)
        for (b in digest) {
            val value = b.toInt() and 0xff
            builder.append(HEX_CHARS[value ushr 4])
            builder.append(HEX_CHARS[value and 0x0f])
        }
        return builder.toString()
    }

    /**
     * Write the per-launch password hash into an app-private YAML config.
     * Using a config file (instead of `PASSWORD` in the environment/argv)
     * keeps the token out of the process command line; the file is 0600 and
     * removed on stop. code-server's own debug logging redacts passwords.
     *
     * `hashed-password` (SHA-256) is used instead of a plaintext `password`:
     * with a plaintext password code-server runs argon2.verify() on EVERY
     * authenticated request (measured ~140 ms per request on the emulator),
     * while an ARGON2/SHA256 hashed-password uses a constant-time string
     * comparison (~3 ms). The login password is a 256-bit random token, so a
     * preimage-resistant hash is sufficient; there is no human password to
     * brute-force. code-server 4.143.1 supports this legacy config path.
     */
    private fun writeAuthConfig(port: Int, token: String): File? {
        return try {
            val dir = authConfigFile().parentFile
            if (dir == null || (!dir.exists() && !dir.mkdirs())) {
                Log.e(TAG, "Cannot create auth config directory")
                return null
            }
            val dest = authConfigFile()
            val tmp = authConfigTmpFile()
            val hashedPassword = sha256Hex(token)
            // The hash is hex, but quoting also prevents any YAML numeric
            // interpretation for future token formats.
            tmp.writeText(
                "auth: password\n" +
                    "hashed-password: \"$hashedPassword\"\n" +
                    "bind-addr: $BIND_HOST:$port\n"
            )
            restrictRuntimeFile(tmp)
            if (dest.exists() && !dest.delete()) {
                tmp.delete()
                Log.e(TAG, "Cannot replace ${dest.absolutePath}")
                return null
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                Log.e(TAG, "Cannot install ${dest.absolutePath}")
                return null
            }
            restrictRuntimeFile(dest)
            dest
        } catch (e: Exception) {
            Log.e(TAG, "Cannot write code-server auth config", e)
            null
        }
    }

    private fun restrictRuntimeFile(file: File) {
        try {
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setExecutable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot restrict permissions on ${file.name}", e)
        }
    }

    /**
     * Perform code-server's normal browser login:
     *   POST /login (application/x-www-form-urlencoded, password=<token>)
     *   -> 302 + Set-Cookie: code-server-session=<sha256 hex>
     * The cookie is then verified against POST /mint-key, which requires auth
     * and returns 401/403 without a valid session.
     */
    private fun loginAndVerify(port: Int, token: String): String? {
        var attempt = 0
        while (attempt < AUTH_LOGIN_ATTEMPTS) {
            attempt++
            val cookie = postLogin(port, token)
            if (cookie != null && verifySession(port, cookie)) {
                Log.i(TAG, "Password login to code-server succeeded (attempt $attempt)")
                return cookie
            }
            Log.w(TAG, "Password login attempt $attempt failed")
            try {
                Thread.sleep(400L * attempt)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return null
    }

    private fun postLogin(port: Int, token: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val body = "password=" + URLEncoder.encode(token, StandardCharsets.UTF_8.name())
            val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
            val conn = URL("http://$BIND_HOST:$port/login")
                .openConnection() as HttpURLConnection
            connection = conn
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Accept", "text/html")
            conn.setFixedLengthStreamingMode(bodyBytes.size)
            conn.outputStream.use { it.write(bodyBytes) }

            val status = conn.responseCode
            if (status != HttpURLConnection.HTTP_MOVED_TEMP && status != HTTP_SEE_OTHER) {
                Log.w(TAG, "Login POST returned HTTP $status (expected redirect)")
                return null
            }
            var setCookie: String? = null
            for ((name, values) in conn.headerFields) {
                if (name != null && name.equals("Set-Cookie", ignoreCase = true)) {
                    setCookie = values?.firstOrNull()
                    break
                }
            }
            val pair = setCookie?.substringBefore(';')?.trim().orEmpty()
            if (!pair.startsWith("$AUTH_COOKIE_NAME=")) {
                Log.w(TAG, "Login response did not include $AUTH_COOKIE_NAME")
                return null
            }
            pair.substringAfter('=', "").ifBlank { null }
        } catch (e: Exception) {
            Log.w(TAG, "Login POST failed: ${e.javaClass.simpleName}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun verifySession(port: Int, cookieValue: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val conn = URL("http://$BIND_HOST:$port/mint-key")
                .openConnection() as HttpURLConnection
            connection = conn
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(0)
            conn.setRequestProperty("Cookie", "$AUTH_COOKIE_NAME=$cookieValue")
            conn.outputStream.use { }
            val status = conn.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Authenticated probe returned HTTP $status")
                return false
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Authenticated probe failed: ${e.javaClass.simpleName}")
            false
        } finally {
            connection?.disconnect()
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
