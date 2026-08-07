package com.devbox

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground Service that manages the code-server process lifecycle.
 *
 * Responsibilities:
 * - Start/stop code-server
 * - Keep process alive via foreground notification
 * - Auto-restart on crash
 * - Report status to bound activities
 */
class CodeServerService : Service() {

    // Service state enum
    enum class ServiceState {
        INITIALIZING,
        SETTING_UP_ENV,
        STARTING_SERVER,
        RUNNING,
        STOPPING,
        STOPPED,
        ERROR
    }

    companion object {
        const val TAG = "CodeServerService"
        const val ACTION_STOP = "com.devbox.action.STOP"
    }

    data class ServiceStatus(
        val state: ServiceState = ServiceState.INITIALIZING,
        val message: String = "Initializing...",
        val progress: Int = 0,
        val serverUrl: String = ""
    )

    private val _serviceStatus = MutableStateFlow(ServiceStatus())
    val serviceStatus: StateFlow<ServiceStatus> = _serviceStatus.asStateFlow()

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var healthCheckJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): CodeServerService = this@CodeServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(
                    App.NOTIFICATION_ID,
                    createNotification("Starting VS Code...", "Initializing environment")
                )
                initializeAndStart()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopServer()
        scope.cancel()
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    /**
     * Full initialization pipeline: setup Termux env → download code-server → start
     */
    private fun initializeAndStart() {
        scope.launch {
            try {
                // Launch a collector to update UI from TermuxManager progress
                val envCollector = launch {
                    TermuxManager.setupState.collect { progress ->
                        when (progress.state) {
                            TermuxManager.State.DOWNLOADING_BOOTSTRAP,
                            TermuxManager.State.EXTRACTING,
                            TermuxManager.State.PATCHING,
                            TermuxManager.State.INSTALLING_PACKAGES -> {
                                _serviceStatus.value = ServiceStatus(
                                    ServiceState.SETTING_UP_ENV,
                                    progress.message,
                                    progress.percent.coerceIn(0, 100)
                                )
                                updateNotification(progress.message, "${progress.percent}%")
                            }
                            TermuxManager.State.READY -> { /* handled below */ }
                            TermuxManager.State.FAILED -> { /* handled via Result */ }
                            else -> { /* ignore */ }
                        }
                    }
                }

                // Phase 1: Initialize Termux environment
                _serviceStatus.value = ServiceStatus(
                    ServiceState.SETTING_UP_ENV, "Setting up environment...", 5
                )
                updateNotification("Setting up development environment...", "Extracting packages")

                val envResult = TermuxManager.initialize(this@CodeServerService)
                envCollector.cancel()
                if (envResult.isFailure) {
                    throw envResult.exceptionOrNull()!!
                }

                // Packages are verified by initialize() — no separate retry needed

                // Launch a collector for code-server status
                val serverCollector = launch {
                    CodeServerLauncher.status.collect { serverStatus ->
                        when (serverStatus.state) {
                            CodeServerLauncher.ServerState.RUNNING -> {
                                _serviceStatus.value = ServiceStatus(
                                    ServiceState.RUNNING,
                                    "VS Code is running", 100, serverStatus.url
                                )
                                updateNotification("VS Code is ready", serverStatus.url)
                            }
                            CodeServerLauncher.ServerState.ERROR -> { /* handled via Result */ }
                            else -> {
                                _serviceStatus.value = ServiceStatus(
                                    ServiceState.STARTING_SERVER,
                                    serverStatus.message,
                                    60
                                )
                                updateNotification(serverStatus.message, "Starting...")
                            }
                        }
                    }
                }

                // Phase 2: Start code-server
                _serviceStatus.value = ServiceStatus(
                    ServiceState.STARTING_SERVER, "Starting code-server...", 60
                )
                updateNotification("Starting code-server...", "Launching VS Code")

                val serverResult = CodeServerLauncher.prepareAndStart()
                serverCollector.cancel()
                if (serverResult.isFailure) {
                    throw serverResult.exceptionOrNull()!!
                }

                // Phase 3: Start health checks
                startHealthChecks()

                Log.i(TAG, "Service fully initialized and running")

            } catch (e: Exception) {
                Log.e(TAG, "Service initialization failed", e)
                _serviceStatus.value = ServiceStatus(
                    ServiceState.ERROR, "Failed: ${e.message}", 0
                )
                updateNotification("VS Code failed to start", e.message ?: "Unknown error")
                stopSelf()
            }
        }
    }

    private var restartCount = 0

    private fun startHealthChecks() {
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(10000)  // Check every 10 seconds
                val healthy = CodeServerLauncher.healthCheck()
                if (!healthy && _serviceStatus.value.state == ServiceState.RUNNING) {
                    Log.w(TAG, "Health check failed, restarting...")
                    restartCount++
                    if (restartCount > 10) {
                        Log.e(TAG, "Too many restarts, giving up")
                        _serviceStatus.value = ServiceStatus(ServiceState.ERROR, "Too many crashes")
                        stopSelf()
                        return@launch
                    }
                    CodeServerLauncher.stop()
                    delay(2000)  // Wait for port to free
                    val restartResult = CodeServerLauncher.prepareAndStart()
                    if (restartResult.isFailure) {
                        Log.e(TAG, "Restart failed", restartResult.exceptionOrNull())
                    } else {
                        _serviceStatus.value = ServiceStatus(ServiceState.RUNNING, "VS Code restarted", 100,
                            CodeServerLauncher.status.value.url)
                        updateNotification("VS Code restarted", "Recovered from crash")
                    }
                }
                if (healthy && _serviceStatus.value.state != ServiceState.RUNNING) {
                    restartCount = 0
                    _serviceStatus.value = ServiceStatus(ServiceState.RUNNING, "VS Code is running", 100,
                        CodeServerLauncher.status.value.url)
                }
            }
        }
    }

    private fun stopServer() {
        healthCheckJob?.cancel()
        CodeServerLauncher.stop()
        _serviceStatus.value = ServiceStatus(ServiceState.STOPPED, "Stopped", 0)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ─── Notification helpers ─────────────────────────────────

    private fun createNotification(title: String, content: String) =
        NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, CodeServerService::class.java).apply { action = ACTION_STOP },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun updateNotification(title: String, content: String) {
        val notification = createNotification(title, content)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(App.NOTIFICATION_ID, notification)
    }
}
