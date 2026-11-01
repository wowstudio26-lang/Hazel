package com.hazel.android.ui.screens.browser

import java.net.URLEncoder

/** Query-string encoding. See SnifferScript.toLoadableUrl for why a space is not a plus. */
internal object QueryEncoding {
    fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
