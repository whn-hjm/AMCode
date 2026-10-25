package com.devbox

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Main Activity — the only UI the user sees.
 *
 * Shows a full-screen WebView connected to the local code-server instance.
 * During initialization, an overlay shows progress.
 * Once code-server is running, the WebView takes over.
 *
 * Startup is a single path:
 *   resolve HOME (storage) -> resolve POST_NOTIFICATIONS -> start service once.
 * No code is allowed to start the service before storage has been resolved.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val DEFAULT_SERVER_PORT = 8080
        private const val DEFAULT_SERVER_URL = "http://127.0.0.1:$DEFAULT_SERVER_PORT"
        private const val MAX_WEBVIEW_RETRIES = 3
        private const val MAX_AUTH_RETRIES = 2
        private const val SESSION_COOKIE_NAME = "code-server-session"
    }

    private lateinit var webView: WebView
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var loadingOverlay: ConstraintLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var terminalOutput: TextView
    private lateinit var terminalScroll: android.widget.ScrollView

    private var codeServerService: CodeServerService? = null
    private var serviceBound = false
    private var statusJob: Job? = null

    private var webViewReady = false
    private var mainFrameError = false
    private var webViewRetryCount = 0
    private var webViewRetryJob: Job? = null
    private var lastFailedUrl: String? = null
    private var loadGeneration = 0

    private var serverUrl: String = DEFAULT_SERVER_URL
    private var serverPort: Int = DEFAULT_SERVER_PORT

    // Startup is intentionally resolved exactly once per Activity instance.
    private var startupStarted = false
    private var storageResolved = false
    private var serviceStartRequested = false

    /** Which flow launched a storage-permission Activity result. */
    private enum class StoragePurpose {
        NONE, STARTUP_EXTERNAL, ON_DEMAND_SHARED, SWITCH_EXTERNAL
    }

    private var pendingStoragePurpose = StoragePurpose.NONE
    private var authRetryCount = 0
    private var sharedStoragePromptShown = false
    private var sharedTrustWarned = false
    private var lastHandledSharedFolder: String? = null
    private var pendingHomeSettingsDialog = false

    // File chooser for <input type=file> (extension uploads, VS Code webviews).
    private var filePathCallback: android.webkit.ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        val uris = result.data?.let {
            android.webkit.WebChromeClient.FileChooserParams.parseResult(result.resultCode, it)
        }
        callback.onReceiveValue(uris)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? CodeServerService.LocalBinder
            if (binder == null) {
                Log.e(TAG, "Unexpected service binder: $service")
                return
            }
            val boundService = binder.getService()
            codeServerService = boundService
            serviceBound = true
            Log.i(TAG, "Bound to CodeServerService")

            statusJob?.cancel()
            statusJob = lifecycleScope.launch {
                boundService.serviceStatus.collect { status ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        updateProgress(status.message, status.progress)
                        when (status.state) {
                            CodeServerService.ServiceState.RUNNING -> onServerReady(status.serverUrl)
                            CodeServerService.ServiceState.ERROR -> {
                                showError(
                                    status.message.ifBlank { getString(R.string.error_occurred) },
                                    retry = { retryService() }
                                )
                            }
                            CodeServerService.ServiceState.STOPPED -> {
                                showError(
                                    if (isChinese()) "VS Code 已停止" else "VS Code has stopped",
                                    retry = { retryService() }
                                )
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            codeServerService = null
            serviceBound = false
            statusJob?.cancel()
            statusJob = null
            Log.w(TAG, "Service disconnected")
            if (!isFinishing && !isDestroyed) {
                showError(
                    if (isChinese()) "VS Code 服务连接已断开" else "Connection to the VS Code service was lost",
                    retry = { retryService() }
                )
            }
        }
    }

    // Permission launcher for POST_NOTIFICATIONS (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(TAG, "Notification permission: $granted")
        // Proceed regardless — the service still runs without a visible notification.
        startCodeServerServiceOnce()
    }

    // Android 11+ "All files access" settings screen. The result is consumed
    // either by the startup external-HOME flow or by an on-demand request for
    // a shared-storage folder (never requested for the internal default).
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val purpose = pendingStoragePurpose
        pendingStoragePurpose = StoragePurpose.NONE
        when (purpose) {
            StoragePurpose.ON_DEMAND_SHARED -> {
                onSharedStoragePermissionResult(HomePrefs.hasExternalAccess(this@MainActivity))
            }
            StoragePurpose.SWITCH_EXTERNAL -> {
                if (HomePrefs.hasExternalAccess(this@MainActivity)) {
                    performSwitchToExternalHome()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        if (isChinese()) "未获得共享存储权限，保持内部存储"
                        else "Shared storage permission not granted; keeping internal HOME",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            StoragePurpose.STARTUP_EXTERNAL -> {
                if (!HomePrefs.hasExternalAccess(this@MainActivity)) {
                    fallbackToInternalAfterExternalDenied()
                } else if (!HomePrefs.isExternalWritable()) {
                    showExternalUnavailableDialog()
                } else {
                    finishExternalHome()
                }
            }
            StoragePurpose.NONE -> {
                // A result delivered after process death/Activity recreation
                // has no owning flow; never change HOME based on it.
                Log.w(TAG, "Ignoring storage settings result with no pending purpose")
            }
        }
    }

    // Android 8-10 (API 26-29) runtime READ/WRITE_EXTERNAL_STORAGE request.
    private val legacyStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val writeGranted = result[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
        val readGranted = result[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        val granted = writeGranted && readGranted
        val purpose = pendingStoragePurpose
        pendingStoragePurpose = StoragePurpose.NONE
        when (purpose) {
            StoragePurpose.ON_DEMAND_SHARED -> onSharedStoragePermissionResult(granted)
            StoragePurpose.SWITCH_EXTERNAL -> {
                if (granted) {
                    performSwitchToExternalHome()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        if (isChinese()) "未获得共享存储权限，保持内部存储"
                        else "Shared storage permission not granted; keeping internal HOME",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            StoragePurpose.STARTUP_EXTERNAL -> {
                if (!granted) {
                    fallbackToInternalAfterExternalDenied()
                } else if (!HomePrefs.isExternalWritable()) {
                    showExternalUnavailableDialog()
                } else {
                    finishExternalHome()
                }
            }
            StoragePurpose.NONE -> {
                Log.w(TAG, "Ignoring legacy storage result with no pending purpose")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            // Decide WebView debugging BEFORE the first WebView instance is
            // inflated by setContentView(): on some Chromium versions the
            // devtools socket is bound at first-WebView init, and a later
            // setWebContentsDebuggingEnabled(false) no longer removes it.
            // BuildConfig is not generated by this project's Gradle
            // configuration (AGP 8 defaults buildConfig off), so use the
            // debuggable flag. Release builds must never expose
            // chrome://inspect to the local code-server page.
            WebView.setWebContentsDebuggingEnabled(isDebuggableBuild())

            setContentView(R.layout.activity_main)

            // Hide system bars for immersive VS Code experience
            WindowCompat.setDecorFitsSystemWindows(window, false)

            // Initialize views FIRST — before anything that touches them
            webView = findViewById(R.id.webview)
            rootLayout = findViewById(R.id.root)
            loadingOverlay = findViewById(R.id.loading_overlay)
            progressBar = findViewById(R.id.progress_bar)
            statusText = findViewById(R.id.status_text)
            progressText = findViewById(R.id.progress_text)
            terminalOutput = findViewById(R.id.terminal_output)
            terminalScroll = findViewById(R.id.terminal_scroll)

            // Never let the startup log/overlay become the IME-served view.
            loadingOverlay.descendantFocusability =
                android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            terminalScroll.isFocusable = false
            terminalScroll.isFocusableInTouchMode = false
            terminalOutput.isFocusable = false
            terminalOutput.isFocusableInTouchMode = false

            // Observe terminal output from TermuxManager
            lifecycleScope.launch {
                TermuxManager.terminalOutput.collect { text ->
                    terminalOutput.text = text
                    terminalScroll.post {
                        terminalScroll.fullScroll(android.view.View.FOCUS_DOWN)
                    }
                }
            }

            hideSystemBars()

            setupWebView()
            setupImeInsets()

            // Handle back button in WebView
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        moveTaskToBack(true)
                    }
                }
            })

            // Single startup path: storage -> notification -> service.
            beginStartup()
            handleLaunchIntent(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Crash in onCreate", e)
            Toast.makeText(this, "App crashed: ${e.message}. Check crash log.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    /**
     * The ongoing notification exposes a "Storage" action that re-opens the
     * app's HOME information / switch-to-internal dialog. A cold start must
     * wait until storage resolution and the foreground service request are
     * done so the dialog cannot be covered by a permission prompt.
     */
    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.action == CodeServerService.ACTION_OPEN_HOME_SETTINGS) {
            Log.i(TAG, "Workspace storage dialog requested from notification")
            intent.action = null
            pendingHomeSettingsDialog = true
            maybeShowPendingHomeSettingsDialog()
        }
    }

    // ─── Startup state machine ───────────────────────────────

    private fun beginStartup() {
        if (startupStarted) return
        startupStarted = true

        if (storageResolved) {
            proceedToNotificationPermission()
            return
        }

        val savedMode = HomePrefs.getMode(this)
        Log.i(TAG, "Startup: persisted home mode=$savedMode")

        when (savedMode) {
            HomePrefs.MODE_EXTERNAL -> {
                // Existing external installations keep working, but the user
                // gets one explicit chance to move to the internal HOME.
                // Nothing is moved or deleted in either case.
                if (!HomePrefs.wasExternalMigrationPrompted(this)) {
                    showExternalMigrationDialog()
                } else {
                    resolveExternalHome()
                }
            }
            HomePrefs.MODE_INTERNAL -> finishInternalHome(persist = true)
            else -> {
                // Fresh installation: internal HOME is the default. Never ask
                // for MANAGE_EXTERNAL_STORAGE just to start the app.
                Log.i(TAG, "No persisted HOME; defaulting to internal storage")
                finishInternalHome(persist = true)
            }
        }
    }

    /**
     * One-time prompt for installations that already use /sdcard/AMCode.
     * "Keep external" preserves the existing behavior; "Switch" only changes
     * HOME, leaving every file in /sdcard/AMCode untouched.
     */
    private fun showExternalMigrationDialog() {
        if (isFinishing || isDestroyed) return
        val isZh = isChinese()
        AlertDialog.Builder(this)
            .setTitle(if (isZh) "工作区存储方式" else "Workspace Storage")
            .setMessage(
                if (isZh)
                    "AMCode 现在默认使用内部存储作为 HOME（Git/编译可执行，速度更快）。\n\n" +
                        "你当前的工作区是 /sdcard/AMCode。切换不会移动或删除任何文件，它们仍可通过 ~/storage/shared 打开。\n\n" +
                        "共享存储中的文件夹可能被其他应用修改，VS Code 会以受限模式打开并要求你确认信任。\n\n" +
                        "是否切换到内部存储？"
                else
                    "AMCode now defaults to internal storage for HOME (Git/builds can run, and it is faster).\n\n" +
                        "Your current workspace is /sdcard/AMCode. Switching does NOT move or delete any files; they stay reachable through ~/storage/shared.\n\n" +
                        "Folders on shared storage can be modified by other apps. VS Code opens them in Restricted Mode and asks you to trust them.\n\n" +
                        "Switch to internal storage now?"
            )
            .setCancelable(false)
            .setPositiveButton(if (isZh) "切换到内部存储" else "Switch to internal") { _, _ ->
                HomePrefs.markExternalMigrationPrompted(this)
                finishInternalHome(persist = true)
            }
            .setNegativeButton(if (isZh) "保留外部存储" else "Keep external") { _, _ ->
                HomePrefs.markExternalMigrationPrompted(this)
                // The dialog above already explains shared-folder trust; avoid
                // stacking a second warning on the same launch.
                HomePrefs.markExternalTrustWarningShown(this)
                sharedTrustWarned = true
                resolveExternalHome()
            }
            .show()
    }

    private fun resolveExternalHome() {
        if (!HomePrefs.hasExternalAccess(this)) {
            // Only an explicit/kept external choice requests storage access at
            // startup; the internal default never does.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                showAllFilesAccessDialog()
            } else {
                requestLegacyStoragePermissions(StoragePurpose.STARTUP_EXTERNAL)
            }
            return
        }
        if (!HomePrefs.isExternalWritable()) {
            showExternalUnavailableDialog()
            return
        }
        finishExternalHome()
    }

    private fun finishInternalHome(
        persist: Boolean,
        fallbackReason: String? = null
    ) {
        if (isFinishing || isDestroyed) return
        if (storageResolved) {
            maybeShowPendingHomeSettingsDialog()
            return
        }
        storageResolved = true
        val ok = HomePrefs.applyInternalHome(this, persist)
        if (!ok) {
            Log.e(TAG, "Internal HOME could not be applied")
            Toast.makeText(
                this,
                if (isChinese()) "内部存储不可写" else "Internal storage is not writable",
                Toast.LENGTH_LONG
            ).show()
        } else if (persist) {
            Log.i(TAG, "HOME set to internal storage")
        }
        if (fallbackReason != null) {
            Toast.makeText(this, fallbackReason, Toast.LENGTH_LONG).show()
        }
        if (shouldRestartServerForHome(HomePrefs.internalHomeDir(this))) {
            // A service from an earlier Activity instance may still be serving
            // the previous HOME. Restart it instead of leaving the WebView
            // attached to the old server.
            workspaceRestartRequested()
            return
        }
        proceedToNotificationPermission()
    }

    /**
     * True when a live code-server was started with a different HOME than
     * [target] (for example an external workspace before the user switched to
     * internal). The service must be restarted before the WebView reloads.
     */
    private fun shouldRestartServerForHome(target: File): Boolean {
        if (CodeServerLauncher.status.value.state != CodeServerLauncher.ServerState.RUNNING) {
            return false
        }
        val active = CodeServerLauncher.activeHomePath() ?: return true
        return active != target.absolutePath
    }

    /** Continue startup after HOME was applied, restarting a stale service. */
    private fun continueAfterExternalHome() {
        if (shouldRestartServerForHome(HomePrefs.externalHomeDir())) {
            workspaceRestartRequested()
        } else {
            proceedToNotificationPermission()
        }
    }

    private fun finishExternalHome() {
        if (isFinishing || isDestroyed) return
        if (storageResolved) return
        if (!HomePrefs.applyExternalHome(this)) {
            showExternalUnavailableDialog()
            return
        }
        HomePrefs.setMode(this, HomePrefs.MODE_EXTERNAL)
        storageResolved = true
        Log.i(TAG, "HOME set to external storage: ${HomePrefs.externalHomeDir().absolutePath}")
        if (!HomePrefs.wasExternalTrustWarningShown(this)) {
            showExternalTrustDialog()
        } else {
            sharedTrustWarned = true
            continueAfterExternalHome()
        }
    }

    /**
     * App-level Workspace Trust warning. code-server is intentionally started
     * WITHOUT `--disable-workspace-trust`: VS Code's Restricted Mode prompt is
     * still authoritative; this dialog only makes the app-level choice
     * explicit before shared storage is opened.
     */
    private fun showExternalTrustDialog() {
        if (isFinishing || isDestroyed) return
        val isZh = isChinese()
        AlertDialog.Builder(this)
            .setTitle(if (isZh) "工作区信任提示" else "Workspace Trust Warning")
            .setMessage(
                if (isZh)
                    "当前 HOME 位于共享存储 /sdcard/AMCode，其他应用可能修改其中的文件。\n\n" +
                        "AMCode 不会绕过 VS Code 的 Workspace Trust；VS Code 会将此文件夹以受限模式打开，并可能询问你是否信任。请只信任你控制的文件夹。"
                else
                    "Your HOME is on shared storage (/sdcard/AMCode), where other apps can modify files.\n\n" +
                        "AMCode does not bypass VS Code Workspace Trust. VS Code opens this folder in Restricted Mode and may ask whether to trust it. Only trust folders you control."
            )
            .setCancelable(false)
            .setPositiveButton(if (isZh) "继续" else "Continue") { _, _ ->
                HomePrefs.markExternalTrustWarningShown(this)
                sharedTrustWarned = true
                continueAfterExternalHome()
            }
            .setNegativeButton(if (isZh) "改用内部存储" else "Use internal HOME") { _, _ ->
                HomePrefs.markExternalTrustWarningShown(this)
                sharedTrustWarned = true
                if (HomePrefs.applyInternalHome(this, persist = true)) {
                    Log.i(TAG, "Switched to internal HOME from the trust dialog")
                }
                if (shouldRestartServerForHome(HomePrefs.internalHomeDir(this))) {
                    workspaceRestartRequested()
                } else {
                    proceedToNotificationPermission()
                }
            }
            .show()
    }

    private fun showExternalUnavailableDialog() {
        if (storageResolved || isFinishing || isDestroyed) return
        val isZh = isChinese()
        AlertDialog.Builder(this)
            .setTitle(if (isZh) "外部工作区不可用" else "External Workspace Unavailable")
            .setMessage(
                if (isZh)
                    "/sdcard/AMCode 当前不可写。文件不会被移动或删除。\n\n可以本次先用内部存储启动，或永久切换到内部存储。"
                else
                    "/sdcard/AMCode is not writable right now. No files will be moved or deleted.\n\nStart with internal HOME for this launch, or switch to internal HOME permanently."
            )
            .setCancelable(false)
            .setPositiveButton(if (isZh) "本次使用内部存储" else "Use internal for now") { _, _ ->
                finishInternalHome(
                    persist = false,
                    fallbackReason = if (isZh) {
                        "本次使用内部存储；/sdcard/AMCode 文件保持不变"
                    } else {
                        "Using internal HOME for this launch; files in /sdcard/AMCode are unchanged"
                    }
                )
            }
            .setNeutralButton(if (isZh) "永久切换" else "Switch permanently") { _, _ ->
                finishInternalHome(persist = true)
            }
            .setNegativeButton(if (isZh) "退出" else "Exit") { _, _ ->
                finish()
            }
            .show()
    }

    private fun showAllFilesAccessDialog() {
        if (isFinishing || isDestroyed) return
        val isZh = isChinese()
        AlertDialog.Builder(this)
            .setTitle(if (isZh) "需要存储权限" else "Storage Access Required")
            .setMessage(
                if (isZh)
                    "你选择了共享存储 /sdcard/AMCode。Android 需要“所有文件访问”权限才能读写它。\n\n也可以改用内部存储（推荐），现有文件不会被移动。"
                else
                    "You chose shared storage /sdcard/AMCode. Android requires All files access to read and write it.\n\nYou can also use internal HOME (recommended); existing files are not moved."
            )
            .setCancelable(false)
            .setPositiveButton(if (isZh) "授权访问" else "Grant Access") { _, _ ->
                launchAllFilesAccessSettings(StoragePurpose.STARTUP_EXTERNAL)
            }
            .setNeutralButton(if (isZh) "使用内部存储" else "Use Internal Storage") { _, _ ->
                finishInternalHome(persist = true)
            }
            .setNegativeButton(if (isZh) "退出" else "Exit") { _, _ ->
                finish()
            }
            .show()
    }

    private fun launchAllFilesAccessSettings(purpose: StoragePurpose) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (purpose == StoragePurpose.ON_DEMAND_SHARED) {
                requestLegacyStoragePermissions(purpose)
            } else {
                fallbackToInternalAfterExternalDenied()
            }
            return
        }
        pendingStoragePurpose = purpose
        val appSettings = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            storagePermissionLauncher.launch(appSettings)
            return
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "All-files app settings not available", e)
        }
        try {
            storagePermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            )
            return
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "All-files settings not available", e)
        }
        pendingStoragePurpose = StoragePurpose.NONE
        if (purpose == StoragePurpose.ON_DEMAND_SHARED || purpose == StoragePurpose.SWITCH_EXTERNAL) {
            Toast.makeText(
                this,
                if (isChinese()) "无法打开存储设置" else "Cannot open storage settings",
                Toast.LENGTH_LONG
            ).show()
        } else {
            fallbackToInternalAfterExternalDenied()
        }
    }

    private fun requestLegacyStoragePermissions(purpose: StoragePurpose) {
        pendingStoragePurpose = purpose
        Log.i(TAG, "Requesting READ/WRITE_EXTERNAL_STORAGE (API 26-29), purpose=$purpose")
        try {
            legacyStoragePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Cannot request storage permissions", e)
            pendingStoragePurpose = StoragePurpose.NONE
            if (purpose == StoragePurpose.ON_DEMAND_SHARED || purpose == StoragePurpose.SWITCH_EXTERNAL) {
                Toast.makeText(
                    this,
                    if (isChinese()) "无法申请存储权限" else "Cannot request storage permission",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                fallbackToInternalAfterExternalDenied()
            }
        }
    }

    /**
     * The user kept/required external HOME but denied or could not obtain the
     * permission. Start with internal HOME for this launch only; the persisted
     * choice stays external so the files are never "moved" implicitly.
     */
    private fun fallbackToInternalAfterExternalDenied() {
        if (storageResolved) return
        finishInternalHome(
            persist = false,
            fallbackReason = if (isChinese()) {
                "未获得共享存储权限，本次使用内部存储；/sdcard/AMCode 文件保持不变"
            } else {
                "Shared storage permission not granted; using internal HOME for this launch. Files in /sdcard/AMCode are unchanged"
            }
        )
    }

    /** Result of an on-demand shared-storage grant (not a HOME change). */
    private fun onSharedStoragePermissionResult(granted: Boolean) {
        Log.i(TAG, "On-demand shared storage permission: $granted")
        if (!granted) {
            Toast.makeText(
                this,
                if (isChinese()) "未获得共享存储权限" else "Shared storage access was not granted",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        Toast.makeText(
            this,
            if (isChinese()) "已获得共享存储访问权限" else "Shared storage access granted",
            Toast.LENGTH_SHORT
        ).show()
        warnSharedFolderTrust()

        // A persisted external HOME that was running in internal fallback
        // should now switch back to the real external workspace.
        if (HomePrefs.getMode(this) == HomePrefs.MODE_EXTERNAL &&
            HomePrefs.isExternalWritable() &&
            shouldRestartServerForHome(HomePrefs.externalHomeDir())
        ) {
            if (HomePrefs.applyExternalHome(this)) {
                workspaceRestartRequested()
                return
            }
        }

        if (webViewReady && ::webView.isInitialized) {
            webView.postDelayed({
                if (!isFinishing && !isDestroyed) webView.reload()
            }, 300)
        }
    }

    private fun proceedToNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                Toast.makeText(
                    this,
                    if (isChinese()) "需要通知权限以保持 VS Code 在后台运行"
                    else "Notification permission is needed to keep VS Code running",
                    Toast.LENGTH_SHORT
                ).show()
            }
            try {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot request notification permission", e)
                startCodeServerServiceOnce()
            }
        } else {
            startCodeServerServiceOnce()
        }
    }

    // ─── WebView ──────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            // code-server is served over HTTP; file:// access is not needed
            // and widens the attack surface of arbitrary workspace content.
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)

            // The page itself is HTTP; mixed content is not required.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            // Performance settings
            loadsImagesAutomatically = true
            blockNetworkImage = false
            mediaPlaybackRequiresUserGesture = false  // Allow autoplay

            // Responsive layout
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100
        }

        // The WebView must be the view that owns Android focus (and therefore
        // the IME). The loading overlay's log TextView used to be focusable
        // and ended up as the IME-served view, so every showSoftInput() from
        // code-server was ignored with "view is not served".
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.isClickable = true
        webView.setOnTouchListener { _, event ->
            // As a last-resort native fallback: move Android focus to the
            // WebView as soon as the user touches it, before Chromium handles
            // the event and asks for the keyboard. Returning false keeps all
            // normal WebView touch/gesture behaviour intact.
            if (event.actionMasked == MotionEvent.ACTION_DOWN && !webView.hasFocus()) {
                webView.requestFocusFromTouch()
            }
            false
        }

        // Accept cookies for code-server session. Third-party cookies are not
        // needed: the page and all its iframes are served from 127.0.0.1, and
        // a workspace page must not read cookies of cross-origin frames.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        // Clear any cached data that might interfere
        webView.clearCache(false)

        // The native JS bridge is intentionally NOT registered:
        // 1) the code-server UI does not use it, and
        // 2) addJavascriptInterface() exposes it to every frame.
        // If it is ever needed, use WebViewCompat.addWebMessageListener with
        // an explicit allowed-origin list instead.

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                // Only the exact local code-server origin stays in the WebView.
                if (isLocalServerUri(uri)) {
                    return false
                }
                // about:blank & friends are same-page navigations used by
                // window.open() popups; blocking them silently breaks those
                // flows without any security gain.
                if (uri.scheme?.lowercase(Locale.US) == "about") {
                    return false
                }
                if (isExternalSchemeAllowed(uri)) {
                    openExternal(uri)
                    return true
                }
                // Do not hand arbitrary schemes (intent:, file:, ...) to the
                // system. Keep them inside the WebView sandbox.
                Log.w(TAG, "Blocked external navigation: $uri")
                return true
            }

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                super.onPageStarted(view, url, favicon)
                if (url != null) maybeHandleSharedFolder(url)
            }

            override fun doUpdateVisitedHistory(
                view: WebView?,
                url: String?,
                isReload: Boolean
            ) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (url != null) maybeHandleSharedFolder(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == null || url.startsWith("about:")) return
                if (!isLocalServerUrl(url)) return
                if (isLoginUrl(url)) {
                    // The session cookie was missing/stale. Re-authenticate
                    // rather than showing code-server's password prompt (the
                    // user never sees the random token).
                    handleLoginPage()
                    return
                }
                if (mainFrameError) {
                    // Chromium renders an error page and then calls
                    // onPageFinished; never dismiss the retry overlay for it.
                    Log.w(TAG, "Ignoring onPageFinished for failed main frame: $url")
                    return
                }

                val wasReady = webViewReady
                webViewReady = true
                authRetryCount = 0
                mainFrameError = false
                lastFailedUrl = null
                webViewRetryCount = 0
                webViewRetryJob?.cancel()
                webViewRetryJob = null
                clearErrorClicks()

                progressBar.progress = 100
                progressBar.visibility = View.VISIBLE
                progressText.text = ""
                loadingOverlay.visibility = View.GONE
                Log.i(TAG, "WebView page loaded: $url (wasReady=$wasReady)")

                // The overlay is gone: the WebView must be the Android-focused
                // view now, otherwise the IME stays bound to the hidden log
                // TextView and ignores the first input taps.
                webView.post { ensureWebViewFocus() }
                injectFocusShim()
                maybeShowInternalTrustHint()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                val isMainFrame = request?.isForMainFrame == true
                Log.w(
                    TAG,
                    "WebView error ${error?.errorCode} (${error?.description}) " +
                        "for ${request?.url} mainFrame=$isMainFrame"
                )
                if (!isMainFrame) {
                    // Subresource failures (icons, source maps, extensions) must
                    // never reload the whole application.
                    return
                }

                mainFrameError = true
                webViewReady = false
                lastFailedUrl = request?.url?.toString()
                val message = error?.description?.toString().orEmpty()
                    .ifBlank { getString(R.string.error_occurred) }
                showError(
                    message,
                    retry = { retryWebView() },
                    showToast = false
                )
                scheduleWebViewAutoRetry()
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                // Main-frame HTTP errors (e.g. code-server 5xx) do not go
                // through onReceivedError; log them so the cause is visible
                // in bug reports. Retry logic stays with onReceivedError.
                if (request?.isForMainFrame == true) {
                    Log.w(
                        TAG,
                        "WebView HTTP ${errorResponse?.statusCode} for ${request.url}"
                    )
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                Log.e(TAG, "WebView renderer gone (didCrash=${detail?.didCrash()})")
                if (isFinishing || isDestroyed) return true

                webViewRetryJob?.cancel()
                webViewRetryJob = null
                webViewReady = false
                mainFrameError = true

                val oldView = view
                val recovered = oldView != null &&
                    oldView === webView &&
                    recreateWebView(oldView)
                val retryAction: (() -> Unit)? = if (recovered) {
                    { retryWebView() }
                } else {
                    // Last-resort recovery: recreate the Activity/WebView shell.
                    { recreate() }
                }
                showError(
                    if (isChinese()) "网页渲染进程已崩溃" else "The web page renderer crashed",
                    retry = retryAction,
                    showToast = false
                )
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100 && loadingOverlay.visibility == View.VISIBLE) {
                    progressBar.progress = newProgress
                }
            }

            override fun onShowFileChooser(
                view: WebView?,
                callback: android.webkit.ValueCallback<Array<Uri>>?,
                params: android.webkit.WebChromeClient.FileChooserParams?
            ): Boolean {
                if (callback == null || params == null) return false
                // Cancel any pending picker first so the WebView never waits on
                // two callbacks.
                filePathCallback?.onReceiveValue(null)
                filePathCallback = null
                return try {
                    fileChooserLauncher.launch(Intent.createChooser(params.createIntent(), null))
                    filePathCallback = callback
                    true
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "No file chooser available", e)
                    false
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot launch file chooser", e)
                    false
                }
            }

            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                msg?.let {
                    Log.d(TAG, "WebView console [${it.messageLevel()}]: ${it.message()}")
                    // Log JS errors to terminal output so user can see
                    if (it.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                        TermuxManager.emitOutput("[WebView error] ${it.message()}\n")
                    }
                }
                return true
            }
        }
    }

    /**
     * Edge-to-edge is enabled (WindowCompat.setDecorFitsSystemWindows(false)),
     * so the legacy adjustResize semantics no longer resize the window on
     * API 30+. Apply the IME inset ourselves by shrinking the WebView's
     * bottom margin by the keyboard height. Chromium then reports a smaller
     * viewport to code-server, so the editor/terminal reflows above the
     * keyboard instead of being covered by it.
     */
    private fun setupImeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val params = webView.layoutParams as? ConstraintLayout.LayoutParams
            if (params != null && params.bottomMargin != imeBottom) {
                params.bottomMargin = imeBottom
                webView.layoutParams = params
                webView.requestLayout()
            }
            // On API < 30 WindowInsetsCompat.CONSUMED has no platform
            // WindowInsets object; returning it can break ViewGroup insets
            // dispatch. There the window's adjustResize path is still active,
            // so simply pass the insets through.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsCompat.CONSUMED
            } else {
                insets
            }
        }
        ViewCompat.requestApplyInsets(rootLayout)
    }

    /**
     * Move Android focus (and therefore the IME) from the hidden loading
     * overlay to the WebView once code-server is visible.
     */
    private fun ensureWebViewFocus() {
        if (isFinishing || isDestroyed) return
        if (!::webView.isInitialized || !::loadingOverlay.isInitialized) return
        if (loadingOverlay.visibility == View.VISIBLE) return
        try {
            loadingOverlay.clearFocus()
            terminalScroll.clearFocus()
            terminalOutput.clearFocus()
            webView.isFocusable = true
            webView.isFocusableInTouchMode = true
            if (!webView.hasFocus()) {
                webView.requestFocusFromTouch()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not move focus to WebView", e)
        }
    }

    /**
     * Conservative JS fallback for inputs whose DOM focus is lost by the
     * page itself. This is NOT a replacement for the native focus handling
     * above: it only re-focuses an input/textarea/contenteditable (or the
     * xterm helper textarea) after a tap, never buttons/links.
     */
    private fun injectFocusShim() {
        webView.evaluateJavascript(
            """
            (function () {
              if (window.__amcFocusShim) return 'already-installed';
              window.__amcFocusShim = true;

              if (!document.getElementById('amc-touch-style')) {
                var style = document.createElement('style');
                style.id = 'amc-touch-style';
                style.textContent = 'body,input,textarea,select,button,[contenteditable]{touch-action:manipulation}';
                (document.head || document.documentElement).appendChild(style);
              }

              function deepActiveElement() {
                var active = document.activeElement;
                while (active && active.shadowRoot && active.shadowRoot.activeElement) {
                  active = active.shadowRoot.activeElement;
                }
                return active;
              }

              function editableFor(el) {
                if (!el || el.nodeType !== 1) return null;
                var tag = el.tagName;
                if (tag === 'TEXTAREA') return el;
                if (tag === 'INPUT') {
                  var type = (el.getAttribute('type') || 'text').toLowerCase();
                  if (type === 'text' || type === 'search' || type === 'email' ||
                      type === 'url' || type === 'tel' || type === 'password' ||
                      type === 'number') {
                    return el;
                  }
                  return null;
                }
                if (el.isContentEditable) return el;
                return null;
              }

              function targetForEvent(ev) {
                var path = ev.composedPath ? ev.composedPath() : [ev.target];
                for (var k = 0; k < path.length; k++) {
                  var candidate = path[k];
                  if (candidate && candidate.nodeType === 1 &&
                      (candidate.tagName === 'BUTTON' || candidate.tagName === 'A')) {
                    return null;
                  }
                }
                for (var i = 0; i < path.length; i++) {
                  var el = path[i];
                  if (!el || el.nodeType !== 1) continue;
                  var editable = editableFor(el);
                  if (editable && !editable.disabled && !editable.readOnly) return editable;
                }
                for (var j = 0; j < path.length; j++) {
                  var node = path[j];
                  if (!node || node.nodeType !== 1) continue;
                  if (node.classList && node.classList.contains('xterm')) {
                    var textarea = node.querySelector('textarea.xterm-helper-textarea');
                    if (textarea && !textarea.disabled && !textarea.readOnly) return textarea;
                  }
                }
                return null;
              }

              function applyFocus(ev) {
                var target = targetForEvent(ev);
                if (!target) return;
                if (deepActiveElement() === target) return;
                setTimeout(function () {
                  if (deepActiveElement() === target) return;
                  if (target.disabled || target.readOnly) return;
                  try { target.focus(); } catch (e) {}
                }, 0);
              }

              var touchStart = null;
              document.addEventListener('touchstart', function (ev) {
                if (ev.touches && ev.touches.length === 1) {
                  touchStart = { x: ev.touches[0].clientX, y: ev.touches[0].clientY, t: Date.now() };
                } else {
                  touchStart = null;
                }
              }, { capture: true, passive: true });

              document.addEventListener('touchend', function (ev) {
                if (!touchStart) return;
                var start = touchStart;
                touchStart = null;
                if (!ev.changedTouches || ev.changedTouches.length !== 1) return;
                var dx = ev.changedTouches[0].clientX - start.x;
                var dy = ev.changedTouches[0].clientY - start.y;
                if (Date.now() - start.t > 500) return;
                if (dx * dx + dy * dy > 100) return;
                applyFocus(ev);
              }, { capture: true, passive: true });

              document.addEventListener('click', function (ev) {
                if (ev.detail === 0) return;
                applyFocus(ev);
              }, true);

              return 'installed';
            })();
            """.trimIndent(),
            null
        )
    }

    private fun isLocalServerUri(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        if (scheme != "http") return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        if (host != "127.0.0.1" && host != "localhost") return false
        val port = if (uri.port != -1) uri.port else 80
        return port == serverPort
    }

    private fun isLocalServerUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            isLocalServerUri(Uri.parse(url))
        } catch (_: Exception) {
            false
        }
    }

    private fun isLoginUrl(url: String?): Boolean {
        if (!isLocalServerUrl(url)) return false
        val path = try {
            Uri.parse(url).path
        } catch (_: Exception) {
            null
        } ?: return false
        return path == "/login" || path.startsWith("/login/")
    }

    private fun serverBaseUrl(): String? {
        val uri = try {
            Uri.parse(serverUrl)
        } catch (_: Exception) {
            return null
        }
        val host = uri.host ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US) ?: "http"
        val port = if (uri.port > 0) uri.port else DEFAULT_SERVER_PORT
        return "$scheme://$host:$port"
    }

    /**
     * code-server prefers the last-opened folder stored in coder.json over the
     * positional HOME argument. After a HOME switch that stale value could
     * reopen the old external workspace, so every fresh navigation explicitly
     * opens the effective HOME. The user can still Open Folder afterwards
     * (that adds its own ?folder= query).
     */
    private fun buildInitialPageUrl(baseUrl: String): String {
        val home = TermuxManager.homeDir
        if (!home.isDirectory) return baseUrl
        // Keep '/' readable; encode spaces and other query metacharacters.
        val folder = Uri.encode(home.absolutePath, "/")
        return "${baseUrl.trimEnd('/')}/?folder=$folder"
    }

    /**
     * Install the already-validated code-server session cookie into the
     * WebView's CookieManager and wait until it is visible. The token itself
     * never reaches JavaScript or logcat.
     */
    private suspend fun installSessionCookie(cookieValue: String): Boolean {
        val base = serverBaseUrl() ?: return false
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        try {
            // HttpOnly keeps workspace/web page JavaScript from reading the
            // session value; the WebView still sends it on HTTP/WS requests.
            manager.setCookie(base, "$SESSION_COOKIE_NAME=$cookieValue; Path=/; HttpOnly")
            manager.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot set WebView session cookie", e)
            return false
        }
        repeat(12) {
            val stored = try {
                manager.getCookie(base)
            } catch (_: Exception) {
                null
            }
            if (stored?.contains("$SESSION_COOKIE_NAME=") == true) {
                Log.i(
                    TAG,
                    "Session cookie installed in WebView " +
                        "(len=${cookieValue.length}, value redacted)"
                )
                return true
            }
            delay(50)
        }
        Log.w(TAG, "Session cookie was not visible to the WebView after 600ms")
        return false
    }

    /**
     * code-server should never show its login page in the WebView because the
     * launcher already authenticated. If it does (stale cookie after a port
     * change, server restart, ...), re-run the loopback login once or twice.
     */
    private fun handleLoginPage() {
        if (isFinishing || isDestroyed) return
        if (authRetryCount >= MAX_AUTH_RETRIES) {
            Log.e(TAG, "Authentication retries exhausted")
            showError(
                if (isChinese()) "无法完成本地 VS Code 认证"
                else "Could not authenticate the local VS Code session",
                retry = { workspaceRestartRequested() },
                showToast = false
            )
            return
        }
        authRetryCount++
        Log.w(TAG, "code-server login page detected; re-authenticating ($authRetryCount/$MAX_AUTH_RETRIES)")
        loadingOverlay.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        statusText.text = getString(R.string.initializing)
        progressText.text = if (isChinese()) "正在重新认证…" else "Re-authenticating…"
        clearErrorClicks()

        val generation = ++loadGeneration
        lifecycleScope.launch {
            val cookie = CodeServerLauncher.refreshSessionCookie()
            if (cookie.isNullOrBlank()) {
                showError(
                    if (isChinese()) "本地认证失败，请重试" else "Local authentication failed; please retry",
                    retry = { workspaceRestartRequested() },
                    showToast = false
                )
                return@launch
            }
            if (generation != loadGeneration || isFinishing || isDestroyed) return@launch
            if (!installSessionCookie(cookie)) {
                // getCookie() intentionally does not expose whether Chromium
                // would send the cookie on the next request, so a negative
                // confirmation is not fatal. If authentication really failed,
                // the login-page detection above retries or reports an error.
                Log.w(TAG, "Session cookie not confirmed before reload; continuing")
            }
            if (generation != loadGeneration || isFinishing || isDestroyed) return@launch
            webView.loadUrl(buildInitialPageUrl(serverUrl))
        }
    }

    /**
     * Shared-storage folders need MANAGE_EXTERNAL_STORAGE (API 30+) or the
     * legacy READ/WRITE grant (API 26-29) only when the user actually opens
     * one. The internal HOME default never triggers this at launch.
     */
    private fun maybeHandleSharedFolder(url: String) {
        if (!isLocalServerUrl(url)) return
        val path = sharedFolderPathFromUrl(url) ?: return
        if (path == lastHandledSharedFolder) return
        lastHandledSharedFolder = path
        Log.i(TAG, "Shared-storage folder opened: $path")

        if (!HomePrefs.hasExternalAccess(this)) {
            if (!sharedStoragePromptShown) {
                sharedStoragePromptShown = true
                showSharedStoragePrompt()
            }
            return
        }
        warnSharedFolderTrust()
    }

    private fun sharedFolderPathFromUrl(url: String): String? {
        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return null
        }
        val raw = uri.getQueryParameter("folder")
            ?: uri.getQueryParameter("workspace")
            ?: return null
        val path = raw.replace('\\', '/')
        return if (isSharedStoragePath(path)) path else null
    }

    private fun isSharedStoragePath(path: String): Boolean {
        return path == "/sdcard" ||
            path.startsWith("/sdcard/") ||
            path == "/storage/emulated" ||
            path.startsWith("/storage/emulated/") ||
            path.startsWith("/storage/self/") ||
            path.contains("/files/home/storage/shared") ||
            path.contains("/storage/shared")
    }

    private fun showSharedStoragePrompt() {
        if (isFinishing || isDestroyed) return
        val isZh = isChinese()
        AlertDialog.Builder(this)
            .setTitle(if (isZh) "共享存储访问" else "Shared Storage Access")
            .setMessage(
                if (isZh)
                    "该工作区位于共享存储（/sdcard）。Android 需要额外的存储授权才能读写它。\n\n授权后 VS Code 仍会以受限模式打开该文件夹，并可能询问你是否信任。"
                else
                    "This workspace is on shared storage (/sdcard). Android requires additional storage access to read and write it.\n\nAfter granting it, VS Code still opens the folder in Restricted Mode and may ask you to trust it."
            )
            .setPositiveButton(if (isZh) "去授权" else "Grant access") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    launchAllFilesAccessSettings(StoragePurpose.ON_DEMAND_SHARED)
                } else {
                    requestLegacyStoragePermissions(StoragePurpose.ON_DEMAND_SHARED)
                }
            }
            .setNegativeButton(if (isZh) "暂不" else "Not now", null)
            .show()
    }

    private fun warnSharedFolderTrust() {
        if (sharedTrustWarned || isFinishing || isDestroyed) return
        sharedTrustWarned = true
        val isZh = isChinese()
        AlertDialog.Builder(this)
            .setTitle(if (isZh) "工作区信任提示" else "Workspace Trust")
            .setMessage(
                if (isZh)
                    "该文件夹位于共享存储，其他应用可能修改其中的文件。\n\nAMCode 不会绕过 VS Code 的 Workspace Trust：VS Code 会以受限模式打开它，并可能询问你是否信任。请只信任你控制的文件夹。"
                else
                    "This folder is on shared storage, where other apps can modify files.\n\nAMCode does not bypass VS Code Workspace Trust: VS Code opens it in Restricted Mode and may ask whether to trust it. Only trust folders you control."
            )
            .setPositiveButton(if (isZh) "我知道了" else "I understand", null)
            .show()
    }

    /**
     * One-time hint for the default internal HOME. VS Code opens the folder in
     * Restricted Mode and shows its own banner; users who do not know the
     * banner miss it and wonder why their extensions are disabled. The hint
     * explains where to click (Trust the Author) without auto-trusting: AMCode
     * never disables workspace trust on the user's behalf.
     */
    private fun maybeShowInternalTrustHint() {
        if (isFinishing || isDestroyed) return
        if (HomePrefs.wasInternalTrustHintShown(this)) return
        val internal = HomePrefs.internalHomeDir(this).absolutePath
        if (TermuxManager.homeDir.absolutePath != internal) return

        val isZh = isChinese()
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isZh) "启用扩展：信任工作区" else "Enable extensions: trust the workspace")
            .setMessage(
                if (isZh)
                    "首次打开工作区时，VS Code 顶部会显示受限模式（Restricted Mode）横幅，此时用户扩展被停用。\n\n" +
                        "该工作区是 AMCode 的应用私有目录（其他应用无法访问）。如果这是你自己的设备，点击横幅上的 " +
                        "“Trust the Authors / 信任” 即可启用全部扩展；也可以保持受限模式。\n\n" +
                        "AMCode 不会代替你自动信任任何文件夹。"
                else
                    "The first time the workspace opens, VS Code shows a Restricted Mode banner at the top and disables user extensions.\n\n" +
                        "This workspace is AMCode's app-private directory (no other app can access it). On your own device, tap " +
                        "\"Trust the Authors\" in the banner to enable all extensions, or keep Restricted Mode.\n\n" +
                        "AMCode never trusts a folder automatically on your behalf."
            )
            .setPositiveButton(if (isZh) "我知道了" else "Got it", null)
            .create()
        // Mark only once the dialog is actually on screen: an exception or
        // a finishing Activity must not permanently consume the hint.
        dialog.setOnShowListener { HomePrefs.markInternalTrustHintShown(this) }
        dialog.show()
    }

    private fun maybeShowPendingHomeSettingsDialog() {
        if (!pendingHomeSettingsDialog) return
        if (!storageResolved || !serviceStartRequested || isFinishing || isDestroyed) return
        pendingHomeSettingsDialog = false
        webView.postDelayed({
            if (!isFinishing && !isDestroyed) showWorkspaceStorageDialog()
        }, 300)
    }

    /**
     * Persistent "switch to internal" path, reachable from the foreground
     * notification's Storage action. Existing external installs get the same
     * choice from the one-time startup migration dialog.
     */
    private fun showWorkspaceStorageDialog() {
        if (isFinishing || isDestroyed) return
        val isZh = isChinese()
        val modeExternal = HomePrefs.getMode(this) == HomePrefs.MODE_EXTERNAL
        val effective = TermuxManager.homeDir.absolutePath
        val usingExternal = modeExternal &&
            effective == HomePrefs.externalHomeDir().absolutePath
        val message =
            if (isZh) {
                "当前 HOME：$effective\n\n" + when {
                    usingExternal ->
                        "使用共享存储 /sdcard/AMCode。共享存储不可执行二进制，Git/编译可能受限。切换到内部存储不会移动或删除文件。"
                    modeExternal ->
                        "外部工作区 /sdcard/AMCode 当前不可用，本次使用内部 HOME。文件不会被移动或删除。"
                    else ->
                        "使用内部存储。共享文件可通过 ~/storage/shared 打开；首次访问 /sdcard 时需要授权。"
                }
            } else {
                "Current HOME: $effective\n\n" + when {
                    usingExternal ->
                        "Using shared storage /sdcard/AMCode. Shared storage is noexec, so Git/build tools may be limited. Switching to internal HOME does not move or delete files."
                    modeExternal ->
                        "The external workspace /sdcard/AMCode is unavailable; internal HOME is being used for this launch. Files are not moved or deleted."
                    else ->
                        "Using internal storage. Shared files are available through ~/storage/shared; Android asks for access the first time /sdcard is used."
                }
            }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (isZh) "工作区存储" else "Workspace Storage")
            .setMessage(message)
            .setNegativeButton(if (isZh) "关闭" else "Close", null)
            .setNeutralButton(if (isZh) "授权共享存储" else "Grant shared storage") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    launchAllFilesAccessSettings(StoragePurpose.ON_DEMAND_SHARED)
                } else {
                    requestLegacyStoragePermissions(StoragePurpose.ON_DEMAND_SHARED)
                }
            }
        if (modeExternal) {
            builder.setPositiveButton(if (isZh) "切换到内部存储" else "Switch to internal HOME") { _, _ ->
                confirmSwitchToInternalHome()
            }
        } else {
            builder.setPositiveButton(if (isZh) "切换到外部存储" else "Switch to external /sdcard/AMCode") { _, _ ->
                confirmSwitchToExternalHome()
            }
        }
        builder.show()
    }

    /**
     * Switch the workspace back to the legacy shared-storage HOME. The dialog
     * spells out the FUSE/noexec limitations before anything changes; files in
     * the internal HOME are never moved or deleted.
     */
    private fun confirmSwitchToExternalHome() {
        if (isFinishing || isDestroyed) return
        val isZh = isChinese()
        AlertDialog.Builder(this)
            .setTitle(if (isZh) "切换到外部存储？" else "Switch to external storage?")
            .setMessage(
                if (isZh)
                    "VS Code 会重启并使用 /sdcard/AMCode 作为 HOME，请先保存未保存的编辑内容。\n\n" +
                        "注意：共享存储不支持 Unix 权限/符号链接，且通常不可执行（noexec）——Git、编译产物、venv、npm 部分功能会受限。\n\n" +
                        "需要存储授权；内部 HOME 中的文件不会被移动或删除。"
                else
                    "VS Code will restart using /sdcard/AMCode as HOME. Save any unsaved editors first.\n\n" +
                        "Note: shared storage lacks Unix permissions/symlinks and is usually noexec — Git, build artifacts, venvs and parts of npm may be limited.\n\n" +
                        "Storage access is required; files in the internal HOME are not moved or deleted."
            )
            .setPositiveButton(if (isZh) "切换" else "Switch") { _, _ ->
                performSwitchToExternalHome()
            }
            .setNegativeButton(if (isZh) "取消" else "Cancel", null)
            .show()
    }

    private fun performSwitchToExternalHome() {
        if (isFinishing || isDestroyed) return
        if (!HomePrefs.hasExternalAccess(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                launchAllFilesAccessSettings(StoragePurpose.SWITCH_EXTERNAL)
            } else {
                requestLegacyStoragePermissions(StoragePurpose.SWITCH_EXTERNAL)
            }
            return
        }
        if (!HomePrefs.isExternalWritable()) {
            Toast.makeText(
                this,
                if (isChinese()) "/sdcard/AMCode 当前不可写，保持内部存储"
                else "/sdcard/AMCode is not writable; keeping internal HOME",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (HomePrefs.applyExternalHome(this)) {
            HomePrefs.setMode(this, HomePrefs.MODE_EXTERNAL)
            HomePrefs.markExternalTrustWarningShown(this)
            sharedTrustWarned = true
            workspaceRestartRequested()
        } else {
            Toast.makeText(
                this,
                if (isChinese()) "无法切换到外部存储" else "Could not switch to external storage",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun confirmSwitchToInternalHome() {
        if (isFinishing || isDestroyed) return
        val isZh = isChinese()
        AlertDialog.Builder(this)
            .setTitle(if (isZh) "切换到内部存储？" else "Switch to internal storage?")
            .setMessage(
                if (isZh)
                    "VS Code 会重启以使用内部 HOME，请先保存未保存的编辑内容。\n\n/sdcard/AMCode 中的文件不会被移动或删除，仍可通过 ~/storage/shared 打开。"
                else
                    "VS Code will restart with internal HOME. Save any unsaved editors first.\n\nFiles in /sdcard/AMCode are not moved or deleted and remain reachable through ~/storage/shared."
            )
            .setPositiveButton(if (isZh) "切换并重启" else "Switch and restart") { _, _ ->
                performSwitchToInternalHome()
            }
            .setNegativeButton(if (isZh) "取消" else "Cancel", null)
            .show()
    }

    private fun performSwitchToInternalHome() {
        if (!HomePrefs.applyInternalHome(this, persist = true)) {
            Toast.makeText(
                this,
                if (isChinese()) "无法切换到内部存储" else "Could not switch to internal storage",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        workspaceRestartRequested()
    }

    /** Ask the service to restart code-server (HOME change or auth recovery). */
    private fun workspaceRestartRequested() {
        Log.i(TAG, "Restarting code-server (HOME change or auth recovery)")
        webViewRetryJob?.cancel()
        webViewRetryJob = null
        webViewRetryCount = 0
        authRetryCount = 0
        webViewReady = false
        mainFrameError = false
        lastFailedUrl = null
        loadGeneration++
        loadingOverlay.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        statusText.text = getString(R.string.initializing)
        progressText.text = ""
        clearErrorClicks()
        serviceStartRequested = true
        try {
            val intent = Intent(this, CodeServerService::class.java)
                .setAction(CodeServerService.ACTION_RESTART)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            if (!serviceBound) {
                bindService(
                    Intent(this, CodeServerService::class.java),
                    serviceConnection,
                    Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot restart CodeServerService", e)
            showError(
                if (isChinese()) "无法重启 VS Code" else "Cannot restart VS Code",
                retry = { retryService() }
            )
        }
    }

    private fun isExternalSchemeAllowed(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        return when (scheme) {
            "http", "https" -> !uri.host.isNullOrBlank()
            "mailto", "tel" -> true
            else -> false
        }
    }

    private fun openExternal(uri: Uri) {
        if (!isExternalSchemeAllowed(uri)) {
            Log.w(TAG, "Refusing to open unsupported URI: $uri")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity for $uri", e)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open $uri", e)
        }
    }

    private fun recreateWebView(oldView: WebView): Boolean {
        val parent = oldView.parent as? android.view.ViewGroup
        if (parent == null) {
            Log.e(TAG, "Cannot recreate WebView: no parent")
            return false
        }
        return try {
            val params = oldView.layoutParams ?: ConstraintLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            val replacement = WebView(this)
            replacement.id = R.id.webview
            replacement.layoutParams = params
            parent.removeView(oldView)
            parent.addView(replacement, 0)
            webView = replacement
            oldView.destroy()
            setupWebView()
            Log.i(TAG, "WebView recreated after renderer crash")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to recreate WebView", e)
            false
        }
    }

    private fun scheduleWebViewAutoRetry() {
        if (webViewRetryCount >= MAX_WEBVIEW_RETRIES) return
        webViewRetryCount++
        webViewRetryJob?.cancel()
        webViewRetryJob = lifecycleScope.launch {
            delay(1500L * webViewRetryCount)
            if (isFinishing || isDestroyed || !mainFrameError) return@launch
            progressBar.visibility = View.VISIBLE
            progressBar.progress = 0
            statusText.text = getString(R.string.initializing)
            progressText.text = if (isChinese()) {
                "正在重试连接（$webViewRetryCount/$MAX_WEBVIEW_RETRIES）…"
            } else {
                "Retrying connection ($webViewRetryCount/$MAX_WEBVIEW_RETRIES)…"
            }
            clearErrorClicks()
            mainFrameError = false
            loadGeneration++
            webView.loadUrl(
                lastFailedUrl?.takeIf { isLocalServerUrl(it) }
                    ?: buildInitialPageUrl(serverUrl)
            )
        }
    }

    private fun retryWebView() {
        val state = codeServerService?.serviceStatus?.value?.state
        if (state != null && state != CodeServerService.ServiceState.RUNNING) {
            retryService()
            return
        }

        webViewRetryJob?.cancel()
        webViewRetryJob = null
        webViewRetryCount = 0
        mainFrameError = false
        webViewReady = false
        loadGeneration++
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        loadingOverlay.visibility = View.VISIBLE
        statusText.text = getString(R.string.initializing)
        progressText.text = ""
        clearErrorClicks()

        val target = lastFailedUrl?.takeIf { isLocalServerUrl(it) }
            ?: buildInitialPageUrl(serverUrl)
        Log.i(TAG, "Retrying WebView load: $target")
        webView.loadUrl(target)
    }

    private fun onServerReady(url: String) {
        val loadUrl = url.ifBlank {
            CodeServerLauncher.status.value.url.ifBlank { DEFAULT_SERVER_URL }
        }
        val parsed = Uri.parse(loadUrl)
        serverUrl = loadUrl
        serverPort = if (parsed.port > 0) parsed.port else DEFAULT_SERVER_PORT
        Log.i(TAG, "code-server ready, loading $loadUrl (port $serverPort)")

        webViewRetryJob?.cancel()
        webViewRetryJob = null
        webViewRetryCount = 0
        authRetryCount = 0
        mainFrameError = false
        webViewReady = false
        lastFailedUrl = null
        clearErrorClicks()

        loadingOverlay.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 100
        statusText.text = getString(R.string.vscode_ready)
        progressText.text = ""

        val generation = ++loadGeneration
        lifecycleScope.launch {
            delay(300)
            if (generation != loadGeneration || isFinishing || isDestroyed) return@launch

            // The launcher has already performed the loopback password login.
            // Install its validated cookie before the first navigation so the
            // WebView never shows code-server's login page.
            val cookie = CodeServerLauncher.currentSessionCookie()
                ?: CodeServerLauncher.refreshSessionCookie()
            if (cookie.isNullOrBlank()) {
                showError(
                    if (isChinese()) "无法取得本地认证会话"
                    else "Could not obtain the local authentication session",
                    retry = { workspaceRestartRequested() },
                    showToast = false
                )
                return@launch
            }
            if (generation != loadGeneration || isFinishing || isDestroyed) return@launch
            if (!installSessionCookie(cookie)) {
                Log.w(TAG, "Loading code-server without a confirmed WebView cookie")
            }
            if (generation != loadGeneration || isFinishing || isDestroyed) return@launch

            webView.loadUrl(buildInitialPageUrl(loadUrl))

            delay(15000)
            if (generation != loadGeneration || isFinishing || isDestroyed) return@launch
            if (!webViewReady && !mainFrameError) {
                showError(
                    if (isChinese()) "VS Code 加载超时" else "VS Code did not load in time",
                    retry = { retryWebView() },
                    showToast = false
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            if (webViewReady && ::loadingOverlay.isInitialized &&
                loadingOverlay.visibility != View.VISIBLE
            ) {
                // Returning from the IME/another window can leave Android
                // focus on the WebView's window but not on the WebView view.
                webView.post { ensureWebViewFocus() }
            }
        }
    }

    // ─── Service management ──────────────────────────────────

    private fun startCodeServerServiceOnce() {
        if (serviceStartRequested) {
            Log.d(TAG, "Service start already requested; ignoring duplicate")
            return
        }
        serviceStartRequested = true
        startCodeServerService()
    }

    private fun startCodeServerService() {
        try {
            val intent = Intent(this, CodeServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            // bindService must pair with onDestroy's unbindService: binding
            // again on every retry (retryService resets serviceStartRequested)
            // would stack connections that are only unbound once.
            if (!serviceBound) {
                bindService(
                    intent,
                    serviceConnection,
                    Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
                )
            }
            Log.i(TAG, "CodeServerService start+bind requested")
        } catch (e: Exception) {
            serviceStartRequested = false
            Log.e(TAG, "Failed to start service", e)
            Toast.makeText(this, "Cannot start VS Code: ${e.message}", Toast.LENGTH_LONG).show()
        }
        updateProgress(getString(R.string.initializing), 0)
        // A "Storage" notification action may have cold-started the Activity.
        // Show its dialog only after the startup chain has requested the
        // service (so a permission prompt is not covered).
        webView.postDelayed({ maybeShowPendingHomeSettingsDialog() }, 800)
    }

    private fun retryService() {
        Log.i(TAG, "Retrying service startup")
        webViewRetryJob?.cancel()
        webViewRetryJob = null
        webViewRetryCount = 0
        authRetryCount = 0
        webViewReady = false
        mainFrameError = false
        lastFailedUrl = null
        serviceStartRequested = false
        loadGeneration++

        loadingOverlay.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        statusText.text = getString(R.string.initializing)
        progressText.text = ""
        clearErrorClicks()
        startCodeServerServiceOnce()
    }

    private fun showError(
        message: String,
        retry: (() -> Unit)? = null,
        showToast: Boolean = true
    ) {
        if (isFinishing || isDestroyed) return
        val text = message.ifBlank { getString(R.string.error_occurred) }
        Log.e(TAG, "Error: $text")

        loadingOverlay.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        statusText.text = getString(R.string.error_occurred)
        val hint = if (isChinese()) "点击此处重试" else "Tap here to retry"
        progressText.text = if (retry != null) "$text\n\n$hint" else text

        val listener = retry?.let { action -> View.OnClickListener { action() } }
        statusText.setOnClickListener(listener)
        progressText.setOnClickListener(listener)
        statusText.isClickable = listener != null
        progressText.isClickable = listener != null

        if (showToast) {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun clearErrorClicks() {
        statusText.setOnClickListener(null)
        progressText.setOnClickListener(null)
        statusText.isClickable = false
        progressText.isClickable = false
    }

    private fun isChinese(): Boolean = Locale.getDefault().language == "zh"

    private fun isDebuggableBuild(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    // ─── UI helpers ───────────────────────────────────────────

    private fun updateProgress(message: String, progress: Int) {
        // Progress text may still carry a tap-to-retry listener from an error
        // state; never let a random tap on the progress line restart things.
        clearErrorClicks()
        statusText.text = message
        progressBar.visibility = View.VISIBLE
        progressBar.progress = progress
        progressText.text = "$progress%"
    }

    private fun hideSystemBars() {
        if (!::webView.isInitialized) return

        WindowInsetsControllerCompat(window, webView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ─── Lifecycle ────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // If WebView was already loaded but server died, reload
        if (webViewReady && webView.url == null) {
            webView.loadUrl(buildInitialPageUrl(serverUrl))
        }
        if (webViewReady && ::loadingOverlay.isInitialized &&
            loadingOverlay.visibility != View.VISIBLE &&
            !webView.url.isNullOrBlank()
        ) {
            webView.post { ensureWebViewFocus() }
        }
    }

    override fun onPause() {
        super.onPause()
        // Don't stop service — keep running in background
    }

    override fun onDestroy() {
        statusJob?.cancel()
        statusJob = null
        webViewRetryJob?.cancel()
        webViewRetryJob = null
        if (serviceBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Service already unbound", e)
            }
            serviceBound = false
        }
        // Don't destroy WebView explicitly — let the system handle it
        super.onDestroy()
    }
}
