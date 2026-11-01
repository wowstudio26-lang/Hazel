package com.hazel.android.ui.screens.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * Everything the browser screen shows that is not the page itself.
 *
 * The web view is owned by the composable, because it is a view and must survive
 * recomposition exactly as it is. This holds the state drawn around it: where the tab is,
 * whether it is loading, and what streams have been found.
 *
 * [media] is append-only for the life of a page and cleared when a new one starts, so the
 * chip list always describes the page on screen. The sniffer de-duplicates by URL, so a
 * stream cannot appear here twice.
 */
class BrowserViewModel : ViewModel() {

    var currentUrl by mutableStateOf("")
        private set

    var pageTitle by mutableStateOf("")
        private set

    /** 0-100, as WebView reports it. */
    var progress by mutableStateOf(0)
        private set

    var isLoading by mutableStateOf(false)
        private set

    var canGoBack by mutableStateOf(false)
        private set

    var canGoForward by mutableStateOf(false)
        private set

    var desktopMode by mutableStateOf(false)
        private set

    /** Streams found on the current page, in the order they were noticed. */
    val media = mutableStateListOf<DetectedMedia>()

    /** Set when a page fails to load, so the screen can say so instead of showing white. */
    var loadError by mutableStateOf<String?>(null)
        private set

    fun onPageStarted(url: String) {
        currentUrl = url
        isLoading = true
        progress = 0
        loadError = null
        // A new page is a new set of streams; anything from the last one is not on screen.
        media.clear()
    }

    fun onPageFinished(url: String, title: String) {
        currentUrl = url
        pageTitle = title
        isLoading = false
    }

    fun onProgressChanged(value: Int) {
        progress = value.coerceIn(0, 100)
        if (progress >= 100) isLoading = false
    }

    fun onNavigationChanged(canBack: Boolean, canForward: Boolean) {
        canGoBack = canBack
        canGoForward = canForward
    }

    fun onLoadError(message: String?) {
        isLoading = false
        loadError = message
    }

    fun setDesktopMode(enabled: Boolean) {
        desktopMode = enabled
    }

    /**
     * Records a stream the sniffer found.
     *
     * Called from the WebView bridge thread, so it is posted to the main thread before it
     * touches [media]: Compose state is not thread-safe, and the bridge thread is not the
     * main one.
     */
    fun onMediaDetected(item: DetectedMedia, post: (() -> Unit) -> Unit) {
        post {
            if (media.none { it.url == item.url }) media.add(item)
        }
    }

    /** Drops the found streams. Used when the user clears the chip list. */
    fun clearMedia() = media.clear()
}
