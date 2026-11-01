package com.hazel.android.ui.screens.browser

import android.webkit.JavascriptInterface

/**
 * Watches a page for playable media and reports each stream once.
 *
 * Two nets, because either one alone misses a kind of site. The injected script
 * ([SnifferScript]) sees what the page's own scripts do, which is the only way to catch a
 * player that fetches an `.m3u8` and assembles frames by hand. [offerFromRequest] sees what
 * the network does, catching a stream the script never named.
 *
 * Both are de-duplicated by URL, because a single video routinely shows up three or four
 * times: once from the element, once from a `<source>`, once from the network. Offering
 * the same stream four times is how a download list becomes unusable.
 *
 * The JS bridge calls [onDetected] on a WebView worker thread, not the main thread.
 * Callers that touch UI state must hop back themselves; nothing here does it for them.
 */
class MediaSniffer(private val onDetected: (DetectedMedia) -> Unit) {

    /** URLs already reported, oldest first. A page's streams, not a history. */
    private val seen = LinkedHashSet<String>()

    /** Called from the page's JavaScript. Thread: WebView bridge thread. */
    @JavascriptInterface
    fun onMediaDetected(url: String?, mimeType: String?, title: String?) {
        offer(url, mimeType, title)
    }

    /**
     * Records a stream the page reported. Returns true if it had not been seen before, so
     * a caller can skip waking the UI for a duplicate.
     */
    fun offer(url: String?, mimeType: String? = "", title: String? = ""): Boolean =
        record(url, mimeType.orEmpty(), title.orEmpty())

    /**
     * Records a stream seen in a network response. Ignores anything that is not media, so
     * this is safe to call for every request the page makes, without filtering first.
     */
    fun offerFromRequest(url: String?, mimeType: String? = ""): Boolean {
        val mime = mimeType.orEmpty()
        val isMedia = SnifferScript.isMediaUrl(url) ||
            mime.startsWith("video/", ignoreCase = true) ||
            mime.startsWith("audio/", ignoreCase = true)
        if (!isMedia) return false
        return record(url, mime, "")
    }

    /** Forgets every URL. Called when a new page loads, so the list is per page. */
    fun reset() {
        synchronized(seen) { seen.clear() }
    }

    private fun record(url: String?, mimeType: String, title: String): Boolean {
        val target = url.orEmpty().trim()
        if (target.isEmpty()) return false
        // Data and blob URLs name no resource anything else can fetch. The manifest behind
        // them is reported separately by the script, and that is the URL worth offering.
        if (target.startsWith("data:") || target.startsWith("blob:")) return false

        synchronized(seen) {
            if (!seen.add(target)) return false
        }
        onDetected(
            DetectedMedia(
                url = target,
                kind = SnifferScript.classify(target, mimeType),
                title = title.takeIf { it.isNotBlank() && it != target }.orEmpty(),
                mimeType = mimeType,
            ),
        )
        return true
    }
}
