package com.hazel.android.download

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Optional client for the PC-Free Hazel HTTP Bridge.
 *
 * This client deliberately uses HTTPS for non-local endpoints. It is a transport helper;
 * callers must collect the endpoint and token from the user and must not hard-code secrets.
 * The bridge itself must be deployed on a host/network where torrent use is permitted.
 */
class RemoteBridgeClient(
    baseUrl: String,
    private val bearerToken: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
) {
    private val baseUrl: String = baseUrl.trim().trimEnd('/')

    @Volatile private var activeDownloadCall: Call? = null

    /** Cancels the currently-running file transfer, if any. */
    fun cancelActiveFileDownload() {
        activeDownloadCall?.cancel()
    }

    init {
        val parsed = Uri.parse(this.baseUrl)
        require(parsed.scheme == "https" || parsed.host in setOf("127.0.0.1", "localhost", "::1")) {
            "Bridge endpoint must use HTTPS except for loopback testing"
        }
        require(parsed.host != null && parsed.userInfo == null && parsed.query == null && parsed.fragment == null) {
            "Bridge endpoint must be a plain base URL without credentials, query, or fragment"
        }
        require(bearerToken.length >= 32) { "Bridge token must be at least 32 characters" }
    }

    data class Job(
        val id: String,
        val status: String,
        val message: String,
        val files: List<String>
    )

    suspend fun checkConnection(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/health")
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Bridge health check failed (${response.code})")
            val json = JSONObject(raw)
            if (json.optString("status") != "ok") throw IOException("Bridge health check returned an unexpected status")
            json.optString("service", "PC-Free bridge")
        }
    }

    suspend fun createMagnetJob(magnetUri: String): Job = withContext(Dispatchers.IO) {
        require(magnetUri.startsWith("magnet:?") &&
            (magnetUri.contains("xt=urn:btih:") || magnetUri.contains("xt=urn:btmh:"))) {
            "Enter a valid BitTorrent magnet link"
        }
        val payload = JSONObject().put("magnet_uri", magnetUri).toString()
        val request = authenticatedRequest("$baseUrl/api/v1/jobs")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        executeJson(request)
    }

    suspend fun getJob(jobId: String): Job = withContext(Dispatchers.IO) {
        require(jobId.matches(Regex("^[0-9a-f]{32}$"))) { "Invalid bridge job ID" }
        executeJson(authenticatedRequest("$baseUrl/api/v1/jobs/$jobId").get().build())
    }

    suspend fun listJobs(): List<Job> = withContext(Dispatchers.IO) {
        val request = authenticatedRequest("$baseUrl/api/v1/jobs").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Bridge request failed (${response.code}): ${responseMessage(response.body?.string())}")
            val array = JSONArray(response.body?.string() ?: "[]")
            (0 until array.length()).map { parseJob(array.getJSONObject(it)) }
        }
    }

    suspend fun cancelJob(jobId: String) = withContext(Dispatchers.IO) {
        require(jobId.matches(Regex("^[0-9a-f]{32}$"))) { "Invalid bridge job ID" }
        val request = authenticatedRequest("$baseUrl/api/v1/jobs/$jobId").delete().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 204) {
                throw IOException("Bridge cancel failed (${response.code}): ${responseMessage(response.body?.string())}")
            }
        }
    }

    /** Downloads one completed bridge file to a temporary sibling, then renames it. */
    suspend fun downloadFile(
        jobId: String,
        relativePath: String,
        destination: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
            require(jobId.matches(Regex("^[0-9a-f]{32}$"))) { "Invalid bridge job ID" }
            val segments = relativePath.split('/').filter { it.isNotEmpty() }
            require(segments.isNotEmpty() && segments.none { it == "." || it == ".." }) {
                "Invalid bridge file path"
            }
            val encodedPath = segments.joinToString("/") { Uri.encode(it) }
            val request = authenticatedRequest("$baseUrl/api/v1/jobs/$jobId/files/$encodedPath")
                .get().build()
            destination.parentFile?.mkdirs()
            val part = File(destination.parentFile ?: File("."), destination.name + ".part")
            val call = http.newCall(request)
            activeDownloadCall = call
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Bridge file request failed (${response.code}): ${responseMessage(response.body?.string())}")
                    }
                    val body = response.body ?: throw IOException("Bridge returned an empty response")
                    val total = body.contentLength()
                    var downloaded = 0L
                    var lastReported = 0L
                    body.byteStream().buffered().use { input ->
                        part.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                if (call.isCanceled()) throw IOException("File transfer cancelled")
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                downloaded += count
                                if (downloaded - lastReported >= 512 * 1024 ||
                                    (total > 0 && downloaded >= total)) {
                                    lastReported = downloaded
                                    onProgress(downloaded, total)
                                }
                            }
                        }
                    }
                }
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Could not replace existing destination file")
                }
                if (!part.renameTo(destination)) {
                    throw IOException("Could not finalize downloaded file")
                }
                destination
            } catch (failure: Exception) {
                part.delete()
                throw failure
            } finally {
                if (activeDownloadCall === call) activeDownloadCall = null
            }
        }

    private fun authenticatedRequest(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $bearerToken")
            .header("Accept", "application/json")

    private fun executeJson(request: Request): Job {
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Bridge request failed (${response.code}): ${responseMessage(raw)}")
            }
            return parseJob(JSONObject(raw))
        }
    }

    private fun parseJob(json: JSONObject): Job {
        val filesJson = json.optJSONArray("files") ?: JSONArray()
        val files = (0 until filesJson.length()).mapNotNull { filesJson.optString(it).takeIf(String::isNotBlank) }
        return Job(
            id = json.getString("id"),
            status = json.optString("status", "unknown"),
            message = json.optString("message"),
            files = files
        )
    }

    private fun responseMessage(raw: String?): String {
        if (raw.isNullOrBlank()) return "No error details"
        return runCatching { JSONObject(raw).optString("detail").ifBlank { raw } }.getOrDefault(raw).take(300)
    }
}
