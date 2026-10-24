package com.devbox

import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import java.util.Locale

/**
 * JavaScript bridge for the code-server WebView.
 *
 * NOTE: MainActivity intentionally does NOT register this bridge anymore.
 * addJavascriptInterface() exposes the object to every frame (including
 * arbitrary workspace/webview content), and the current code-server UI does
 * not call it. If it is re-enabled, use WebViewCompat.addWebMessageListener
 * with an explicit allowed-origin list instead.
 *
 * The methods below are kept minimal and validate their inputs so that a
 * future opt-in cannot become an arbitrary-Intent / path-disclosure bridge.
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
        return try {
            val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
            info.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    /**
     * Get the Termux environment path (PREFIX).
     */
    @JavascriptInterface
    fun getPrefix(): String {
        return try {
            TermuxManager.usrDir.absolutePath
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Get the home directory path.
     */
    @JavascriptInterface
    fun getHomeDirectory(): String {
        return try {
            TermuxManager.homeDir.absolutePath
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Open a URL in the external browser.
     */
    @JavascriptInterface
    fun openExternalUrl(url: String) {
        try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase(Locale.US)
            // Never forward intent:/file:/javascript: or other arbitrary
            // schemes from web content to the system.
            val allowed = scheme == "http" || scheme == "https" ||
                scheme == "mailto" || scheme == "tel"
            if (!allowed) {
                Log.w(TAG, "Blocked openExternalUrl scheme: $scheme")
                return
            }
            if ((scheme == "http" || scheme == "https") && uri.host.isNullOrBlank()) {
                Log.w(TAG, "Blocked openExternalUrl without host: $url")
                return
            }
            if (activity.isFinishing || activity.isDestroyed) return

            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                data = uri
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
        if (activity.isFinishing || activity.isDestroyed) return
        val safeMessage = message.take(200)
        activity.runOnUiThread {
            android.widget.Toast.makeText(
                activity, safeMessage, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
}
