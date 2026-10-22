package com.hazel.android.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Publishes a completed temporary bridge download into the user's public Downloads/Hazel folder. */
object RemoteBridgeDownloads {
    fun publish(context: Context, source: File, displayName: String): Uri {
        require(source.isFile && source.length() > 0L) { "Downloaded file is missing or empty" }
        val safeName = displayName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "remote-download.bin" }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // On pre-scoped-storage Android, use the public Downloads directory. The app
            // declares WRITE_EXTERNAL_STORAGE through API 29; callers may still need runtime
            // permission if the device has not granted it.
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val folder = File(downloads, "Hazel")
            if (!folder.exists() && !folder.mkdirs()) throw IOException("Cannot create Downloads/Hazel")
            val destination = File(folder, safeName)
            source.inputStream().use { input -> destination.outputStream().buffered().use(input::copyTo) }
            return Uri.fromFile(destination)
        }

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType(safeName))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Hazel")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create public Downloads entry")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().buffered().use { input -> input.copyTo(output) }
            } ?: throw IOException("Could not open public Downloads entry")
            val publish = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            if (resolver.update(uri, publish, null, null) <= 0) {
                throw IOException("Could not publish file in Downloads/Hazel")
            }
            return uri
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    private fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "torrent" -> "application/x-bittorrent"
        else -> "application/octet-stream"
    }
}
