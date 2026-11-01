package com.hazel.android.ui.screens.browser

/**
 * One playable stream the browser noticed on the page.
 *
 * Deliberately dumb: a URL, what kind of thing it is, and a human label for the chip.
 * Everything a download needs beyond that is decided later by the download sheet, from
 * the URL itself, so the browser never grows a second opinion about formats.
 */
data class DetectedMedia(
    val url: String,
    val kind: Kind,
    /** Page or element title, for the chip. May be blank. */
    val title: String = "",
    /** MIME reported by the page or the response, e.g. "video/mp4". May be blank. */
    val mimeType: String = "",
) {
    enum class Kind { VIDEO, AUDIO, PLAYLIST }

    /**
     * What to show on the chip. Falls back through title, MIME, then the URL's last
     * path segment, because a chip reading "Stream" for every row tells the user nothing.
     */
    val label: String
        get() = title.ifBlank { mimeType.substringAfter('/', "") }
            .ifBlank { url.substringAfterLast('/').substringBefore('?') }
            .ifBlank { url }
}
