package com.hazel.android.ui.screens.browser

import android.annotation.SuppressLint
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hazel.android.R
import com.hazel.android.download.WebViewProxy
import com.hazel.android.ui.components.HazelUrlBar

/**
 * The in-app browser.
 *
 * A page, a bar above it, and a chip over it as soon as the page holds something playable.
 * The user browses as normal; when a stream appears, tapping the chip opens Hazel's own
 * download sheet with the URL already filled in, which is the same sheet a pasted link
 * opens.
 *
 * Nothing about downloading lives here. The browser's whole job is to notice a stream and
 * hand its URL over; the queue, the formats, the metadata and the proxy are the download
 * side's business, and it already works.
 *
 * With a proxy set, the page must go through it or not open at all — see [WebViewProxy].
 * A browser showing content from the device's own address while the downloads come from
 * somewhere else would give away the address the proxy exists to hide.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    initialUrl: String,
    onOpenDownloadSheet: (DetectedMedia) -> Unit,
    onClose: () -> Unit,
    viewModel: BrowserViewModel = viewModel(),
) {
    val context = LocalContext.current

    // Held across recomposition: a WebView rebuilt mid-session would lose the page.
    var webView by remember { mutableStateOf<WebView?>(null) }
    var proxyBlocked by remember { mutableStateOf(false) }
    var showStreams by remember { mutableStateOf(false) }

    val sniffer = remember {
        MediaSniffer { detected ->
            // Called on the bridge thread; the view model posts to the main thread itself.
            viewModel.onMediaDetected(detected) { block -> webView?.post(block) }
        }
    }

    BackHandler(enabled = viewModel.canGoBack) {
        webView?.goBack()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BrowserUrlBar(
            url = viewModel.currentUrl,
            isLoading = viewModel.isLoading,
            canGoBack = viewModel.canGoBack,
            canGoForward = viewModel.canGoForward,
            onClose = onClose,
            onSubmit = { typed -> webView?.loadUrl(SnifferScript.toLoadableUrl(typed)) },
        )

        if (viewModel.isLoading) {
            LinearProgressIndicator(
                progress = { viewModel.progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (proxyBlocked) {
                Text(
                    text = stringResource(R.string.browser_proxy_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.configureForBrowser(viewModel)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            addJavascriptInterface(sniffer, "HazelSniffer")
                            webViewClient = browserClient(viewModel, sniffer, context.assets)
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    viewModel.onProgressChanged(newProgress)
                                }
                            }
                            webView = this
                            if (WebViewProxy.inStep()) {
                                loadUrl(initialUrl)
                            } else {
                                proxyBlocked = true
                            }
                        }
                    },
                )
            }

            // The chip. Only over a page, and only when there is something to offer.
            if (viewModel.media.isNotEmpty()) {
                BrowserDownloadChip(
                    count = viewModel.media.size,
                    onClick = {
                        // One stream is unambiguous, so open its sheet directly. Several, and
                        // the user has to say which first.
                        if (viewModel.media.size == 1) onOpenDownloadSheet(viewModel.media.first())
                        else showStreams = true
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                )
            }
        }
    }

    if (showStreams) {
        BrowserStreamsSheet(
            items = viewModel.media,
            onPick = { item ->
                showStreams = false
                onOpenDownloadSheet(item)
            },
            onDismiss = { showStreams = false },
        )
    }
}

/** The bar above the page: address, history, and the way out. */
@Composable
private fun BrowserUrlBar(
    url: String,
    isLoading: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onClose: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var typed by remember(url) { mutableStateOf(url) }

    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            HazelUrlBar(
                value = typed,
                onValueChange = { typed = it },
                hint = stringResource(R.string.browser_url_hint),
                onSubmit = { onSubmit(typed) },
                trailing = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.browser_back))
                    }
                },
            )
        }
    }
}

/** The floating Download button drawn over the page. */
@Composable
private fun BrowserDownloadChip(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(
                Icons.Filled.Download,
                contentDescription = stringResource(R.string.browser_download),
            )
            Text(
                text = if (count == 1) stringResource(R.string.browser_download)
                else stringResource(R.string.browser_streams_found, count),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** Page settings shared by every browser tab. */
private fun WebSettings.configureForBrowser(viewModel: BrowserViewModel) {
    javaScriptEnabled = true
    domStorageEnabled = true
    databaseEnabled = true
    loadWithOverviewMode = true
    useWideViewPort = true
    mediaPlaybackRequiresUserGesture = false
    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    userAgentString = if (viewModel.desktopMode) {
        // The desktop UA is what makes sites serve their full player, which is what a user
        // asking for "desktop site" is after.
        DESKTOP_USER_AGENT
    } else {
        userAgentString
    }
}

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36"

/** The client that watches requests and injection. */
private fun browserClient(
    viewModel: BrowserViewModel,
    sniffer: MediaSniffer,
    assets: android.content.res.AssetManager,
): WebViewClient = object : WebViewClient() {

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        viewModel.onPageStarted(url.orEmpty())
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        viewModel.onPageFinished(url.orEmpty(), view?.title.orEmpty())
        // Injected after load, not during: the asset reads and the string build are cheap,
        // but running the script mid-parse would race the page's own setup.
        view?.evaluateJavascript(scriptText(assets), null)
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: android.webkit.WebResourceError?,
    ) {
        // Only the main frame matters; a blocked ad should not blank the screen.
        if (request?.isForMainFrame == true) {
            viewModel.onLoadError(error?.description?.toString())
        }
    }

    /** The passive net: media the page fetched before any script could see it. */
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        sniffer.offerFromRequest(request?.url?.toString())
        // Null means "carry on loading normally"; the browser never modifies the response.
        return null
    }
}

@Volatile
private var cachedScript: String? = null

private fun scriptText(assets: android.content.res.AssetManager): String =
    cachedScript ?: synchronized(SnifferScript) {
        cachedScript ?: buildString {
            append(readAsset(assets, "browser/sniffer-page.js"))
            append('\n')
            append(readAsset(assets, "browser/sniffer-network.js"))
        }.also { cachedScript = it }
    }

private fun readAsset(assets: android.content.res.AssetManager, name: String): String =
    runCatching { assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault("")
