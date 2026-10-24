package com.devbox

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {

    companion object {
        const val TAG = "VSBoxed"
        const val CHANNEL_ID = "vsboxed_service"
        const val CHANNEL_NAME = "VS Code Server"
        const val NOTIFICATION_ID = 1001

        lateinit var instance: App
            private set

        /** Path to crash log on external storage, for easy access without ADB */
        fun getCrashLogPath(): File {
            return File(instance.getExternalFilesDir(null), "vsboxed_crash.log")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Install crash handler FIRST — before anything else
        installCrashHandler()

        createNotificationChannel()
        Log.i(TAG, "VSBoxed Application initialized")
    }

    // ─── Crash Handler ───────────────────────────────────────

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Write crash log to file
            try {
                val crashFile = getCrashLogPath()
                crashFile.parentFile?.mkdirs()
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                pw.println("=== VSBoxed Crash Report ===")
                pw.println("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                pw.println("Thread: ${thread.name}")
                pw.println("Android: ${android.os.Build.VERSION.SDK_INT} (${android.os.Build.VERSION.RELEASE})")
                pw.println("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                pw.println()
                pw.println("Stack trace:")
                throwable.printStackTrace(pw)
                pw.flush()
                crashFile.writeText(sw.toString())
                Log.e(TAG, "Crash logged to ${crashFile.absolutePath}", throwable)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write crash log", e)
            }

            // Pass to default handler (which will kill the app)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    // ─── Notification Channel ────────────────────────────────

    private fun createNotificationChannel() {
        try {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows VS Code server is running"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create notification channel", e)
        }
    }
}

/**
 * Persisted choice of HOME/workspace storage.
 *
 * The service may be restarted by START_STICKY without an Activity, so the
 * choice must be stored outside the Activity and restored before code-server
 * is spawned. External storage is only ever used after verifying that the
 * runtime permission / MANAGE_EXTERNAL_STORAGE grant is present and that the
 * directory is actually writable.
 */
object HomePrefs {
    private const val TAG = "HomePrefs"
    private const val PREFS_NAME = "amcode_prefs"
    private const val KEY_HOME_MODE = "home_mode"

    const val MODE_INTERNAL = "internal"
    const val MODE_EXTERNAL = "external"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMode(context: Context): String? =
        prefs(context).getString(KEY_HOME_MODE, null)

    fun setMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_HOME_MODE, mode).apply()
    }

    /** Standard shared-storage workspace: /sdcard/AMCode */
    fun externalHomeDir(): File = File(Environment.getExternalStorageDirectory(), "AMCode")

    /** Private, always-writable fallback: files/home */
    fun internalHomeDir(context: Context): File =
        File(context.applicationContext.filesDir, "home")

    fun hasExternalAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * mkdirs() alone is not enough on FUSE-backed shared storage: verify the
     * result and write a probe file before pointing HOME at /sdcard/AMCode.
     */
    fun isExternalWritable(): Boolean {
        return try {
            if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
                Log.w(TAG, "External storage is not mounted")
                return false
            }
            val dir = externalHomeDir()
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "Cannot create ${dir.absolutePath}")
                return false
            }
            if (!dir.isDirectory || !dir.canWrite()) {
                Log.w(TAG, "Not a writable directory: ${dir.absolutePath}")
                return false
            }
            val probe = File(dir, ".amcode-write-test")
            if (probe.exists()) probe.delete()
            val ok = probe.createNewFile()
            if (probe.exists()) probe.delete()
            if (!ok) Log.w(TAG, "Write probe failed in ${dir.absolutePath}")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "External HOME is not usable", e)
            false
        }
    }

    /**
     * Restore the persisted HOME into TermuxManager before code-server starts.
     * Returns true when the external HOME was applied.
     */
    fun restoreHomeDir(context: Context): Boolean {
        val mode = getMode(context)
        if (mode == MODE_EXTERNAL) {
            if (hasExternalAccess(context) && isExternalWritable()) {
                if (applyHomeDir(externalHomeDir())) {
                    Log.i(TAG, "Restored external HOME: ${externalHomeDir().absolutePath}")
                    return true
                }
                Log.w(TAG, "Could not apply external HOME")
            } else {
                Log.w(TAG, "Persisted external HOME is unavailable")
            }
            // Keep the persisted mode and the effective HOME consistent for
            // future START_STICKY restarts in this installation.
            setMode(context, MODE_INTERNAL)
        }
        applyHomeDir(internalHomeDir(context))
        Log.i(TAG, "Restored internal HOME: ${internalHomeDir(context).absolutePath}")
        return false
    }

    private fun applyHomeDir(dir: File): Boolean {
        return try {
            TermuxManager.setHomeDir(dir)
            dir.isDirectory && dir.canWrite()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set HOME to ${dir.absolutePath}", e)
            false
        }
    }
}
