package com.salvia.salviabrowxer

import com.salvia.salviabrowxer.browser.MediaSniffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSnifferTest {

    @Test
    fun manifestsAndFilesAreRecognised() {
        assertEquals(
            "application/vnd.apple.mpegurl",
            MediaSniffer.classify("https://cdn.example.com/v/master.m3u8?token=1")!!.mimeType
        )
        assertEquals("application/dash+xml", MediaSniffer.classify("https://cdn.example.com/a/manifest.mpd")!!.mimeType)
        assertEquals("video/mp4", MediaSniffer.classify("https://cdn.example.com/video.mp4")!!.mimeType)
        assertEquals("audio/mpeg", MediaSniffer.classify("https://cdn.example.com/song.mp3")!!.mimeType)
    }

    @Test
    fun segmentsAndFragmentsAreIgnored() {
        assertNull(MediaSniffer.classify("https://cdn.example.com/seg-12.ts"))
        assertNull(MediaSniffer.classify("https://cdn.example.com/chunk1.m4s"))
        assertNull(MediaSniffer.classify("https://cdn.example.com/video-seg3.mp4"))
        assertNull(MediaSniffer.classify("https://cdn.example.com/init.mp4"))
        assertNull(MediaSniffer.classify("https://cdn.example.com/a.mp4?bytestart=0&byteend=99"))
    }

    @Test
    fun nonMediaAndUnsafeUrlsAreIgnored() {
        assertNull(MediaSniffer.classify("https://example.com/page.html"))
        assertNull(MediaSniffer.classify("https://example.com/logo.png"))
        assertNull(MediaSniffer.classify("ftp://example.com/video.mp4"))
        assertNull(MediaSniffer.classify("file:///sdcard/video.mp4"))
        assertNull(MediaSniffer.classify(""))
        assertNull(MediaSniffer.classify(null))
        assertNull(MediaSniffer.classify("https://pagead2.googlesyndication.com/x.mp4"))
        assertNotNull(MediaSniffer.classify("https://initial-videos.example.com/initial.mp4"))
    }
}
