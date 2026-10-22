package com.hazel.android.download

import android.content.Context
import org.json.JSONObject

/** Remembers the last remote job on-device so Hazel can reconnect to it after reopening. */
object RemoteBridgeJobStore {
    private const val PREFS = "remote_bridge_jobs"
    private const val ENTRY = "last_job"

    data class SavedJob(val id: String, val magnetUri: String)

    fun load(context: Context): SavedJob? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ENTRY, null)
            ?: return null
        val json = JSONObject(raw)
        val id = json.getString("id")
        require(id.matches(Regex("^[0-9a-f]{32}$")))
        SavedJob(id, json.optString("magnet_uri"))
    }.getOrNull()

    fun save(context: Context, id: String, magnetUri: String) {
        require(id.matches(Regex("^[0-9a-f]{32}$")))
        val raw = JSONObject().put("id", id).put("magnet_uri", magnetUri).toString()
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ENTRY, raw).commit()) { "Could not save the remote job" }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(ENTRY).commit()
    }
}
