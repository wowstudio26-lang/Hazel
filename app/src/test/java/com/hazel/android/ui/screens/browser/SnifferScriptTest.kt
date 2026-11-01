package com.hazel.android.ui.screens.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure part of the browser worth pinning: what counts as media, what kind it is, and
 * what a typed address becomes. These decide what the chip offers, and each is cheap to get
 * wrong in a way that only shows up on a real page.
 *
 * Runs on the JVM with no Android classes involved, which is why [SnifferScript] is not
 * allowed to touch `android.net.Uri`.
 */
class SnifferScriptTest {

    @Test
    fun `recognises media extensions, with and without a query string`() {
        assertTrue(SnifferScript.isDownloadableLink("https://a.example/video.mp4"))
        assertTrue(SnifferScript.isDownloadableLink("https://a.example/v.mp4?token=abc"))
        assertTrue(SnifferScript.isDownloadableLink("https://a.example/hls/index.m3u8"))
        assertTrue(SnifferScript.isDownloadableLink("https://a.example/audio.M4A"))
        assertTrue(SnifferScript.isDownloadableLink("https://a.example/dash/manifest.mpd"))
    }

    @Test
    fun `turns down pages, fragments and blob urls`() {
        assertFalse(SnifferScript.isDownloadableLink("https://a.example/watch"))
        assertFalse(SnifferScript.isDownloadableLink("https://a.example/a.html#x.mp4"))
        assertFalse(SnifferScript.isDownloadableLink("blob:https://a.example/1234"))
        assertFalse(SnifferScript.isDownloadableLink("data:video/mp4;base64,AAAA"))
        assertFalse(SnifferScript.isDownloadableLink(""))
        assertFalse(SnifferScript.isDownloadableLink(null))
    }

    @Test
    fun `turns down hls and dash fragments`() {
        // A segment is a few seconds of video, fetched by the hundred. Offering these would
        // bury the manifest, which is the thing worth downloading.
        assertFalse(SnifferScript.isDownloadableLink("https://a.example/seg/00001.ts"))
        assertFalse(SnifferScript.isDownloadableLink("https://a.example/dash/init.m4s"))
        assertFalse(SnifferScript.isDownloadableLink("https://a.example/seg/00001.ts?range=0-9"))
    }

    @Test
    fun `a bare word becomes a search and a host is loaded directly`() {
        val searched = SnifferScript.toLoadableUrl("blender tutorial")
        assertTrue(searched.startsWith("https://duckduckgo.com/?q="))
        // URLEncoder writes a space as "+", which a search engine reads as a space.
        assertTrue(searched.endsWith("blender+tutorial"))

        assertEquals("https://example.com", SnifferScript.toLoadableUrl("example.com"))
        assertEquals("https://example.com/x", SnifferScript.toLoadableUrl("https://example.com/x"))
        assertEquals("", SnifferScript.toLoadableUrl("   "))
    }

    @Test
    fun `classification trusts the extension before the mime type`() {
        // A playlist served as a generic type is still a playlist.
        assertEquals(
            DetectedMedia.Kind.PLAYLIST,
            SnifferScript.classify("https://a.example/x.m3u8", "application/octet-stream"),
        )
        assertEquals(
            DetectedMedia.Kind.AUDIO,
            SnifferScript.classify("https://a.example/x.m4a", "video/mp4"),
        )
        // With no extension to read, the mime type is all there is.
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
        // The same URL from the element and then from the network is one stream, not two.
        assertFalse(sniffer.offer("https://a.example/v.mp4", "video/mp4", "Clip"))
        assertFalse(sniffer.offerFromRequest("https://a.example/v.mp4", "video/mp4"))

        assertEquals(1, found.size)
        assertEquals("Clip", found.single().title)

        // Segments never reach the list, however they arrive.
        assertFalse(sniffer.offerFromRequest("https://a.example/seg/1.ts", "video/mp2t"))
        // Nor does a page URL or a blob the app cannot fetch.
        assertFalse(sniffer.offerFromRequest("https://a.example/watch", "text/html"))
        assertFalse(sniffer.offer("blob:https://a.example/abcd", "video/mp4"))
        assertEquals(1, found.size)
    }

    @Test
    fun `a label always says something`() {
        // Falls back through title, mime, then the URL, so no chip is ever blank.
        assertEquals("Title", DetectedMedia("https://a/b.mp4", DetectedMedia.Kind.VIDEO, "Title").label)
        assertEquals("mp4", DetectedMedia("https://a/b", DetectedMedia.Kind.VIDEO, "", "video/mp4").label)
        assertEquals("b.mp4", DetectedMedia("https://a/b.mp4", DetectedMedia.Kind.VIDEO).label)
    }
}
