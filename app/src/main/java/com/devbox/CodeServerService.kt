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
 * two concurrent bootstrap/apt/server starts. Crash recovery is routed
 * through the same gate (initializeAndStart with recovery = true), so the
 * health-check loop detects crashes but never starts a server itself.
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

    /**
     * Makes the initJob single-flight check atomic with replacing the
     * pipeline references. The health-check loop performs its recovery
     * hand-over (initializeAndStart) from an IO thread while ACTION_RESTART /
     * ACTION_STOP / duplicate starts mutate the same fields on the main
     * thread; without the lock, a recovery pipeline launched at the same
     * instant could slip past ACTION_RESTART's cancel pass (silently dropping
     * the user's restart). Never held across a suspension.
     */
    private val pipelineLock = Any()

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
                // The pipeline being cancelled may itself be a health-recovery
                // pipeline (initializeAndStart with recovery = true); doing
                // the cancel pass under pipelineLock keeps it atomic with the
                // health loop's hand-over, so this restart can never be
                // dropped by a concurrently launched recovery pipeline.
                val (previous, previousHealth) = synchronized(pipelineLock) {
                    val p = initJob
                    p?.cancel()
                    initJob = null
                    val h = healthCheckJob
                    h?.cancel()
                    healthCheckJob = null
                    Pair(p, h)
                }
                restartCount = 0
                CodeServerLauncher.stop()
                initializeAndStart(waitFor = previous, waitForHealth = previousHealth)
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
        synchronized(pipelineLock) {
            initJob?.cancel()
            initJob = null
            healthCheckJob?.cancel()
            healthCheckJob = null
        }
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
     *
     * [recovery] is the health-check hand-over: the environment is already
     * installed (a server ran before the crash), so the pipeline skips the
     * HOME/Termux phases and runs only the bounded process-restart loop
     * (recoverFromCrash). Routing the crash restart through this same gate
     * closes the last concurrency gap: prepareAndStart() no longer has any
     * caller outside a pipeline, so a health restart can never interleave
     * with an ACTION_RESTART pipeline.
     *
     * Expression body on purpose: it keeps guard + health-loop teardown +
     * initJob assignment inside pipelineLock (see that field) without
     * re-indenting the pipeline body below.
     */
    private fun initializeAndStart(
        waitFor: Job? = null,
        waitForHealth: Job? = null,
        recovery: Boolean = false
    ): Unit = synchronized(pipelineLock) {
        if (initJob?.isActive == true) {
            Log.i(TAG, "initializeAndStart ignored: already active")
            return
        }
        // A previous health-check loop must not race this initialization.
        // In recovery mode the caller IS that loop: cancelling it here is
        // safe because it returns immediately after this call, and the new
        // pipeline joins it (waitForHealth) before touching anything.
        healthCheckJob?.cancel()
        healthCheckJob = null

        initJob = scope.launch {
            val self = coroutineContext[Job]
            try {
                listOfNotNull(waitFor, waitForHealth).forEach { job ->
                    try {
                        // The cancelled pipelines' blocking sections (apt/dpkg/
                        // HTTP) do not observe coroutine cancellation
                        // promptly; do not let this pipeline (and its UI) wait
                        // on them forever — e.g. when the user switches HOME
                        // while the first install is still downloading.
                        withTimeoutOrNull(15_000) { job.join() }
                        if (job.isActive) {
                            Log.w(TAG, "Previous pipeline still unwinding; starting anyway")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    }
                }

                if (recovery) {
                    // Health-check hand-over: only restart the process —
                    // but inside this same single-flight gate, which is what
                    // keeps the restart from interleaving with a concurrent
                    // ACTION_RESTART pipeline.
                    recoverFromCrash(self)
                    return@launch
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
                                updateProgressNotification(progress.message, "${progress.percent}%")
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
                                updateProgressNotification(
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
                // A pipeline that was superseded (ACTION_RESTART/stop) can end
                // in a normal failure when its blocking section unwinds: the
                // launcher process it was starting is already gone by design.
                // It must NOT tear down the service the replacement pipeline
                // is running in.
                if (initJob !== self || !isActive) {
                    Log.w(TAG, "Superseded pipeline failed (${e.message}); leaving the service to its owner")
                    return@launch
                }
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

    private val healthCheckLock = Any()

    /**
     * Probe-only health loop: detect the crash, then hand the whole restart
     * over to the single-flight pipeline (initializeAndStart with
     * recovery = true) and exit. The restart used to run right here, calling
     * CodeServerLauncher.prepareAndStart() outside the initJob gate — the
     * last window where two starts (health restart × ACTION_RESTART) could
     * overlap.
     */
    private fun startHealthChecks() {
        synchronized(healthCheckLock) {
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

                    Log.w(TAG, "Health check failed; handing restart to the pipeline")
                    // initializeAndStart cancels this very job during the
                    // hand-over (healthCheckJob is us); that is fine because
                    // nothing suspends between this call and the return, and
                    // the new pipeline joins this job before doing anything.
                    initializeAndStart(
                        waitForHealth = coroutineContext[Job],
                        recovery = true
                    )
                    // Accepted: the recovery pipeline now owns
                    // stop/start/retry and starts a fresh health loop once
                    // the server is back. Rejected (some other pipeline is
                    // already active — e.g. an ACTION_RESTART landed between
                    // the probe and here): that pipeline owns the world and
                    // starts its own loop. Either way this loop is done.
                    return@launch
                }
            }
        }
    }

    /**
     * Bounded crash-recovery loop — the recovery branch of the initJob
     * pipeline. Runs entirely inside the single-flight gate, so its
     * stop/start sequence can never interleave with another pipeline.
     * Cadence and notification texts match the old in-loop restart:
     * 2 s between stop and start, 10 s between attempts, at most
     * [MAX_RESTARTS] attempts before giving up.
     */
    private suspend fun CoroutineScope.recoverFromCrash(self: Job?) {
        while (true) {
            restartCount++
            Log.w(TAG, "Health check failed; restart attempt $restartCount/$MAX_RESTARTS")
            if (restartCount > MAX_RESTARTS) {
                // Same ownership rule as the pipeline failure handler: a
                // recovery loop superseded during a non-cancellable section
                // must not tear down the service its replacement runs in.
                if (initJob !== self || !isActive) {
                    Log.w(TAG, "Superseded recovery loop hit its limit; leaving the service to its owner")
                    return
                }
                Log.e(TAG, "Too many restarts, giving up")
                _serviceStatus.value = ServiceStatus(
                    ServiceState.ERROR, "VS Code crashed too many times", 0
                )
                updateNotification("VS Code failed", "Too many restarts")
                CodeServerLauncher.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
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

            // The attempt may have unwound from a non-cancellable section of
            // a start that was already superseded (such a start reports
            // failure, or a hollow "success" without a live server). Do not
            // publish anything unless this loop still owns the pipeline: the
            // replacement's UI state must win.
            if (initJob !== self || !isActive) {
                Log.w(TAG, "Recovery loop superseded during restart attempt; exiting")
                return
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
                startHealthChecks()
                Log.i(TAG, "code-server recovered; service running again")
                return
            }

            Log.e(TAG, "Restart failed")
            _serviceStatus.value = ServiceStatus(
                ServiceState.ERROR, "Restart failed", 0
            )
            updateNotification("VS Code failed", "Restart failed")
            // Pace retries like the old loop-top probe delay did.
            delay(HEALTH_INTERVAL_MS)
        }
    }

    /**
     * ACTION_STOP: cancel initialization, kill the server, remove the
     * notification and actually stop the service.
     */
    private fun stopEverything() {
        Log.i(TAG, "Stopping service")
        // pipelineLock: a recovery hand-over racing this stop must not be
        // able to launch a new pipeline interleaved with the teardown.
        synchronized(pipelineLock) {
            initJob?.cancel()
            initJob = null
            healthCheckJob?.cancel()
            healthCheckJob = null
        }
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

    @Volatile
    private var lastProgressNotificationAt = 0L

    /**
     * The system rate-limits (and mutes) spammy notification senders; progress
     * ticks during a 200MB download do not need more than ~2 updates/second.
     * State transitions keep using the unthrottled [updateNotification].
     */
    private fun updateProgressNotification(title: String, content: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastProgressNotificationAt < 500) return
        lastProgressNotificationAt = now
        updateNotification(title, content)
    }
}
