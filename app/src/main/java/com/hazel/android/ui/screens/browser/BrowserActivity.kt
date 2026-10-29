package com.hazel.android.ui.screens.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hazel.android.ui.share.ShareOverlayActivity

/**
 * Lightweight in-app browser. When a video element starts playing, its current source is
 * offered to the app's existing share/download sheet, which then resolves real formats.
 *
 * This does not extract DRM keys or bypass site access controls. Sites that hide media in
 * protected players or do not expose a usable media URL may not provide a downloadable link.
 */
class BrowserActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BrowserScreen(
                onClose = { finish() },
                onDownload = { mediaUrl ->
                    startActivity(
                        Intent(this, ShareOverlayActivity::class.java).apply {
                            action = Intent.ACTION_SEND
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, mediaUrl)
                        }
                    )
                }
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun BrowserScreen(
        onClose: () -> Unit,
        onDownload: (String) -> Unit
    ) {
        val context = LocalContext.current
        var address by remember { mutableStateOf("https://www.google.com") }
        var currentUrl by remember { mutableStateOf("") }
        var pageTitle by remember { mutableStateOf("Leo Browser") }
        var mediaUrl by remember { mutableStateOf<String?>(null) }
        var progress by remember { mutableIntStateOf(0) }
        var webView by remember { mutableStateOf<WebView?>(null) }

        fun navigate() {
            val raw = address.trim()
            if (raw.isBlank()) return
            val target = when {
                raw.startsWith("https://", true) || raw.startsWith("http://", true) -> raw
                raw.contains(" ") || !raw.contains(".") -> "https://www.google.com/search?q=" +
                    android.net.Uri.encode(raw)
                else -> "https://$raw"
            }
            mediaUrl = null
            webView?.loadUrl(target)
        }

        BackHandler {
            if (webView?.canGoBack() == true) webView?.goBack() else onClose()
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = pageTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (currentUrl.isNotBlank()) {
                                Text(
                                    text = currentUrl,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (webView?.canGoBack() == true) webView?.goBack() else onClose()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { webView?.reload() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, contentDescription = "Close browser")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Search or enter address") },
                        leadingIcon = {
                            Icon(Icons.Filled.Language, contentDescription = null)
                        }
                    )
                    Button(onClick = { navigate() }) { Text("Go") }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webView = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = true
                                settings.loadsImagesAutomatically = true
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        progress = newProgress
                                    }

                                    override fun onReceivedTitle(view: WebView?, title: String?) {
                                        pageTitle = title?.takeIf { it.isNotBlank() } ?: "Leo Browser"
                                    }
                                }
                                addJavascriptInterface(object {
                                    @JavascriptInterface
                                    fun onVideoPlaying(url: String?) {
                                        val candidate = url?.trim().orEmpty()
                                        if (candidate.startsWith("https://", true) ||
                                            candidate.startsWith("http://", true)
                                        ) {
                                            runOnUiThread {
                                                mediaUrl = candidate
                                                address = this@apply.url.orEmpty()
                                                currentUrl = this@apply.url.orEmpty()
                                            }
                                        }
                                    }
                                }, "LeoBrowserBridge")
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean = false

                                    override fun onPageStarted(
                                        view: WebView?,
                                        url: String?,
                                        favicon: android.graphics.Bitmap?
                                    ) {
                                        currentUrl = url.orEmpty()
                                        address = url.orEmpty()
                                        mediaUrl = null
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        currentUrl = url.orEmpty()
                                        address = url.orEmpty()
                                        val hook = """
                                            (function() {
                                              if (window.__leoBrowserHooked) return;
                                              window.__leoBrowserHooked = true;
                                              function attach(v) {
                                                if (!v || v.__leoHooked) return;
                                                v.__leoHooked = true;
                                                v.addEventListener('play', function() {
                                                  setTimeout(function() {
                                                    var s = v.currentSrc || v.src ||
                                                      (v.querySelector('source') && v.querySelector('source').src) || '';
                                                    if (window.LeoBrowserBridge) {
                                                      if (!s || s.indexOf('blob:') === 0) s = location.href;
                                                      window.LeoBrowserBridge.onVideoPlaying(s);
                                                    }
                                                  }, 250);
                                                }, true);
                                              }
                                              document.querySelectorAll('video').forEach(attach);
                                              new MutationObserver(function() {
                                                document.querySelectorAll('video').forEach(attach);
                                              }).observe(document.documentElement, {childList:true, subtree:true});
                                            })();
                                        """.trimIndent()
                                        view?.evaluateJavascript(hook, null)
                                    }
                                }
                                loadUrl("https://www.google.com")
                            }
                        },
                        update = { view -> webView = view }
                    )

                    if (progress in 1..99) {
                        CircularProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                        )
                    }

                    mediaUrl?.let { detected ->
                        Card(
                            modifier = Modifier.align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(12.dp),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Video detected",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Open the download picker to inspect available video quality and audio tracks.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                                )
                                Text(
                                    detected,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = { onDownload(detected) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Filled.Download, contentDescription = null)
                                        Text("  Choose quality")
                                    }
                                    IconButton(onClick = { mediaUrl = null }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                webView?.apply {
                    stopLoading()
                    removeJavascriptInterface("LeoBrowserBridge")
                    destroy()
                }
                webView = null
            }
        }
    }
}
