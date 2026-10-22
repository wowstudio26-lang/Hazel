package com.hazel.android.download

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONObject

/** Durable, secret-free state for the one foreground remote-file transfer. */
object RemoteBridgeTransferStore {
    private const val PREFS = "remote_bridge_transfer"
    private const val STATE_KEY = "state"

    data class State(
        val jobId: String,
        val relativePath: String,
        val displayName: String,
        val status: String,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = -1L,
        val fileUri: String? = null,
        val message: String = "",
        val updatedAtMillis: Long = System.currentTimeMillis()
    ) {
        val isActive: Boolean
            get() = status in setOf("queued", "downloading", "cancelling")
    }

    fun load(context: Context): State? = readPreferences(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(context: Context): Flow<State?> = callbackFlow {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == STATE_KEY) trySend(readPreferences(changed))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(readPreferences(prefs))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun save(context: Context, state: State) {
        val json = JSONObject()
            .put("jobId", state.jobId)
            .put("relativePath", state.relativePath)
            .put("displayName", state.displayName)
            .put("status", state.status)
            .put("downloadedBytes", state.downloadedBytes)
            .put("totalBytes", state.totalBytes)
            .put("fileUri", state.fileUri)
            .put("message", state.message)
            .put("updatedAtMillis", state.updatedAtMillis)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(STATE_KEY, json.toString())
            .apply()
    }

    fun update(context: Context, transform: (State) -> State) {
        synchronized(this) {
            val current = load(context) ?: return
            save(context, transform(current).copy(updatedAtMillis = System.currentTimeMillis()))
        }
    }

    private fun readPreferences(prefs: SharedPreferences): State? = runCatching {
        val raw = prefs.getString(STATE_KEY, null) ?: return null
        val json = JSONObject(raw)
        val jobId = json.getString("jobId")
        require(jobId.matches(Regex("^[0-9a-f]{32}$")))
        State(
            jobId = jobId,
            relativePath = json.getString("relativePath"),
            displayName = json.optString("displayName", "remote-download.bin"),
            status = json.optString("status", "unknown"),
            downloadedBytes = json.optLong("downloadedBytes"),
            totalBytes = json.optLong("totalBytes", -1L),
            fileUri = json.optString("fileUri").takeIf(String::isNotBlank),
            message = json.optString("message"),
            updatedAtMillis = json.optLong("updatedAtMillis", 0L)
        )
    }.getOrNull()
}
