package com.hazel.android.ui.screens.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two pure parts of the browser worth pinning: what counts as media, and how a stream
 * is labelled. Both decide what the user sees on the chip, and both are cheap to get wrong
 * in ways that only show up on a real page.
 */
class SnifferScriptTest {

    @Test
    fun `recognises media extensions, with and without a query string`() {
        assertTrue(SnifferScript.isDownloadableLink("https://a.example/video.mp4"))
        assertTrue(SnifferScript.isDownloadableLink("https://a.example/v.mp4?token=abc"))
        assertTrue(SnifferScript.isDownloadableLink("https://a.example/hls/index.m3u8"))
        assertTrue(SnifferScript.isDownloadableLink("https://a.example/audio.M4A"))
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
    fun `a bare word becomes a search and a host is loaded directly`() {
        val searched = SnifferScript.toLoadableUrl("blender tutorial")
        assertTrue(searched.startsWith("https://duckduckgo.com/?q="))
        assertTrue(searched.contains("blender%20tutorial"))

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
}
