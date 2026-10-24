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
import android.view.KeyEvent
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    }

    private lateinit var webView: WebView
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

    // Android 11+ "All files access" settings screen.
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (HomePrefs.hasExternalAccess(this@MainActivity) &&
            HomePrefs.isExternalWritable()
        ) {
            completeStorageSetup(true)
        } else {
            completeStorageSetup(
                false,
                if (isChinese()) "无法访问共享存储，已改用内部存储"
                else "External storage is unavailable; using internal storage"
            )
        }
    }

    // Android 8-10 (API 26-29) runtime READ/WRITE_EXTERNAL_STORAGE request.
    private val legacyStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val writeGranted = result[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
        val readGranted = result[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        if (writeGranted && readGranted && HomePrefs.isExternalWritable()) {
            completeStorageSetup(true)
        } else {
            completeStorageSetup(
                false,
                if (isChinese()) "未获得存储权限，已改用内部存储"
                else "Storage permission was denied; using internal storage"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            // Hide system bars for immersive VS Code experience
            WindowCompat.setDecorFitsSystemWindows(window, false)

            // Initialize views FIRST — before anything that touches them
            webView = findViewById(R.id.webview)
            loadingOverlay = findViewById(R.id.loading_overlay)
            progressBar = findViewById(R.id.progress_bar)
            statusText = findViewById(R.id.status_text)
            progressText = findViewById(R.id.progress_text)
            terminalOutput = findViewById(R.id.terminal_output)
            terminalScroll = findViewById(R.id.terminal_scroll)

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
        } catch (e: Exception) {
            Log.e(TAG, "Crash in onCreate", e)
            Toast.makeText(this, "App crashed: ${e.message}. Check crash log.", Toast.LENGTH_LONG).show()
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

        // The user already chose internal storage: never ask again.
        if (savedMode == HomePrefs.MODE_INTERNAL) {
            completeStorageSetup(false)
            return
        }

        if (HomePrefs.hasExternalAccess(this)) {
            if (HomePrefs.isExternalWritable()) {
                completeStorageSetup(true)
            } else {
                completeStorageSetup(
                    false,
                    if (isChinese()) "共享存储不可写，已改用内部存储"
                    else "External storage is not writable; using internal storage"
                )
            }
            return
        }

        // No storage access yet: resolve it before the service may start.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            showAllFilesAccessDialog()
        } else {
            requestLegacyStoragePermissions()
        }
    }

    /**
     * Persist the effective HOME choice and continue. This is the ONLY place
     * that marks storage as resolved, so the service cannot be started twice.
     */
    private fun completeStorageSetup(useExternal: Boolean, fallbackReason: String? = null) {
        if (storageResolved) return
        storageResolved = true

        var external = useExternal && HomePrefs.isExternalWritable()
        if (external) {
            val dir = HomePrefs.externalHomeDir()
            HomePrefs.setMode(this, HomePrefs.MODE_EXTERNAL)
            // TermuxManager.setHomeDir() ignores mkdirs() failures, so verify
            // writability both before and after applying the choice.
            try {
                TermuxManager.setHomeDir(dir)
                if (!dir.isDirectory || !dir.canWrite()) {
                    external = false
                } else {
                    Log.i(TAG, "HOME set to external storage: ${dir.absolutePath}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cannot apply external HOME", e)
                external = false
            }
        }

        if (!external) {
            val dir = HomePrefs.internalHomeDir(this)
            HomePrefs.setMode(this, HomePrefs.MODE_INTERNAL)
            try {
                TermuxManager.setHomeDir(dir)
                Log.i(TAG, "HOME set to internal storage: ${dir.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Cannot apply internal HOME", e)
            }
            if (useExternal) {
                Toast.makeText(
                    this,
                    fallbackReason ?: if (isChinese()) "已改用内部存储" else "Using internal storage",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        proceedToNotificationPermission()
    }

    private fun showAllFilesAccessDialog() {
        val isZh = isChinese()
        AlertDialog.Builder(this)
            .setTitle(if (isZh) "需要存储权限" else "Storage Access Required")
            .setMessage(if (isZh)
                "AMCode 需要访问所有文件的权限，以便将 /sdcard/AMCode 作为工作目录。\n\n请选择一个选项："
                else "AMCode needs access to all files to use /sdcard/AMCode as your workspace.\n\nChoose an option:")
            .setCancelable(false)
            .setPositiveButton(if (isZh) "授权访问" else "Grant Access") { _, _ ->
                launchAllFilesAccessSettings()
            }
            .setNeutralButton(if (isZh) "使用内部存储" else "Use Internal Storage") { _, _ ->
                completeStorageSetup(false)
            }
            .setNegativeButton(if (isZh) "退出" else "Exit") { _, _ ->
                finish()
            }
            .show()
    }

    private fun launchAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            completeStorageSetup(
                false,
                if (isChinese()) "系统不支持该存储权限，已改用内部存储"
                else "All-files access is not supported; using internal storage"
            )
            return
        }
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
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "All-files settings not available", e)
            completeStorageSetup(
                false,
                if (isChinese()) "无法打开存储设置，已改用内部存储"
                else "Cannot open storage settings; using internal storage"
            )
        }
    }

    private fun requestLegacyStoragePermissions() {
        Log.i(TAG, "Requesting READ/WRITE_EXTERNAL_STORAGE (API 26-29)")
        try {
            legacyStoragePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Cannot request storage permissions", e)
            completeStorageSetup(
                false,
                if (isChinese()) "无法申请存储权限，已改用内部存储"
                else "Cannot request storage permission; using internal storage"
            )
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // Debugging bridge only in debug builds. Release must never expose
        // chrome://inspect to the local code-server page.
        // BuildConfig is not generated by this project's Gradle configuration
        // (AGP 8 defaults buildConfig off), so use the debuggable flag.
        WebView.setWebContentsDebuggingEnabled(isDebuggableBuild())

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

        // Accept cookies for code-server session
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

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
                if (isExternalSchemeAllowed(uri)) {
                    openExternal(uri)
                    return true
                }
                // Do not hand arbitrary schemes (intent:, file:, ...) to the
                // system. Keep them inside the WebView sandbox.
                Log.w(TAG, "Blocked external navigation: $uri")
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == null || url.startsWith("about:")) return
                if (!isLocalServerUrl(url)) return
                if (mainFrameError) {
                    // Chromium renders an error page and then calls
                    // onPageFinished; never dismiss the retry overlay for it.
                    Log.w(TAG, "Ignoring onPageFinished for failed main frame: $url")
                    return
                }

                val wasReady = webViewReady
                webViewReady = true
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
                Log.i(TAG, "WebView page loaded: $url")

                if (!wasReady) {
                    // Inject mobile touch fix: prevent 300ms tap delay
                    view?.evaluateJavascript("""
                        (function(){
                            if(window.__amc_touch) return;
                            window.__amc_touch=true;
                            var s=document.createElement('style');
                            s.textContent='body,input,textarea,select,button{ touch-action: manipulation; }';
                            document.head.appendChild(s);
                        })();
                    """.trimIndent(), null)
                }
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
            webView.loadUrl(lastFailedUrl?.takeIf { isLocalServerUrl(it) } ?: serverUrl)
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

        val target = lastFailedUrl?.takeIf { isLocalServerUrl(it) } ?: serverUrl
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
            webView.loadUrl(loadUrl)

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

    /**
     * Handle hardware keyboard shortcuts.
     * Passes Ctrl+C/V/S etc. to the WebView via JS injection.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Forward Ctrl/Alt modifier keys to the WebView
        // code-server handles shortcuts via DOM events in modern WebViews
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.isCtrlPressed || event.isAltPressed)) {
            return super.dispatchKeyEvent(event)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
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
            bindService(
                intent,
                serviceConnection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
            Log.i(TAG, "CodeServerService start+bind requested")
        } catch (e: Exception) {
            serviceStartRequested = false
            Log.e(TAG, "Failed to start service", e)
            Toast.makeText(this, "Cannot start VS Code: ${e.message}", Toast.LENGTH_LONG).show()
        }
        updateProgress(getString(R.string.initializing), 0)
    }

    private fun retryService() {
        Log.i(TAG, "Retrying service startup")
        webViewRetryJob?.cancel()
        webViewRetryJob = null
        webViewRetryCount = 0
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
            webView.loadUrl(serverUrl)
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
