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
import java.nio.file.Files
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
            val dir = instance.getExternalFilesDir(null) ?: instance.filesDir
            return File(dir, "vsboxed_crash.log")
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
 * Default (no preference) is the app-private internal HOME
 * (`/data/user/0/com.devbox/files/home`). Shared storage was the old default
 * and is kept working for existing `home_mode=external` installations, but it
 * is never selected silently and its files are never moved automatically.
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
    private const val KEY_EXTERNAL_MIGRATION_PROMPTED = "external_migration_prompted"
    private const val KEY_EXTERNAL_TRUST_WARNED = "external_trust_warned"
    private const val KEY_INTERNAL_TRUST_HINT_SHOWN = "internal_trust_hint_shown"
    private const val KEY_EXTRA_KEYS_ENABLED = "extra_keys_enabled"

    const val MODE_INTERNAL = "internal"
    const val MODE_EXTERNAL = "external"

    /**
     * Termux-style shared-storage mount point. `/sdcard` resolves to the same
     * FUSE mount, but the canonical path is stable across Android versions.
     */
    private const val SHARED_STORAGE_TARGET = "/storage/emulated/0"

    /** Result of restoring HOME without an Activity (START_STICKY/service). */
    data class RestoreResult(
        val external: Boolean,
        val home: File,
        /**
         * True when the persisted choice is external but it is currently
         * unavailable; internal HOME is used for this launch only and the
         * persisted choice is left untouched so an Activity can repair it.
         */
        val externalFallback: Boolean
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMode(context: Context): String? =
        prefs(context).getString(KEY_HOME_MODE, null)

    fun setMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_HOME_MODE, mode).apply()
    }

    fun wasExternalMigrationPrompted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EXTERNAL_MIGRATION_PROMPTED, false)

    fun markExternalMigrationPrompted(context: Context) {
        prefs(context).edit().putBoolean(KEY_EXTERNAL_MIGRATION_PROMPTED, true).apply()
    }

    fun wasExternalTrustWarningShown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EXTERNAL_TRUST_WARNED, false)

    fun markExternalTrustWarningShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_EXTERNAL_TRUST_WARNED, true).apply()
    }

    fun wasInternalTrustHintShown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INTERNAL_TRUST_HINT_SHOWN, false)

    fun markInternalTrustHintShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_INTERNAL_TRUST_HINT_SHOWN, true).apply()
    }

    /**
     * Extra-keys toolbar visibility, remembered across launches: users with a
     * hardware keyboard collapse the row once and never see it again.
     */
    fun isExtraKeysEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EXTRA_KEYS_ENABLED, true)

    fun setExtraKeysEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXTRA_KEYS_ENABLED, enabled).apply()
    }

    /** Legacy shared-storage workspace: /sdcard/AMCode */
    fun externalHomeDir(): File = File(Environment.getExternalStorageDirectory(), "AMCode")

    /** Private, always-writable default: files/home */
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
     * Apply the internal HOME and make sure `~/storage/shared` exists. The
     * symlink is created even when /sdcard is not readable yet: Android will
     * deny access until the user grants it, and the link itself is harmless.
     */
    fun applyInternalHome(context: Context, persist: Boolean = true): Boolean {
        if (persist) setMode(context, MODE_INTERNAL)
        val dir = internalHomeDir(context)
        return try {
            TermuxManager.setHomeDir(dir)
            val ok = dir.isDirectory && dir.canWrite()
            if (!ok) {
                Log.e(TAG, "Internal HOME is not writable: ${dir.absolutePath}")
                return false
            }
            ensureStorageLayout(dir)
            Log.i(TAG, "Applied internal HOME: ${dir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply internal HOME", e)
            false
        }
    }

    /** Apply the legacy external HOME after access/writability were checked. */
    fun applyExternalHome(context: Context): Boolean {
        val dir = externalHomeDir()
        return try {
            // Validate before touching the global HOME: on failure the app
            // must keep running with the previous (working) directory.
            if (!dir.isDirectory || !dir.canWrite()) {
                Log.w(TAG, "External HOME is not writable: ${dir.absolutePath}")
                return false
            }
            TermuxManager.setHomeDir(dir)
            setMode(context, MODE_EXTERNAL)
            Log.i(TAG, "Applied external HOME: ${dir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply external HOME", e)
            false
        }
    }

    /**
     * Restore the persisted HOME into TermuxManager before code-server starts.
     *
     * Unlike the previous implementation this NEVER rewrites an explicit
     * `external` preference to `internal`. If the external grant was revoked,
     * internal HOME is used for this launch only and the Activity can prompt
     * again; user files under /sdcard/AMCode stay where they are.
     */
    fun restoreHomeDir(context: Context): RestoreResult {
        val mode = getMode(context)
        if (mode == MODE_EXTERNAL) {
            if (hasExternalAccess(context) && isExternalWritable()) {
                if (applyExternalHome(context)) {
                    return RestoreResult(
                        external = true,
                        home = TermuxManager.homeDir,
                        externalFallback = false
                    )
                }
                Log.w(TAG, "Could not apply external HOME")
            } else {
                Log.w(TAG, "Persisted external HOME is unavailable")
            }
            applyInternalHome(context, persist = false)
            Log.w(
                TAG,
                "Using internal HOME for this launch; persisted external HOME is unchanged"
            )
            return RestoreResult(
                external = false,
                home = TermuxManager.homeDir,
                externalFallback = true
            )
        }

        if (mode != MODE_INTERNAL) setMode(context, MODE_INTERNAL)
        applyInternalHome(context, persist = false)
        return RestoreResult(
            external = false,
            home = TermuxManager.homeDir,
            externalFallback = false
        )
    }

    /**
     * Create `$HOME/storage/shared -> /storage/emulated/0`. Existing real
     * directories are left untouched; only a symlink (including a dangling or
     * stale one) or an empty directory is replaced.
     */
    fun ensureStorageLayout(home: File): Boolean {
        return try {
            if (!home.isDirectory) return false
            val storage = File(home, "storage")
            if (!storage.exists() && !storage.mkdirs()) {
                Log.w(TAG, "Cannot create ${storage.absolutePath}")
                return false
            }
            if (!storage.isDirectory) {
                Log.w(TAG, "Not a directory: ${storage.absolutePath}")
                return false
            }
            ensureSharedStorageLink(File(storage, "shared"))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cannot prepare ~/storage layout", e)
            false
        }
    }

    private fun ensureSharedStorageLink(link: File) {
        val linkPath = link.toPath()
        val target = File(SHARED_STORAGE_TARGET).toPath()

        if (Files.isSymbolicLink(linkPath)) {
            val raw = try {
                Files.readSymbolicLink(linkPath)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read ${link.absolutePath}", e)
                null
            }
            val resolved = raw?.let {
                try {
                    if (it.isAbsolute) it.normalize()
                    else link.parentFile?.toPath()?.resolve(it)?.normalize()
                } catch (_: Exception) {
                    null
                }
            }
            if (resolved == target) {
                Log.i(TAG, "storage/shared already points to $SHARED_STORAGE_TARGET")
                return
            }
            // Only symlinks are removed here, never user data.
            try {
                Files.delete(linkPath)
                Log.i(TAG, "Removed stale storage/shared link -> ${raw ?: "<unreadable>"}")
            } catch (e: Exception) {
                Log.w(TAG, "Cannot replace storage/shared symlink", e)
                return
            }
        } else if (link.exists()) {
            // A real directory is only replaced when it is empty; a directory
            // with files is preserved and reported instead of being destroyed.
            if (!link.isDirectory) {
                Log.w(TAG, "Refusing to replace non-directory ${link.absolutePath}")
                return
            }
            val entries = link.list()
            if (entries == null || entries.isNotEmpty()) {
                Log.w(TAG, "Refusing to replace non-empty ${link.absolutePath}")
                return
            }
            if (!link.delete()) {
                Log.w(TAG, "Cannot remove empty directory ${link.absolutePath}")
                return
            }
        }

        try {
            Files.createSymbolicLink(linkPath, target)
            Log.i(TAG, "Created ${link.absolutePath} -> $SHARED_STORAGE_TARGET")
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "Filesystem does not support symlinks for ${link.absolutePath}", e)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot create storage/shared symlink", e)
        }
    }
}
