package com.anharness.app.diagnostics

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-diag-redaction] Sanitized diagnostics bundle (P5 engineering parity).
 *
 * Packages logs + device/app facts into a zip the user can share with a
 * maintainer. EVERY text file passes [DiagnosticRedactor] before it
 * enters the zip; credential-shaped file names are excluded outright.
 * Nothing is uploaded — the user shares the file explicitly.
 */
object DiagnosticsBundle {

    private const val MAX_FILE_BYTES = 2L * 1024 * 1024   // per-file cap
    private const val MAX_TOTAL_BYTES = 16L * 1024 * 1024 // bundle cap

    suspend fun build(context: Context): Uri = withContext(Dispatchers.IO) {
        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val zipFile = File(sharedDir, "diagnostics-${System.currentTimeMillis()}.zip")
        var total = 0L
        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            // 1) Device/app facts (no identifiers beyond model/OS/build).
            val facts = buildString {
                appendLine("app_version: ${runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrDefault("?")}")
                appendLine("android_sdk: ${android.os.Build.VERSION.SDK_INT}")
                appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("abi: ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
                appendLine("generated_at: ${System.currentTimeMillis()}")
            }
            putText(zos, "device.txt", DiagnosticRedactor.redact(facts))

            // 2) Logs — redacted, size-capped, forbidden names skipped.
            val logsDir = File(context.filesDir, "logs")
            val files = logsDir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
            for (f in files) {
                if (!f.isFile) continue
                if (DiagnosticRedactor.isForbiddenFile(f.name)) continue
                if (total >= MAX_TOTAL_BYTES) break
                val bytes = f.length().coerceAtMost(MAX_FILE_BYTES)
                val text = f.inputStream().use { input ->
                    val buf = ByteArray(bytes.toInt())
                    var read = 0
                    while (read < buf.size) {
                        val n = input.read(buf, read, buf.size - read)
                        if (n <= 0) break
                        read += n
                    }
                    String(buf, 0, read, Charsets.UTF_8)
                }
                val redacted = DiagnosticRedactor.redact(text)
                putText(zos, "logs/${f.name}", redacted)
                total += redacted.length
            }
        }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
    }

    private fun putText(zos: ZipOutputStream, name: String, text: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(text.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }
}