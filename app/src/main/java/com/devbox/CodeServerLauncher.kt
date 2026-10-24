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
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
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
