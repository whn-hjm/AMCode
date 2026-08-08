package com.devbox

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import java.io.File
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Main Activity — the only UI the user sees.
 *
 * Shows a full-screen WebView connected to the local code-server instance.
 * During initialization, an overlay shows progress.
 * Once code-server is running, the WebView takes over.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "MainActivity"
        private const val CODE_SERVER_URL = "http://localhost:8080"
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
    private var webViewReady = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CodeServerService.LocalBinder
            codeServerService = binder.getService()
            serviceBound = true
            Log.i(TAG, "Bound to CodeServerService")

            // Observe service status
            lifecycleScope.launch {
                codeServerService?.serviceStatus?.collect { status ->
                    runOnUiThread {
                        updateProgress(status.message, status.progress)

                        if (status.state == CodeServerService.ServiceState.RUNNING) {
                            onServerReady(status.serverUrl)
                        } else if (status.state == CodeServerService.ServiceState.ERROR) {
                            showError(status.message)
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            codeServerService = null
            serviceBound = false
            Log.w(TAG, "Service disconnected")
        }
    }

    // Permission launcher for POST_NOTIFICATIONS (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(TAG, "Notification permission: $granted")
        // Proceed regardless — the service handles the failure gracefully
        startCodeServerService()
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

            // Check storage permission for external home directory
            checkStoragePermission()

            // Request notification permission on Android 13+ before starting service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                when {
                    ContextCompat.checkSelfPermission(
                        this, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED -> {
                        startCodeServerService()
                    }
                    shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                        // Show a brief toast then request
                        Toast.makeText(this, "Notification needed to keep VS Code running", Toast.LENGTH_SHORT).show()
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    else -> {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            } else {
                startCodeServerService()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Crash in onCreate", e)
            Toast.makeText(this, "App crashed: ${e.message}. Check crash log.", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true              // VS Code needs file:// for workers
            allowContentAccess = true           // Required for some VS Code features
            cacheMode = WebSettings.LOAD_DEFAULT
            allowUniversalAccessFromFileURLs = true  // Allow XHR from file://
            setSupportMultipleWindows(false)

            // Enable mixed content (needed for local HTTP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

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

            // Allow debugging
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
        }

        // Accept cookies for code-server session
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Clear any cached data that might interfere
        webView.clearCache(false)

        // Add JS bridge
        webView.addJavascriptInterface(WebViewInterface(this), WebViewInterface.JS_NAME)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                // Keep ALL local traffic in WebView
                return if (url.startsWith("http://localhost") ||
                           url.startsWith("http://127.0.0.1")) {
                    false
                } else {
                    // External link → open in browser
                    try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) {}
                    true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!webViewReady && url != null && !url.startsWith("about:")) {
                    webViewReady = true
                    loadingOverlay.postDelayed({ loadingOverlay.visibility = View.GONE }, 300)
                    Log.i(TAG, "WebView page loaded: $url")
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
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                Log.w(TAG, "WebView error $errorCode: $description for $failingUrl")
                if (!webViewReady) {
                    lifecycleScope.launch {
                        delay(2000)
                        view?.loadUrl(CODE_SERVER_URL)
                    }
                }
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

    // ─── Storage permission ──────────────────────────────────

    private var storageResolved = false

    private fun checkStoragePermission() {
        // Already granted → proceed immediately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                TermuxManager.setHomeDir(File(Environment.getExternalStorageDirectory(), "AMCode"))
                storageResolved = true
                proceedAfterPermission()
                return
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            TermuxManager.setHomeDir(File(Environment.getExternalStorageDirectory(), "AMCode"))
            storageResolved = true
            proceedAfterPermission()
            return
        }

        // Need permission — show dialog, block initialization
        val isZh = java.util.Locale.getDefault().language == "zh"
        AlertDialog.Builder(this)
            .setTitle(if (isZh) "需要存储权限" else "Storage Access Required")
            .setMessage(if (isZh)
                "AMCode 需要访问所有文件的权限，以便将 /sdcard/AMCode 作为工作目录。\n\n请选择一个选项："
                else "AMCode needs access to all files to use /sdcard/AMCode as your workspace.\n\nChoose an option:")
            .setCancelable(false)
            .setPositiveButton(if (isZh) "授权访问" else "Grant Access") { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                storagePermissionLauncher.launch(intent)
            }
            .setNeutralButton(if (isZh) "使用内部存储" else "Use Internal Storage") { _, _ ->
                // Keep default internal homeDir — don't call setHomeDir
                storageResolved = true
                proceedAfterPermission()
            }
            .setNegativeButton(if (isZh) "退出" else "Exit") { _, _ ->
                finish()
            }
            .show()
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()) {
            TermuxManager.setHomeDir(File(Environment.getExternalStorageDirectory(), "AMCode"))
        }
        // If not granted, keep default internal homeDir
        storageResolved = true
        proceedAfterPermission()
    }

    private fun proceedAfterPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ->
                    startCodeServerService()
                else ->
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startCodeServerService()
        }
    }

    // ─── Service management ──────────────────────────────────

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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
            Toast.makeText(this, "Cannot start VS Code: ${e.message}", Toast.LENGTH_LONG).show()
        }
        updateProgress("Starting VS Code environment...", 0)
    }

    private fun onServerReady(url: String) {
        val loadUrl = if (url.isNotBlank()) url else CODE_SERVER_URL
        Log.i(TAG, "code-server ready, loading $loadUrl")
        progressBar.progress = 100
        statusText.text = getString(R.string.vscode_ready)
        progressText.text = ""

        lifecycleScope.launch {
            delay(300)
            webView.loadUrl(loadUrl)
            // Timeout after 15s
            delay(15000)
            if (!webViewReady) {
                Log.w(TAG, "WebView timeout, retrying with $CODE_SERVER_URL")
                webView.loadUrl(CODE_SERVER_URL)
                delay(10000)
                if (!webViewReady) {
                    Log.w(TAG, "WebView still not loaded, showing anyway")
                    loadingOverlay.post { loadingOverlay.visibility = View.GONE }
                }
            }
        }
    }

    private fun showError(message: String) {
        Log.e(TAG, "Error: $message")
        loadingOverlay.visibility = View.VISIBLE
        statusText.text = getString(R.string.error_occurred)
        progressText.text = message
        progressBar.visibility = View.GONE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ─── UI helpers ───────────────────────────────────────────

    private fun updateProgress(message: String, progress: Int) {
        statusText.text = message
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
            webView.loadUrl(CODE_SERVER_URL)
        }
    }

    override fun onPause() {
        super.onPause()
        // Don't stop service — keep running in background
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        // Don't destroy WebView explicitly — let the system handle it
        super.onDestroy()
    }
}
