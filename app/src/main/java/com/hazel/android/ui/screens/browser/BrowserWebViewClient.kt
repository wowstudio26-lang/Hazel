package com.hazel.android.ui.screens.browser

import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Page settings and the client that watches requests and injects the sniffer.
 *
 * Kept out of the screen so the composable stays about layout. Nothing here downloads
 * anything: [WebViewClient.shouldInterceptRequest] looks at requests and returns null for
 * every one of them, which leaves the page loading exactly as it would without Hazel.
 */
internal fun WebView.configureForBrowser() {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        loadWithOverviewMode = true
        useWideViewPort = true
        // Autoplay is allowed because the media a user came to find is often behind a play
        // button, and a tap that does nothing reads as a broken page.
        mediaPlaybackRequiresUserGesture = false
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    }
}

/**
 * The desktop user agent, applied when the user asks for the desktop site.
 *
 * Sites serve their full player to this and a cut-down one otherwise, which is the whole
 * point of the setting. Passing false restores the engine's own agent.
 */
internal fun WebView.applyDesktopUserAgent(enabled: Boolean) {
    val current = settings.userAgentString.orEmpty()
    val isDesktop = current.contains("Windows NT") || current.contains("Macintosh")
    when {
        enabled && !isDesktop -> settings.userAgentString = DESKTOP_USER_AGENT
        !enabled && isDesktop -> settings.userAgentString = null
    }
}

internal const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36"

/**
 * The client that reports page lifecycle to the view model, injects the sniffer once a page
 * has loaded, and watches the network for media the script could not see.
 */
internal fun browserClient(
    viewModel: BrowserViewModel,
    sniffer: MediaSniffer,
): WebViewClient = object : WebViewClient() {

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        viewModel.onPageStarted(url.orEmpty())
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        viewModel.onPageFinished(url.orEmpty(), view?.title.orEmpty())
        // Injected after load rather than during: the page's own setup would otherwise race
        // this, and a player built mid-parse would be watched half-built.
        val appContext = view?.context?.applicationContext
        if (appContext != null) {
            view.evaluateJavascript(SnifferScript.script(appContext), null)
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        // Only the main frame matters; a blocked ad should not blank the screen.
        if (request?.isForMainFrame == true) {
            viewModel.onLoadError(error?.description?.toString())
        }
    }

    /** The passive net: media fetched before the script could see it. */
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        sniffer.offerFromRequest(request?.url?.toString())
        // Null means "carry on loading normally"; the response is never modified.
        return null
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        viewModel.onNavigationChanged(
            canBack = view?.canGoBack() == true,
            canForward = view?.canGoForward() == true,
        )
    }
}
