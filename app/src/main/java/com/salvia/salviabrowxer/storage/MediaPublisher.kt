package com.salvia.salviabrowxer.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

object MediaPublisher {

    /** Persists the user-chosen SAF tree under which downloads are stored (null = app default). */
    @Volatile
    var downloadTreeUri: Uri? = null

    fun publishToDevice(
        context: Context,
        sourceFile: File,
        displayName: String,
        mimeType: String
    ): String? {
        if (!sourceFile.exists() || sourceFile.length() == 0L) return null
        val safeName = sanitizeName(displayName)
        val normalizedMime = mimeType.substringBefore(';').trim().lowercase()

        // Preferred: the folder the user picked in Settings (SAF tree).
        val tree = downloadTreeUri
        if (tree != null) {
            publishToSaf(context, tree, sourceFile, safeName, normalizedMime)?.let { return it }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val (collection, relativePath) = when {
                normalizedMime.startsWith("video/") ->
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                        "${Environment.DIRECTORY_MOVIES}/SalviaBrowxer"
                normalizedMime.startsWith("audio/") ->
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                        "${Environment.DIRECTORY_MUSIC}/SalviaBrowxer"
                else ->
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                        "${Environment.DIRECTORY_DOWNLOADS}/SalviaBrowxer"
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, normalizedMime.ifEmpty { "application/octet-stream" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val itemUri: Uri = resolver.insert(collection, values) ?: return null
            return try {
                resolver.openOutputStream(itemUri)?.use { out ->
                    sourceFile.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
                } ?: throw IllegalStateException("Cannot open MediaStore stream")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
                itemUri.toString()
            } catch (ignored: Exception) {
                resolver.delete(itemUri, null, null)
                null
            }
        } else {
            // Android 8/9: the public Download directory needs WRITE_EXTERNAL_STORAGE granted at
            // runtime, which this app never asks for. Try it (some ROMs auto-grant), and fall
            // back to the app's own external files dir — no permission needed, still visible to
            // gallery apps after the MediaScanner pass.
            @Suppress("DEPRECATION")
            val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = if (publicDir.canWrite()) {
                File(publicDir, "SalviaBrowxer").apply { mkdirs() }
            } else {
                val appDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: return null
                File(appDir, "SalviaBrowxer").apply { mkdirs() }
            }
            val destFile = uniqueFile(targetDir, safeName)
            return try {
                sourceFile.copyTo(destFile, overwrite = false)
                MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf(normalizedMime), null)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile).toString()
            } catch (ignored: Exception) {
                null
            }
        }
    }

    /** Copies [sourceFile] into the SAF [tree] folder; returns the created document URI. */
    private fun publishToSaf(
        context: Context,
        tree: Uri,
        sourceFile: File,
        displayName: String,
        mimeType: String
    ): String? {
        return try {
            val resolver = context.contentResolver
            val dir = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree)
            )
            val finalMime = mimeType.ifBlank { guessMimeFromName(displayName) }
            val created = DocumentsContract.createDocument(resolver, dir, finalMime, displayName)
                ?: return null
            resolver.openOutputStream(created)?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
            }
            created.toString()
        } catch (ignored: Exception) {
            null
        }
    }

    fun providerUriForInternalFile(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun guessMimeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$stem ($i)$ext")
            i++
        }
        return candidate
    }

    private fun sanitizeName(raw: String): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.take(110).ifEmpty { "media.mp4" }
    }
}
