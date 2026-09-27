package com.deadpacketsociety.fieldcontroller

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * Writes app + BLE + node status events to a plain-text file on disk so
 * dropouts/disconnects can be diagnosed after the fact instead of relying
 * on live observation of the on-screen Monitor log (which is memory-only
 * and capped at 200 lines).
 *
 * - One file per app session, named dps-debug-yyyyMMdd-HHmmss.log
 * - Stored under app-specific external storage (no runtime permission
 *   needed on API 19+, still visible via a file manager / adb, and wiped
 *   automatically on uninstall)
 * - All disk I/O happens on a single dedicated background thread so
 *   logging never blocks the UI thread
 * - A session file is rotated once it passes MAX_FILE_BYTES, and old
 *   files are pruned once the log directory passes MAX_TOTAL_BYTES
 */
class FileLogger(context: Context) {

    companion object {
        private const val MAX_FILE_BYTES = 5L * 1024 * 1024   // 5 MB per file
        private const val MAX_TOTAL_BYTES = 40L * 1024 * 1024 // 40 MB total kept on disk
        private const val MAX_FILES = 30
    }

    private val fileNameFormat =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    private val lineTimestampFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val logDir: File = resolveLogDir(context)

    @Volatile
    private var currentFile: File = newSessionFile()

    @Volatile
    private var writer: FileWriter? = openWriter(currentFile)

    private data class LogJob(val line: String?, val flush: Boolean = false, val close: Boolean = false)

    private val queue = LinkedBlockingQueue<LogJob>()

    private val worker = Thread({
        runWorkerLoop()
    }, "FileLoggerThread").apply {
        isDaemon = true
        start()
    }

    init {
        pruneOldFiles()
        writeHeader(context)
    }

    /** Queue a line to be appended to the current log file. Never blocks the caller. */
    fun write(message: String) {
        val timestamp = lineTimestampFormat.format(Date())
        queue.offer(LogJob(line = "[$timestamp] $message"))
    }

    /** Force any queued lines to disk now. Blocks briefly (used before export/share). */
    fun flush() {
        val latch = java.util.concurrent.CountDownLatch(1)
        queue.offer(LogJob(line = null, flush = true))
        // best-effort: give the worker a short window to catch up before returning
        try {
            Thread.sleep(50)
        } catch (_: InterruptedException) {
        }
    }

    fun close() {
        queue.offer(LogJob(line = null, close = true))
    }

    fun currentFile(): File = currentFile

    fun currentFileName(): String = currentFile.name

    fun logDirectory(): File = logDir

    private fun runWorkerLoop() {
        while (true) {
            val job = try {
                queue.take()
            } catch (_: InterruptedException) {
                return
            }

            if (job.close) {
                try {
                    writer?.flush()
                    writer?.close()
                } catch (_: Exception) {
                }
                return
            }

            if (job.flush) {
                try {
                    writer?.flush()
                } catch (_: Exception) {
                }
                continue
            }

            val line = job.line ?: continue

            try {
                rotateIfNeeded()

                writer?.appendLine(line)
                writer?.flush()
            } catch (_: Exception) {
                // Disk write failed (storage full, unmounted, etc). Drop the line
                // rather than crash the app — debug logging must never take down
                // the capture session itself.
            }
        }
    }

    private fun rotateIfNeeded() {
        if (currentFile.length() < MAX_FILE_BYTES) {
            return
        }

        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {
        }

        currentFile = newSessionFile()
        writer = openWriter(currentFile)

        pruneOldFiles()
    }

    private fun writeHeader(context: Context) {
        val info = buildString {
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
            append("Log file: ${currentFile.absolutePath}\n")

            try {
                val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                append("App version: ${pkgInfo.versionName}\n")
            } catch (_: Exception) {
            }
        }

        write("=== SESSION HEADER ===\n$info=== END HEADER ===")
    }

    private fun newSessionFile(): File {
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        val name = "dps-debug-${fileNameFormat.format(Date())}.log"

        return File(logDir, name)
    }

    private fun openWriter(file: File): FileWriter? {
        return try {
            FileWriter(file, true)
        } catch (_: Exception) {
            null
        }
    }

    private fun pruneOldFiles() {
        val files = logDir.listFiles { f -> f.isFile && f.name.startsWith("dps-debug-") }
            ?.sortedBy { it.lastModified() }
            ?: return

        var totalBytes = files.sumOf { it.length() }
        var index = 0

        // Prune by count first
        while (files.size - index > MAX_FILES) {
            val victim = files[index]
            totalBytes -= victim.length()
            victim.delete()
            index++
        }

        // Then prune oldest-first by total size, but never delete the file
        // we're currently writing to
        while (totalBytes > MAX_TOTAL_BYTES && index < files.size) {
            val victim = files[index]

            if (victim.absolutePath == currentFile.absolutePath) {
                index++
                continue
            }

            totalBytes -= victim.length()
            victim.delete()
            index++
        }
    }

    private fun resolveLogDir(context: Context): File {
        val externalBase = context.getExternalFilesDir(null)

        val base = if (externalBase != null) {
            File(externalBase, "logs")
        } else {
            File(context.filesDir, "logs")
        }

        if (!base.exists()) {
            base.mkdirs()
        }

        return base
    }
}
