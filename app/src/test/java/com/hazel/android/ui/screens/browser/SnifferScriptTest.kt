package com.hazel.android.ui.screens.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure part of the browser. Runs on the JVM, so no Android class may appear in it. */
class SnifferScriptTest {

    @Test
    fun `recognises media, with and without a query string`() {
        assertTrue(SnifferScript.isDownloadableLink("https://a.example/video.mp4"))
        assertTrue(SnifferScript.isDownloadableLink("https://a.example/v.mp4?token=abc"))
        assertTrue(SnifferScript.isDownloadableLink("https://a.example/hls/index.m3u8"))
        assertTrue(SnifferScript.isDownloadableLink("https://a.example/audio.M4A"))
        assertTrue(SnifferScript.isDownloadableLink("https://a.example/dash/manifest.mpd"))
    }

    @Test
    fun `turns down pages, anchors, blob and data urls`() {
        assertFalse(SnifferScript.isDownloadableLink("https://a.example/watch"))
        assertFalse(SnifferScript.isDownloadableLink("https://a.example/a.html#x.mp4"))
        assertFalse(SnifferScript.isDownloadableLink("blob:https://a.example/1234"))
        assertFalse(SnifferScript.isDownloadableLink("data:video/mp4;base64,AAAA"))
        assertFalse(SnifferScript.isDownloadableLink(""))
        assertFalse(SnifferScript.isDownloadableLink(null))
    }

    @Test
    fun `turns down hls and dash fragments`() {
        // Hundreds per video, a few seconds each. Offering one would bury the manifest,
        // which is the thing worth downloading.
        assertFalse(SnifferScript.isDownloadableLink("https://a.example/seg/00001.ts"))
        assertFalse(SnifferScript.isDownloadableLink("https://a.example/dash/init.m4s"))
        assertFalse(SnifferScript.isDownloadableLink("https://a.example/seg/1.ts?range=0-9"))
    }

    @Test
    fun `a bare word searches, a host is loaded`() {
        assertEquals("https://example.com", SnifferScript.toLoadableUrl("example.com"))
        assertEquals("https://example.com/x", SnifferScript.toLoadableUrl("https://example.com/x"))
        assertEquals("", SnifferScript.toLoadableUrl("   "))
    }

    @Test
    fun `a space is percent-encoded, not written as a plus`() {
        // URLEncoder writes a space as "+", which a search engine reads as a space but a URL
        // parser does not: RFC 3986 makes "+" an ordinary sub-delimiter. "%20" is a space to
        // every parser, so the plus is rewritten away.
        assertEquals(
            "https://duckduckgo.com/?q=blender%20tutorial",
            SnifferScript.toLoadableUrl("blender tutorial"),
        )
    }

    @Test
    fun `a plus the user typed survives`() {
        // The regression this guards. Rewriting "+" before encoding turns the user's literal
        // plus into a space and loses it. Encoding first makes it %2B, which the rewrite
        // cannot then touch.
        assertEquals(
            "https://duckduckgo.com/?q=c%2B%2B%20reference",
            SnifferScript.toLoadableUrl("c++ reference"),
        )
    }

    @Test
    fun `classification trusts the extension before the mime type`() {
        assertEquals(
            DetectedMedia.Kind.PLAYLIST,
            SnifferScript.classify("https://a.example/x.m3u8", "application/octet-stream"),
        )
        assertEquals(
            DetectedMedia.Kind.AUDIO,
            SnifferScript.classify("https://a.example/x.m4a", "video/mp4"),
        )
        assertEquals(
            DetectedMedia.Kind.AUDIO,
            SnifferScript.classify("https://a.example/stream?id=1", "audio/mpeg"),
        )
        assertEquals(DetectedMedia.Kind.VIDEO, SnifferScript.classify("https://a.example/stream"))
    }

    @Test
    fun `the sniffer reports a stream once and skips fragments`() {
        val found = mutableListOf<DetectedMedia>()
        val sniffer = MediaSniffer { found.add(it) }

        assertTrue(sniffer.offer("https://a.example/v.mp4", "video/mp4", "Clip"))
        // The same URL from the element and then the network is one stream, not two.
        assertFalse(sniffer.offer("https://a.example/v.mp4", "video/mp4", "Clip"))
        assertFalse(sniffer.offerFromRequest("https://a.example/v.mp4", "video/mp4"))
        assertEquals(1, found.size)
        assertEquals("Clip", found.single().title)

        assertFalse(sniffer.offerFromRequest("https://a.example/seg/1.ts", "video/mp2t"))
        assertFalse(sniffer.offerFromRequest("https://a.example/watch", "text/html"))
        assertFalse(sniffer.offer("blob:https://a.example/abcd", "video/mp4"))
        assertEquals(1, found.size)
    }

    @Test
    fun `a label always says something`() {
        val video = DetectedMedia.Kind.VIDEO
        assertEquals("Title", DetectedMedia("https://a/b.mp4", video, "Title").label)
        assertEquals("mp4", DetectedMedia("https://a/b", video, "", "video/mp4").label)
        assertEquals("b.mp4", DetectedMedia("https://a/b.mp4", video).label)
    }
}
