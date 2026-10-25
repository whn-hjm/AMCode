package com.devbox

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground Service that manages the code-server process lifecycle.
 *
 * Responsibilities:
 * - Start/stop code-server
 * - Keep process alive via foreground notification
 * - Auto-restart on crash (bounded)
 * - Report status to bound activities
 *
 * Initialization is single-flight: repeated onStartCommand() calls cannot run
 * two concurrent bootstrap/apt/server starts.
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
        const val ACTION_RESTART = "com.devbox.action.RESTART"
        const val ACTION_OPEN_HOME_SETTINGS = "com.devbox.action.OPEN_HOME_SETTINGS"
        private const val HEALTH_INTERVAL_MS = 10_000L
        private const val RESTART_DELAY_MS = 2_000L
        private const val MAX_RESTARTS = 10
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var initJob: Job? = null

    @Volatile
    private var healthCheckJob: Job? = null

    @Volatile
    private var restartCount = 0

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
                Log.i(TAG, "ACTION_STOP received")
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                Log.i(TAG, "ACTION_RESTART received (HOME change or auth recovery)")
                startForeground(
                    App.NOTIFICATION_ID,
                    createNotification("Restarting VS Code...", "Applying workspace storage")
                )
                _serviceStatus.value = ServiceStatus(
                    ServiceState.INITIALIZING, "Restarting VS Code...", 0
                )
                // Cancel the current pipeline and wait for it to unwind before
                // a new one starts, otherwise two servers could be spawned.
                val previous = initJob
                previous?.cancel()
                initJob = null
                healthCheckJob?.cancel()
                healthCheckJob = null
                restartCount = 0
                CodeServerLauncher.stop()
                initializeAndStart(waitFor = previous)
                return START_STICKY
            }
            else -> {
                // Never enter a second initialization pipeline.
                if (initJob?.isActive == true) {
                    Log.i(TAG, "Initialization already active; ignoring duplicate start")
                    return START_STICKY
                }

                val state = _serviceStatus.value.state
                startForeground(
                    App.NOTIFICATION_ID,
                    if (state == ServiceState.RUNNING) {
                        createNotification("VS Code is running", "Server is ready")
                    } else {
                        createNotification("Starting VS Code...", "Initializing environment")
                    }
                )

                if (state == ServiceState.RUNNING) {
                    Log.i(TAG, "Service already running; ensuring health checks")
                    startHealthChecks()
                    return START_STICKY
                }

                initializeAndStart()
                return START_STICKY
            }
        }
    }

    /**
     * Task removal must not leave an orphan code-server behind. The activity
     * owns the task; when the user swipes it away we stop the server and the
     * service (the manifest also sets stopWithTask for the same guarantee).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "Task removed; stopping code-server to avoid an orphan process")
        stopEverything()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroying")
        initJob?.cancel()
        initJob = null
        healthCheckJob?.cancel()
        healthCheckJob = null
        // Non-blocking: the launcher kills the process on its own IO scope even
        // after this service (and its scope) is gone.
        CodeServerLauncher.stop()
        _serviceStatus.value = ServiceStatus(ServiceState.STOPPED, "Stopped", 0)
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    /**
     * Full initialization pipeline: restore HOME → setup Termux env → start
     * code-server. Guarded by initJob so only one pipeline can run at a time.
     *
     * [waitFor] is an already-cancelled previous pipeline (HOME switch); the
     * new pipeline joins it first so two servers can never be spawned.
     */
    private fun initializeAndStart(waitFor: Job? = null) {
        if (initJob?.isActive == true) {
            Log.i(TAG, "initializeAndStart ignored: already active")
            return
        }
        // A previous health-check loop must not race this initialization.
        healthCheckJob?.cancel()
        healthCheckJob = null

        initJob = scope.launch {
            try {
                if (waitFor != null) {
                    try {
                        // The cancelled pipeline's blocking sections (apt/dpkg)
                        // do not observe coroutine cancellation promptly; do
                        // not let this pipeline (and its UI) wait on them
                        // forever — e.g. when the user switches HOME while the
                        // first install is still downloading.
                        withTimeoutOrNull(15_000) { waitFor.join() }
                        if (waitFor.isActive) {
                            Log.w(TAG, "Previous pipeline still unwinding; starting anyway")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    }
                }
                _serviceStatus.value = ServiceStatus(
                    ServiceState.INITIALIZING, "Initializing...", 0
                )

                // Restore the persisted HOME before anything spawns code-server
                // or creates Termux directories. This also covers START_STICKY
                // restarts that arrive with a null Intent.
                val restored = HomePrefs.restoreHomeDir(this@CodeServerService)
                Log.i(
                    TAG,
                    "Initialization HOME=${restored.home.absolutePath} " +
                        "external=${restored.external} fallback=${restored.externalFallback}"
                )
                if (restored.externalFallback) {
                    // Keep the persisted external choice: an Activity can prompt
                    // for the missing grant without files being moved.
                    updateNotification(
                        "External workspace unavailable",
                        "Using internal HOME for this launch"
                    )
                }

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
                            else -> { /* READY/FAILED handled by Result below */ }
                        }
                    }
                }

                // Phase 1: Initialize Termux environment
                _serviceStatus.value = ServiceStatus(
                    ServiceState.SETTING_UP_ENV, "Setting up environment...", 5
                )
                updateNotification("Setting up development environment...", "Extracting packages")

                val envResult = try {
                    TermuxManager.initialize(this@CodeServerService)
                } finally {
                    envCollector.cancel()
                }
                if (envResult.isFailure) {
                    throw envResult.exceptionOrNull()
                        ?: RuntimeException("Environment setup failed")
                }

                // Bootstrap extraction can (re)create HOME subdirectories, so
                // make sure the Termux-style ~/storage/shared link is present
                // before any terminal or extension starts. This is internal
                // HOME only; an external HOME is already under /sdcard.
                if (!restored.external) {
                    HomePrefs.ensureStorageLayout(TermuxManager.homeDir)
                }

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
                            CodeServerLauncher.ServerState.ERROR,
                            CodeServerLauncher.ServerState.STOPPED,
                            CodeServerLauncher.ServerState.STOPPING -> Unit
                            else -> {
                                _serviceStatus.value = ServiceStatus(
                                    ServiceState.STARTING_SERVER,
                                    serverStatus.message,
                                    60,
                                    serverStatus.url
                                )
                                updateNotification(
                                    serverStatus.message.ifBlank { "Starting code-server..." },
                                    "Starting..."
                                )
                            }
                        }
                    }
                }

                // Phase 2: Start code-server
                _serviceStatus.value = ServiceStatus(
                    ServiceState.STARTING_SERVER, "Starting code-server...", 60
                )
                updateNotification("Starting code-server...", "Launching VS Code")

                val serverResult = try {
                    CodeServerLauncher.prepareAndStart()
                } finally {
                    serverCollector.cancel()
                }
                if (serverResult.isFailure) {
                    throw serverResult.exceptionOrNull()
                        ?: RuntimeException("code-server failed to start")
                }

                // Phase 3: Start health checks
                restartCount = 0
                _serviceStatus.value = ServiceStatus(
                    ServiceState.RUNNING,
                    "VS Code is running",
                    100,
                    CodeServerLauncher.status.value.url
                )
                updateNotification(
                    "VS Code is ready",
                    CodeServerLauncher.status.value.url
                )
                startHealthChecks()

                Log.i(TAG, "Service fully initialized and running")
            } catch (e: CancellationException) {
                Log.i(TAG, "Initialization cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Service initialization failed", e)
                _serviceStatus.value = ServiceStatus(
                    ServiceState.ERROR, "Failed: ${e.message}", 0
                )
                updateNotification("VS Code failed to start", e.message ?: "Unknown error")
                healthCheckJob?.cancel()
                healthCheckJob = null
                CodeServerLauncher.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startHealthChecks() {
        if (healthCheckJob?.isActive == true) {
            Log.d(TAG, "Health check already running")
            return
        }
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(HEALTH_INTERVAL_MS)

                val healthy = try {
                    CodeServerLauncher.healthCheck()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Health check error", e)
                    false
                }

                if (healthy) {
                    if (restartCount != 0) restartCount = 0
                    continue
                }

                restartCount++
                Log.w(TAG, "Health check failed; restart attempt $restartCount/$MAX_RESTARTS")
                if (restartCount > MAX_RESTARTS) {
                    Log.e(TAG, "Too many restarts, giving up")
                    _serviceStatus.value = ServiceStatus(
                        ServiceState.ERROR, "VS Code crashed too many times", 0
                    )
                    updateNotification("VS Code failed", "Too many restarts")
                    CodeServerLauncher.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }

                _serviceStatus.value = ServiceStatus(
                    ServiceState.STARTING_SERVER,
                    "Restarting VS Code...",
                    80,
                    CodeServerLauncher.status.value.url
                )
                updateNotification("Restarting VS Code...", "Recovering from crash")

                CodeServerLauncher.stop()
                delay(RESTART_DELAY_MS)

                var restarted = false
                try {
                    restarted = CodeServerLauncher.prepareAndStart().isSuccess
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Restart threw", e)
                }

                if (restarted) {
                    // Reset only after a successful restart.
                    restartCount = 0
                    _serviceStatus.value = ServiceStatus(
                        ServiceState.RUNNING,
                        "VS Code restarted",
                        100,
                        CodeServerLauncher.status.value.url
                    )
                    updateNotification("VS Code restarted", "Recovered from crash")
                } else {
                    Log.e(TAG, "Restart failed")
                    _serviceStatus.value = ServiceStatus(
                        ServiceState.ERROR, "Restart failed", 0
                    )
                }
            }
        }
    }

    /**
     * ACTION_STOP: cancel initialization, kill the server, remove the
     * notification and actually stop the service.
     */
    private fun stopEverything() {
        Log.i(TAG, "Stopping service")
        initJob?.cancel()
        initJob = null
        healthCheckJob?.cancel()
        healthCheckJob = null
        restartCount = 0

        // stop() is asynchronous; no waitFor() on the main thread.
        CodeServerLauncher.stop()
        _serviceStatus.value = ServiceStatus(ServiceState.STOPPED, "Stopped", 0)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
            .addAction(
                android.R.drawable.ic_menu_manage,
                "Storage",
                PendingIntent.getActivity(
                    this,
                    2,
                    Intent(this, MainActivity::class.java).apply {
                        action = ACTION_OPEN_HOME_SETTINGS
                    },
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
