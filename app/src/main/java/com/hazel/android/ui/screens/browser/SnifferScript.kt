package com.hazel.android.ui.screens.browser

import android.content.Context
import android.net.Uri

/**
 * The browser's two mechanical jobs: handing the page its sniffer script, and deciding
 * what a URL is.
 *
 * The rules live here rather than in the web view so they can be tested on the JVM, with no
 * WebView and no device. They are also the only place these decisions are made: the chip,
 * the long-press menu and the download hand-off all ask the same questions, so they cannot
 * disagree about whether something is worth downloading.
 */
object SnifferScript {

    private const val ASSET_DIR = "browser"

    /** Injected in this order: elements first, then the script hooks that use its helpers. */
    private val FILES = listOf("sniffer-page.js", "sniffer-network.js")

    /**
     * Extensions worth offering. Deliberately not `ts`: in this project that means the
     * TypeScript sources, and an HLS segment is never something the user wants by name.
     */
    val DOWNLOADABLE_EXTENSIONS = setOf(
        "m3u8", "mpd", "mp4", "m4v", "mov", "webm", "mkv",
        "m4a", "mp3", "aac", "flac", "ogg", "opus", "wav",
    )

    private val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "aac", "flac", "ogg", "opus", "wav")

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
     * followed. A page URL is not; a media file is.
     */
    fun isDownloadableLink(url: String?): Boolean = extensionOf(url?.takeIf {
        !it.startsWith("blob:") && !it.startsWith("data:")
    }) in DOWNLOADABLE_EXTENSIONS

    /** Whether a stream seen in a network response is worth recording. */
    fun isMediaUrl(url: String?): Boolean = isDownloadableLink(url)

    /**
     * What kind of stream a URL names. The URL decides first, because a playlist served as
     * `application/octet-stream` is still a playlist; the MIME only has a say when there is
     * no extension to read.
     */
    fun classify(url: String, mimeType: String = ""): DetectedMedia.Kind = when {
        extensionOf(url) == "m3u8" || extensionOf(url) == "mpd" -> DetectedMedia.Kind.PLAYLIST
        extensionOf(url) in AUDIO_EXTENSIONS -> DetectedMedia.Kind.AUDIO
        mimeType.startsWith("audio/", ignoreCase = true) -> DetectedMedia.Kind.AUDIO
        else -> DetectedMedia.Kind.VIDEO
    }

    /**
     * Turns whatever the user typed into something worth loading.
     *
     * A bare word is a search, not a host, so it goes to the search engine. Anything that
     * already looks like an address keeps it. One place decides this, so the URL bar and
     * the "open external" path cannot disagree.
     */
    fun toLoadableUrl(input: String, searchTemplate: String = "https://duckduckgo.com/?q=%s"): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        val looksLikeHost = trimmed.contains('.') && !trimmed.contains(' ')
        if (looksLikeHost) return "https://$trimmed"
        return searchTemplate.replace("%s", Uri.encode(trimmed))
    }

    /**
     * The lowercase extension, or "" when there is none.
     *
     * The fragment is dropped before the query, because it is not part of what is fetched:
     * a link ending `#x.mp4` is a page with an anchor, not a video.
     */
    private fun extensionOf(url: String?): String {
        val target = url.orEmpty()
        if (target.isBlank()) return ""
        val path = target.substringBefore('?').substringBefore('#')
        return path.substringAfterLast('.', "").lowercase()
    }
}
