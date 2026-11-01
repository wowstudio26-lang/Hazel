package com.hazel.android.ui.screens.browser

import android.content.Context

/**
 * The browser's two mechanical jobs: handing the page its sniffer script, and deciding what
 * a URL is.
 *
 * The rules are here rather than in the web view so they can be tested on the JVM, with no
 * WebView and no device. Nothing below the script loader may touch an Android class: the
 * stubs on a unit-test classpath throw on every call, so one Uri.encode would take the whole
 * test file with it. Query strings go through [QueryEncoding] for that reason, and it is
 * also where the reason a space is written as %20 rather than a plus is explained.
 *
 * These are also the only place these decisions are made, so the chip, the long-press menu
 * and the hand-off cannot disagree about whether something is worth downloading.
 */
object SnifferScript {

    private const val ASSET_DIR = "browser"

    /** Injected in this order: elements first, then the script hooks that use its helpers. */
    private val FILES = listOf("sniffer-page.js", "sniffer-network.js")

    /**
     * Extensions worth offering.
     *
     * No `ts`: in this project that is the TypeScript sources, and on an HLS page it is a
     * media segment, fetched by the hundred at a few seconds each. Offering those would bury
     * the one manifest the user actually wants under a list of fragments.
     */
    val DOWNLOADABLE_EXTENSIONS = setOf(
        "m3u8", "mpd", "mp4", "m4v", "mov", "webm", "mkv",
        "m4a", "mp3", "aac", "flac", "ogg", "opus", "wav",
    )

    private val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "aac", "flac", "ogg", "opus", "wav")

    @Volatile
    private var cached: String? = null

    /** The whole script, ready to hand to evaluateJavascript. */
    fun script(context: Context): String = cached ?: synchronized(this) {
        cached ?: load(context).also { cached = it }
    }

    private fun load(context: Context): String = FILES.joinToString(separator = "\n") { name ->
        runCatching {
            context.assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    /**
     * Whether a link should be offered as a download rather than followed.
     *
     * Blob and data URLs are turned down: they name a buffer in the page, not a resource
     * anything else can fetch, so there is nothing to hand to a downloader.
     */
    fun isDownloadableLink(url: String?): Boolean {
        val target = url.orEmpty()
        if (target.isBlank()) return false
        if (target.startsWith("blob:") || target.startsWith("data:")) return false
        return extensionOf(target) in DOWNLOADABLE_EXTENSIONS
    }

    /** Whether a stream seen in a network response is worth recording. */
    fun isMediaUrl(url: String?): Boolean = isDownloadableLink(url)

    /**
     * What kind of stream a URL names. The URL decides first, because a playlist served as
     * application/octet-stream is still a playlist; the MIME only speaks when there is no
     * extension to read.
     */
    fun classify(url: String, mimeType: String = ""): DetectedMedia.Kind {
        val extension = extensionOf(url)
        return when {
            extension == "m3u8" || extension == "mpd" -> DetectedMedia.Kind.PLAYLIST
            extension in AUDIO_EXTENSIONS -> DetectedMedia.Kind.AUDIO
            mimeType.startsWith("audio/", ignoreCase = true) -> DetectedMedia.Kind.AUDIO
            else -> DetectedMedia.Kind.VIDEO
        }
    }

    /** A bare word is a search; anything already shaped like an address is loaded as one. */
    fun toLoadableUrl(input: String, searchTemplate: String = SEARCH_TEMPLATE): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        val looksLikeHost = trimmed.contains('.') && !trimmed.contains(' ')
        if (looksLikeHost) return "https://$trimmed"
        return searchTemplate.replace("%s", QueryEncoding.encode(trimmed))
    }

    /**
     * Where a typed word is searched. DuckDuckGo for the same reason the rest of the app
     * keeps search suggestions optional: it answers without building a profile of the device.
     */
    const val SEARCH_TEMPLATE = "https://duckduckgo.com/?q=%s"

    /**
     * The lowercase extension, or "" when there is none.
     *
     * The fragment is dropped before the query, because it is not fetched: a link ending
     * #x.mp4 is a page with an anchor, not a video.
     */
    private fun extensionOf(url: String): String {
        if (url.isBlank()) return ""
        val path = url.substringBefore('?').substringBefore('#')
        return path.substringAfterLast('.', "").lowercase()
    }
}
