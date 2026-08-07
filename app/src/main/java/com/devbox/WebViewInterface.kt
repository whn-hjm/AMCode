package com.devbox

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * JavaScript bridge exposed to code-server WebView.
 *
 * Allows code-server's web UI to invoke native Android functionality.
 * Registered as "Android" in the WebView's JavaScript context.
 */
class WebViewInterface(
    private val activity: MainActivity
) {
    companion object {
        const val TAG = "WebViewInterface"
        const val JS_NAME = "VSBoxed"
    }

    /**
     * Called by JS to log messages to Android logcat.
     */
    @JavascriptInterface
    fun log(level: String, message: String) {
        when (level.lowercase()) {
            "error" -> Log.e(TAG, message)
            "warn" -> Log.w(TAG, message)
            "info" -> Log.i(TAG, message)
            "debug" -> Log.d(TAG, message)
            else -> Log.i(TAG, message)
        }
    }

    /**
     * Get the app version string.
     */
    @JavascriptInterface
    fun getAppVersion(): String {
        return "1.0.0"
    }

    /**
     * Get the Termux environment path (PREFIX).
     */
    @JavascriptInterface
    fun getPrefix(): String {
        return TermuxManager.usrDir.absolutePath
    }

    /**
     * Get the home directory path.
     */
    @JavascriptInterface
    fun getHomeDirectory(): String {
        return TermuxManager.homeDir.absolutePath
    }

    /**
     * Open a URL in the external browser.
     */
    @JavascriptInterface
    fun openExternalUrl(url: String) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse(url)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URL: $url", e)
        }
    }

    /**
     * Show a native toast message.
     */
    @JavascriptInterface
    fun showToast(message: String) {
        activity.runOnUiThread {
            android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
