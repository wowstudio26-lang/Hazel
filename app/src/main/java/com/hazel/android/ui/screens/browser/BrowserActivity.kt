package com.hazel.android.ui.screens.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.hazel.android.MainActivity
import com.hazel.android.data.SettingsRepository
import com.hazel.android.ui.theme.HazelTheme

/**
 * Hosts the browser.
 *
 * An activity of its own rather than a destination in the app's nav graph, for one reason:
 * the browser is the one screen that is genuinely a different mode of use. It is what the
 * user is doing while they browse, and when they leave it they expect to be back where they
 * were, not on Hazel's home screen.
 *
 * The download hand-off deliberately goes through the share path the app already has:
 * [handoff] starts [MainActivity] with the stream URL as a shared link, exactly as another
 * app would. That means the browser adds no download code at all — no second queue, no
 * second set of format rules — and the sheet the user sees is identical to the one a
 * pasted link opens. If that path ever changes, this one changes with it for free.
 */
class BrowserActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val startUrl = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_HOME

        setContent {
            val savedTheme by remember(this) { SettingsRepository.isDarkTheme(this) }
                .collectAsState(initial = null)
            val accentName by remember(this) { SettingsRepository.getAccentColor(this) }
                .collectAsState(initial = null)

            HazelTheme(
                darkTheme = savedTheme ?: true,
                accentName = accentName ?: DEFAULT_ACCENT,
            ) {
                BrowserScreen(
                    initialUrl = startUrl,
                    onOpenDownloadSheet = { media ->
                        handoff(media.url, media.title)
                        finish()
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    /**
     * Gives a found stream to the app's own download flow and leaves the browser.
     *
     * Goes through [MainActivity]'s share handling rather than the queue directly, so a
     * stream discovered here is treated exactly like a link shared from another app.
     */
    private fun handoff(url: String, title: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_SUBJECT, title)
            // Brings the download screen forward behind the sheet, so dismissing the sheet
            // leaves the user somewhere that makes sense.
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, ROUTE_DOWNLOAD)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    companion object {
        const val EXTRA_URL = "hazel.browser.url"

        /** Where the browser opens when nothing else was asked for. */
        private const val DEFAULT_HOME = "https://duckduckgo.com/"

        private const val DEFAULT_ACCENT = "orange"

        /** Must match the download destination's route in the app's nav graph. */
        private const val ROUTE_DOWNLOAD = "download"

        /** Opens the browser on [url], or on its home page when [url] is null. */
        fun intent(context: Context, url: String? = null): Intent =
            Intent(context, BrowserActivity::class.java).apply {
                if (!url.isNullOrBlank()) putExtra(EXTRA_URL, url)
            }
    }
}
