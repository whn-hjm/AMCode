package com.devbox

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages the code-server process lifecycle.
 *
 * Uses code-server installed via Termux apt (pkg install code-server).
 * The binary is at $PREFIX/bin/code-server (bionic-native, no glibc issues).
 *
 * code-server listens on 127.0.0.1:PORT (default 8080).
 */
object CodeServerLauncher {

    private const val TAG = "CodeServerLauncher"
    private const val PORT = 8080

    enum class ServerState {
        STOPPED, STARTING, RUNNING, STOPPING, ERROR
    }

    data class ServerStatus(
        val state: ServerState = ServerState.STOPPED,
        val message: String = "",
        val port: Int = PORT,
        val url: String = "http://127.0.0.1:$PORT",
        val pid: Int = -1
    )

    private val _status = MutableStateFlow(ServerStatus())
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    private var serverProcess: Process? = null
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Path to Termux-installed code-server binary */
    private val codeServerBin: File
        get() = File(TermuxManager.binDir, "code-server")

    fun isInstalled(): Boolean = codeServerBin.exists()

    suspend fun prepareAndStart(): Result<Unit> {
        return try {
            if (!isInstalled()) {
                return Result.failure(IllegalStateException("code-server not installed. Run: pkg install code-server"))
            }
            start()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start code-server", e)
            _status.value = ServerStatus(ServerState.ERROR, e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    suspend fun start() {
        if (serverProcess?.isAlive == true) {
            _status.value = ServerStatus(ServerState.RUNNING, "Already running", pid = getPid(serverProcess!!))
            return
        }

        _status.value = ServerStatus(ServerState.STARTING, "Starting code-server...")

        withContext(Dispatchers.IO) {
            val env = TermuxManager.environment.toMutableMap()
            env["USER"] = "devbox"
            env["SHELL"] = TermuxManager.getShell()
            env["CS_DISABLE_TELEMETRY"] = "true"
            env["CS_DISABLE_UPDATE_CHECK"] = "true"
            // Pretend Linux at OS level — uname() returns "Linux" via LD_PRELOAD
            val preloadLib = File(App.instance.applicationInfo.nativeLibraryDir, "libplatform_fix.so")
            if (preloadLib.exists()) {
                env["LD_PRELOAD"] = preloadLib.absolutePath
            }

            val userDataDir = File(App.instance.filesDir, ".code-server-data").absolutePath
            val extDir = File(App.instance.filesDir, ".code-server-extensions").absolutePath

            serverProcess = ProcessBuilder(
                codeServerBin.absolutePath,
                "--bind-addr", "0.0.0.0:$PORT",
                "--auth", "none",
                "--disable-telemetry",
                "--disable-update-check",
                "--user-data-dir", userDataDir,
                "--extensions-dir", extDir,
                TermuxManager.homeDir.absolutePath
            ).apply {
                directory(TermuxManager.homeDir)
                environment().putAll(env)
                redirectErrorStream(true)
            }.start()

            val pid = getPid(serverProcess!!)
            Log.i(TAG, "code-server PID=$pid")

            // Monitor output + detect process crash
            monitorJob = scope.launch {
                val reader = BufferedReader(InputStreamReader(serverProcess!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.d(TAG, "code-server: ${line!!}")
                    if (line!!.contains("HTTP server listening") ||
                        line!!.contains("Server listening on")) {
                        Log.i(TAG, "code-server is ready")
                    }
                }
                // Process exited — mark as stopped so health check can restart
                val exitCode = try { serverProcess?.waitFor() } catch (_: Exception) { -1 }
                Log.w(TAG, "code-server process exited with code $exitCode")
                _status.value = ServerStatus(ServerState.STOPPED, "Process exited (code $exitCode)")
            }

            // Wait for server
            waitForServerReady()

            _status.value = ServerStatus(ServerState.RUNNING, "Server running", pid = pid)
        }
    }

    fun stop() {
        _status.value = ServerStatus(ServerState.STOPPING, "Stopping...")
        monitorJob?.cancel()
        serverProcess?.let {
            it.destroy()
            if (!it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                it.destroyForcibly()
            }
        }
        serverProcess = null
        _status.value = ServerStatus(ServerState.STOPPED, "Stopped")
    }

    suspend fun healthCheck(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://127.0.0.1:$PORT/healthz")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 2000; c.readTimeout = 2000
            val ok = c.responseCode == 200
            c.disconnect(); ok
        } catch (_: Exception) { false }
    }

    private suspend fun waitForServerReady() = withContext(Dispatchers.IO) {
        for (i in 1..60) {
            if (healthCheck()) { Log.i(TAG, "Server ready after ${i}s"); return@withContext }
            delay(1000)
        }
        throw RuntimeException("code-server did not become ready within 60s")
    }

    private fun getPid(p: Process): Int = try {
        val f = p.javaClass.getDeclaredField("pid"); f.isAccessible = true; f.getInt(p)
    } catch (_: Exception) { -1 }
}
