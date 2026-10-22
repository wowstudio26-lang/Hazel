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
        require(source.isFile) { "Downloaded file is missing" }
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
            var destination = File(folder, uniqueFileName(folder, safeName))
            val temporary = File(folder, ".${destination.name}.${System.nanoTime()}.part")
            try {
                source.inputStream().buffered().use { input ->
                    temporary.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                if (destination.exists()) destination = File(folder, uniqueFileName(folder, safeName))
                if (!temporary.renameTo(destination)) throw IOException("Could not finalize file in Downloads/Hazel")
                return Uri.fromFile(destination)
            } finally {
                temporary.delete()
            }
        }

        val resolver = context.contentResolver
        val finalName = uniqueMediaStoreName(context, safeName)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, finalName)
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

    private fun uniqueFileName(folder: File, desiredName: String): String {
        if (!File(folder, desiredName).exists()) return desiredName
        val dot = desiredName.lastIndexOf('.')
        val stem = if (dot > 0) desiredName.substring(0, dot) else desiredName
        val extension = if (dot > 0) desiredName.substring(dot) else ""
        var index = 2
        while (File(folder, "$stem ($index)$extension").exists()) index++
        return "$stem ($index)$extension"
    }

    private fun uniqueMediaStoreName(context: Context, desiredName: String): String {
        val resolver = context.contentResolver
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/Hazel"
        fun exists(name: String): Boolean {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(relativePath, name),
                null
            ).use { cursor -> return cursor?.moveToFirst() == true }
        }
        if (!exists(desiredName)) return desiredName
        val dot = desiredName.lastIndexOf('.')
        val stem = if (dot > 0) desiredName.substring(0, dot) else desiredName
        val extension = if (dot > 0) desiredName.substring(dot) else ""
        var index = 2
        var candidate: String
        do {
            candidate = "$stem ($index)$extension"
            index++
        } while (exists(candidate))
        return candidate
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
