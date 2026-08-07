package com.devbox

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
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
