package com.hazel.android.ui.screens.browser

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hazel.android.R
import com.hazel.android.download.WebViewProxy

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
 * With a proxy set, the page goes through it or does not open at all, because a browser
 * showing content from the device's own address while the downloads come from somewhere
 * else would give away the address the proxy exists to hide. See [WebViewProxy].
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
            // Called on the bridge thread; the view model hops to the main thread itself.
            viewModel.onMediaDetected(detected) { block -> webView?.post(block) }
        }
    }

    BackHandler(enabled = viewModel.canGoBack) { webView?.goBack() }

    Column(modifier = Modifier.fillMaxSize()) {
        BrowserUrlBar(
            url = viewModel.currentUrl,
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
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
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
                            configureForBrowser()
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            addJavascriptInterface(sniffer, JS_BRIDGE_NAME)
                            webViewClient = browserClient(viewModel, sniffer)
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
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
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

/** The name the page sees for the bridge. Must match the name the script calls. */
const val JS_BRIDGE_NAME = "HazelSniffer"

/** The bar above the page: address, and the way out. */
@Composable
private fun BrowserUrlBar(
    url: String,
    onClose: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var typed by remember(url) { mutableStateOf(url) }
    val hint = stringResource(R.string.browser_url_hint)

    Surface(tonalElevation = 3.dp) {
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit(typed) }),
            trailingIcon = {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.browser_back),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        )
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
