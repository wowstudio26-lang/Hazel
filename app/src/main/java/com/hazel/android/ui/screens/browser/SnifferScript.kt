package com.hazel.android.ui.screens.browser

import android.content.Context
import android.net.Uri

/**
 * Reads the two sniffer scripts out of the app's assets.
 *
 * Kept as assets rather than Kotlin string literals for two reasons: the scripts stay
 * readable and lintable as JavaScript, and no escaping in a Kotlin string can quietly
 * corrupt a regex. [script] joins them in order and caches the result, because it is asked
 * for on every page load and the files never change while the app runs.
 */
object SnifferScript {

    private const val ASSET_DIR = "browser"

    /** Injected in this order: elements first, then the script hooks that use its helpers. */
    private val FILES = listOf("sniffer-page.js", "sniffer-network.js")

    @Volatile
    private var cached: String? = null

    /** The whole script, ready to hand to `evaluateJavascript`. */
    fun script(context: Context): String = cached ?: synchronized(this) {
        cached ?: load(context).also { cached = it }
    }

    private fun load(context: Context): String = FILES.joinToString(separator = "\n") { name ->
        runCatching {
            context.assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    /**
     * Whether a tapped or long-pressed link should be offered as a download rather than
     * followed. Media files are the case worth interrupting for; a page URL is not.
     */
    fun isDownloadableLink(url: String?): Boolean {
        val target = url.orEmpty()
        if (target.isBlank()) return false
        if (target.startsWith("blob:") || target.startsWith("data:")) return false
        val noQuery = target.substringBefore('?').substringBefore('#')
        val extension = noQuery.substringAfterLast('.', "")
        return extension.lowercase() in DOWNLOADABLE_EXTENSIONS
    }

    val DOWNLOADABLE_EXTENSIONS = setOf(
        "m3u8", "mpd", "mp4", "m4v", "mov", "webm", "mkv", "ts",
        "m4a", "mp3", "aac", "flac", "ogg", "opus", "wav",
    )

    /**
     * Turns whatever the user typed into something worth loading.
     *
     * A bare word is a search, not a host, so it goes to the search engine. Anything that
     * already looks like an address keeps it. This is the one place that decision is made,
     * so the URL bar and the "open external" path cannot disagree.
     */
    fun toLoadableUrl(input: String, searchTemplate: String = "https://duckduckgo.com/?q=%s"): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        val looksLikeHost = trimmed.contains('.') && !trimmed.contains(' ')
        if (looksLikeHost) return "https://$trimmed"
        return searchTemplate.replace("%s", Uri.encode(trimmed))
    }
}
