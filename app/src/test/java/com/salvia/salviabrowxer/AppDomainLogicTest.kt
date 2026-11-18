package com.salvia.salviabrowxer

import com.salvia.salviabrowxer.core.AddressBarResolver
import com.salvia.salviabrowxer.core.DownloadFileResolver
import com.salvia.salviabrowxer.core.FloatingSnap
import com.salvia.salviabrowxer.core.LockoutPolicy
import com.salvia.salviabrowxer.core.MainContentSelector
import com.salvia.salviabrowxer.core.MediaOfferability
import com.salvia.salviabrowxer.core.MediaProbeAndExtract
import com.salvia.salviabrowxer.core.PastedLinkClassifier
import com.salvia.salviabrowxer.data.CandidateRole
import com.salvia.salviabrowxer.data.ClipboardLinkKind
import com.salvia.salviabrowxer.data.MediaCandidate
import com.salvia.salviabrowxer.data.SearchEngineKey
import com.salvia.salviabrowxer.data.UnsupportedMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDomainLogicTest {

    @Test
    fun addressBarResolverRoutesHostsUrlsAndSearchQueries() {
        assertEquals(
            "https://example.com/watch",
            AddressBarResolver.resolveAddressBarInput("https://example.com/watch")
        )
        assertEquals(
            "https://example.com",
            AddressBarResolver.resolveAddressBarInput("example.com")
        )
        val searchUrl = AddressBarResolver.resolveAddressBarInput("kotlin compose", SearchEngineKey.DuckDuckGo)
        assertNotNull(searchUrl)
        assertTrue(searchUrl!!.startsWith("https://duckduckgo.com/?q="))
        val blockedJs = AddressBarResolver.resolveAddressBarInput("javascript:alert(1)", SearchEngineKey.Google)
        assertTrue(blockedJs!!.startsWith("https://www.google.com/search?q="))
    }

    @Test
    fun pastedLinkClassifierDistinguishesMediaFromPages() {
        assertEquals(
            ClipboardLinkKind.MEDIA_FILE,
            PastedLinkClassifier.classifyPastedLink("https://cdn.example.com/movie.mp4?token=abc")
        )
        assertEquals(
            ClipboardLinkKind.MEDIA_FILE,
            PastedLinkClassifier.classifyPastedLink("https://stream.example.com/master.m3u8")
        )
        assertEquals(
            ClipboardLinkKind.WEB_PAGE,
            PastedLinkClassifier.classifyPastedLink("https://www.youtube.com/watch?v=123")
        )
        assertNull(PastedLinkClassifier.classifyPastedLink("not-a-url"))
    }

    @Test
    fun mainContentSelectorPrefersMainVideoOverAdsAndShortPreviews() {
        val page = "https://videos.example.com/watch/1"
        val ad = MediaCandidate(
            id = "ad",
            pageUrl = page,
            mediaUrl = "https://doubleclick.net/preroll.mp4",
            mimeType = "video/mp4",
            extension = "mp4",
            duration = 5_000L,
            muted = true,
            hasControls = false
        )
        val main = MediaCandidate(
            id = "main",
            pageUrl = page,
            mediaUrl = "https://cdn.example.com/feature_1080p.mp4",
            mimeType = "video/mp4",
            extension = "mp4",
            width = 1920,
            height = 1080,
            duration = 120_000L,
            hasControls = true,
            visible = true,
            confidence = 0.95
        )
        val (selected, ranked) = MainContentSelector.rankAndClassifyCandidates(
            listOf(ad, main),
            visibleUrl = main.mediaUrl,
            pageUrl = page
        )
        assertEquals(main.mediaUrl, selected?.mediaUrl)
        assertEquals(CandidateRole.MAIN, ranked.first().role)
        assertEquals(CandidateRole.AD, ranked.last().role)
    }

    @Test
    fun floatingSnapClampsAndSnapsToNearestHorizontalEdge() {
        val (leftX, clampedY) = FloatingSnap.snapFloatingPosition(
            x = 40f,
            y = -20f,
            containerWidth = 400f,
            containerHeight = 800f,
            buttonSize = 54f,
            pad = 14f
        )
        assertEquals(14f, leftX, 0.01f)
        assertEquals(14f, clampedY, 0.01f)

        val (rightX, _) = FloatingSnap.snapFloatingPosition(
            x = 260f,
            y = 300f,
            containerWidth = 400f,
            containerHeight = 800f,
            buttonSize = 54f,
            pad = 14f
        )
        assertEquals(400f - 54f - 14f, rightX, 0.01f)
    }

    @Test
    fun lockoutPolicyAppliesExponentialBackoffAfterThreeFailures() {
        val policy = LockoutPolicy(maxAttempts = 3, baseDelayMillis = 2000L, maxDelayMillis = 60_000L)
        assertEquals(0L, policy.onFailure(1000L))
        assertEquals(0L, policy.onFailure(2000L))
        assertEquals(2000L, policy.onFailure(3000L))
        assertTrue(policy.isLockedOut(4000L))
        assertFalse(policy.isLockedOut(6000L))
        policy.onSuccess()
        assertFalse(policy.isLockedOut(6000L))
    }

    @Test
    fun mediaOfferabilityAndPlaylistParserDetectUnsupportedAndVariants() {
        assertEquals(
            UnsupportedMedia.LIVE,
            MediaOfferability.unsupportedReasonFor("https://cdn.example.com/live.m3u8", isLive = true)
        )
        assertEquals(
            UnsupportedMedia.BLOB_STREAM,
            MediaOfferability.unsupportedReasonFor("blob:https://example.com/123", isMediaSource = true)
        )

        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1280x720
            720p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3500000,RESOLUTION=1920x1080
            1080p/index.m3u8
        """.trimIndent()
        val parsed = MediaProbeAndExtract.parseHlsPlaylist(master, "https://cdn.example.com/v/master.m3u8", "Demo")
        assertNull(parsed.unsupported)
        assertEquals(2, parsed.formats.size)
        assertEquals(1080, parsed.formats.first().height)
        assertTrue(MediaProbeAndExtract.isNewerVersion("v1.2.0", "1.0.0"))
        assertFalse(MediaProbeAndExtract.isNewerVersion("v1.0.0", "1.0.0"))
    }

    @Test
    fun downloadFileResolverFindsExtensionedFilesAndCollectsArtifacts() {
        val tempRoot = java.nio.file.Files.createTempDirectory("salvia_dl_test").toFile()
        try {
            val id = "dl-abc:123"
            val safe = DownloadFileResolver.sanitizeStorageId(id)
            assertEquals("dl-abc_123", safe)

            val videoFile = java.io.File(tempRoot, "$safe.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val segDir = java.io.File(tempRoot, "seg_$safe").apply { mkdirs() }
            java.io.File(segDir, "0.ts").writeBytes(byteArrayOf(9))
            val partsDir = java.io.File(tempRoot, "parts_$safe").apply { mkdirs() }

            val foundWithoutRecorded = DownloadFileResolver.findInternalDownloadFile(tempRoot, id, null)
            assertEquals(videoFile.absolutePath, foundWithoutRecorded?.absolutePath)

            val foundWithRecorded = DownloadFileResolver.findInternalDownloadFile(tempRoot, id, videoFile.absolutePath)
            assertEquals(videoFile.absolutePath, foundWithRecorded?.absolutePath)

            val artifacts = DownloadFileResolver.collectArtifactsForDeletion(tempRoot, id, videoFile.absolutePath)
            assertTrue(artifacts.contains(videoFile))
            assertTrue(artifacts.contains(segDir))
            assertTrue(artifacts.contains(partsDir))
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
