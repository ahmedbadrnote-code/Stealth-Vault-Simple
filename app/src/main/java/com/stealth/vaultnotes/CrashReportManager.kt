package com.stealth.vaultnotes

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Local-only crash diagnostics.
 *
 * Uncaught Java/Kotlin exceptions are written synchronously to app-private storage.
 * On the next process start, reports are copied to the public Downloads collection
 * on Android 10+ so the user can find and share them without a computer or root.
 */
object CrashReportManager {
    private const val PREFS_NAME = "crash_report_diagnostics"
    private const val KEY_BREADCRUMBS = "breadcrumbs"
    private const val KEY_VIEWER_ACTIVE = "viewer_active"
    private const val KEY_VIEWER_STATE = "viewer_state"
    private const val KEY_EXPORTED_NAMES = "exported_report_names"
    private const val MAX_BREADCRUMBS = 45

    private val installed = AtomicBoolean(false)

    @Volatile
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return

        val applicationContext = context.applicationContext
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeUncaughtExceptionReport(applicationContext, thread, throwable)
            } catch (_: Throwable) {
                // Never let diagnostics prevent Android's normal crash handling.
            }

            try {
                previousHandler?.uncaughtException(thread, throwable)
            } catch (_: Throwable) {
                // Fall through to process termination if a custom handler returns/fails.
            }

            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(10)
        }
    }

    @Synchronized
    fun beginSession(context: Context) {
        val prefs = preferences(context)
        prefs.edit()
            .putBoolean(KEY_VIEWER_ACTIVE, false)
            .putString(KEY_VIEWER_STATE, "")
            .putString(KEY_BREADCRUMBS, "")
            .apply()
        recordEvent(context, "Application process started")
    }

    @Synchronized
    fun recordEvent(context: Context, event: String, error: Throwable? = null) {
        try {
            val prefs = preferences(context)
            val existing = prefs.getString(KEY_BREADCRUMBS, "").orEmpty()
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()

            val safeEvent = redact(context, event).take(1200)
            val line = "${timestamp()} | $safeEvent"
            val errorText = if (error == null) "" else {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                "\n${redact(context, writer.toString()).take(5000)}"
            }
            val updated = (existing + (line + errorText)).takeLast(MAX_BREADCRUMBS)
            prefs.edit().putString(KEY_BREADCRUMBS, updated.joinToString("\n")).apply()
        } catch (_: Throwable) {
            // Diagnostics must never interrupt the app.
        }
    }

    @Synchronized
    fun setViewerState(
        context: Context,
        page: Int,
        mediaType: String,
        itemId: String
    ) {
        val state = "active=true page=$page mediaType=$mediaType item=${itemId.take(8)}"
        preferences(context).edit()
            .putBoolean(KEY_VIEWER_ACTIVE, true)
            .putString(KEY_VIEWER_STATE, state)
            .apply()
        recordEvent(context, "Media viewer page selected: $state")
    }

    @Synchronized
    fun clearViewerState(context: Context) {
        preferences(context).edit()
            .putBoolean(KEY_VIEWER_ACTIVE, false)
            .putString(KEY_VIEWER_STATE, "")
            .apply()
    }

    fun markAppBackgrounded(context: Context) {
        clearViewerState(context)
        recordEvent(context, "App moved to background")
    }

    /**
     * A native codec crash or OS process kill may bypass the Java uncaught handler.
     * If the previous process disappeared while the media viewer was active, preserve
     * that as a possible unexpected exit, without claiming a confirmed Java crash.
     */
    @Synchronized
    fun recoverPreviousUnexpectedExit(context: Context) {
        val prefs = preferences(context)
        if (!prefs.getBoolean(KEY_VIEWER_ACTIVE, false)) return

        val viewerState = prefs.getString(KEY_VIEWER_STATE, "unknown viewer state").orEmpty()
        val breadcrumbs = prefs.getString(KEY_BREADCRUMBS, "").orEmpty()
        val report = buildString {
            appendLine("STEALTH VAULT - POSSIBLE UNEXPECTED PROCESS EXIT")
            appendLine("This is not a confirmed Java crash. Android may have terminated the process, or a native component may have failed.")
            appendLine("Time detected: ${timestamp()}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android API: ${Build.VERSION.SDK_INT}")
            appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Last viewer state: $viewerState")
            appendLine()
            appendLine("Recent diagnostic events:")
            appendLine(breadcrumbs.ifBlank { "No diagnostic events were recorded." })
        }
        writeReportFile(context, "possible-exit", report)
        clearViewerState(context)
    }

    fun publishReportsToDownloads(context: Context) {
        val reports = reportDirectory(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()

        if (reports.isEmpty()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishWithMediaStore(context, reports)
        } else {
            publishToAppDocuments(context, reports)
        }
    }

    fun getLatestReportText(context: Context): String {
        val latest = latestReportFile(context) ?: return ""
        return try {
            latest.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    fun getLatestReportFileName(context: Context): String {
        return latestReportFile(context)?.name ?: "stealth-vault-crash-report.txt"
    }

    private fun latestReportFile(context: Context): File? {
        return reportDirectory(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun writeUncaughtExceptionReport(
        context: Context,
        thread: Thread,
        throwable: Throwable
    ) {
        val stackWriter = StringWriter()
        throwable.printStackTrace(PrintWriter(stackWriter))

        val prefs = preferences(context)
        val viewerState = prefs.getString(KEY_VIEWER_STATE, "not in media viewer").orEmpty()
        val breadcrumbs = prefs.getString(KEY_BREADCRUMBS, "").orEmpty()

        val report = buildString {
            appendLine("STEALTH VAULT - UNCAUGHT EXCEPTION")
            appendLine("Time: ${timestamp()}")
            appendLine("Thread: ${thread.name} (id=${thread.id})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android API: ${Build.VERSION.SDK_INT}")
            appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Last viewer state: $viewerState")
            appendLine()
            appendLine("Exception:")
            appendLine(redact(context, stackWriter.toString()))
            appendLine()
            appendLine("Recent diagnostic events:")
            appendLine(breadcrumbs.ifBlank { "No diagnostic events were recorded." })
        }

        writeReportFile(context, "crash", report)
        clearViewerState(context)
    }

    @Synchronized
    private fun writeReportFile(context: Context, prefix: String, content: String) {
        val directory = reportDirectory(context)
        if (!directory.exists() && !directory.mkdirs()) return

        val file = File(directory, "$prefix-${fileTimestamp()}.txt")
        FileOutputStream(file).use { output ->
            output.write(redact(context, content).toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }

        directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(10)
            ?.forEach { it.delete() }
    }

    private fun publishWithMediaStore(context: Context, reports: List<File>) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/Stealth Vault Crash Reports/"
        val exported = preferences(context)
            .getStringSet(KEY_EXPORTED_NAMES, mutableSetOf<String>())
            ?.toMutableSet()
            ?: mutableSetOf()

        reports.forEach { report ->
            if (report.name in exported) return@forEach

            try {
                val existing = resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(report.name, relativePath),
                    null
                )?.use { it.moveToFirst() } ?: false

                if (existing) {
                    exported.add(report.name)
                    return@forEach
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, report.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val uri = resolver.insert(collection, values) ?: return@forEach
                try {
                    val output = resolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Could not open crash report destination")
                    output.use { destination ->
                        report.inputStream().use { source -> source.copyTo(destination) }
                    }
                    val completed = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(uri, completed, null, null)
                    exported.add(report.name)
                } catch (error: Exception) {
                    resolver.delete(uri, null, null)
                    throw error
                }
            } catch (_: Exception) {
                // Keep the private report; a later app start can retry the export.
            }
        }

        preferences(context).edit().putStringSet(KEY_EXPORTED_NAMES, exported).apply()
    }

    private fun publishToAppDocuments(context: Context, reports: List<File>) {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val destination = File(base, "Crash Reports")
        if (!destination.exists() && !destination.mkdirs()) return

        reports.forEach { report ->
            try {
                val target = File(destination, report.name)
                if (!target.exists()) report.copyTo(target, overwrite = false)
            } catch (_: Exception) {
                // Keep the private report if the external app-specific copy fails.
            }
        }
    }

    fun getStorageLocationDescription(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Downloads/Stealth Vault Crash Reports"
        } else {
            "App Documents/Crash Reports; use Export TXT to save a copy elsewhere"
        }
    }

    private fun reportDirectory(context: Context): File =
        File(context.filesDir, "crash_reports")

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun fileTimestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

    private fun redact(context: Context, value: String): String {
        return value
            .replace(context.cacheDir.absolutePath, "[app-cache]")
            .replace(context.filesDir.absolutePath, "[app-private]")
    }
}
