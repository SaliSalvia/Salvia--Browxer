package com.salvia.salviabrowxer.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object NativeMediaMuxer {
    private const val BUFFER_SIZE = 512 * 1024

    // Codecs MediaMuxer's MP4 output cannot hold reliably; those get a WebM container.
    private val webmOnlyVideo = setOf("video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "video/av01")
    private val webmOnlyAudio = setOf("audio/opus", "audio/vorbis")

    /**
     * Muxes [videoFile] (+ [audioFile] when present) into an MP4/WebM next to [target].
     *
     * The container is chosen from the actual track codecs: H.264/H.265 + AAC → MP4,
     * VP8/VP9/AV1 or Opus/Vorbis → WebM. When muxing fails entirely, null is returned and the
     * caller falls back to a raw copy (with an honest extension).
     */
    fun smartMux(videoFile: File, audioFile: File?, target: File): File? {
        val mimes = collectTrackMimes(videoFile) + (audioFile?.let { collectTrackMimes(it) } ?: emptyList())
        val needsWebM = mimes.any { it in webmOnlyVideo || it in webmOnlyAudio }
        if (needsWebM) {
            val webmTarget = File(target.parentFile, target.nameWithoutExtension + ".webm")
            val ok = muxStreams(videoFile, audioFile, webmTarget, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
            if (ok) return webmTarget
            // WebM muxing failed (device does not support it): try MP4 anyway.
            val mp4Target = File(target.parentFile, target.nameWithoutExtension + ".mp4")
            if (muxStreams(videoFile, audioFile, mp4Target, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)) {
                return mp4Target
            }
            return null
        }
        val ok = muxStreams(videoFile, audioFile, target, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return if (ok) target else null
    }

    fun remuxToMp4(inputFile: File, outputMp4: File): Boolean {
        return muxStreams(inputFile, null, outputMp4, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun muxVideoAndAudio(videoFile: File, audioFile: File, outputMp4: File): Boolean {
        return muxStreams(videoFile, audioFile, outputMp4, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun collectTrackMimes(file: File): List<String> {
        val extractor = MediaExtractor()
        return try {
            if (!file.exists() || file.length() == 0L) return emptyList()
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).mapNotNull { idx ->
                extractor.getTrackFormat(idx).getString(MediaFormat.KEY_MIME)
            }
        } catch (ignored: Exception) {
            emptyList()
        } finally {
            try {
                extractor.release()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun muxStreams(
        videoFile: File,
        audioFile: File?,
        outputFile: File,
        format: Int
    ): Boolean {
        if (!videoFile.exists() || videoFile.length() == 0L) return false
        val hasAudio = audioFile != null && audioFile.exists() && audioFile.length() > 0L

        val videoExtractor = MediaExtractor()
        val audioExtractor = if (hasAudio) MediaExtractor() else null
        var muxer: MediaMuxer? = null
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor?.setDataSource(audioFile!!.absolutePath)

            muxer = MediaMuxer(outputFile.absolutePath, format)

            val videoMap = HashMap<Int, Int>()
            for (i in 0 until videoExtractor.trackCount) {
                val trackFormat = videoExtractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                val wanted = if (audioExtractor != null) mime.startsWith("video/") else
                    mime.startsWith("video/") || mime.startsWith("audio/")
                if (wanted) {
                    videoExtractor.selectTrack(i)
                    videoMap[i] = muxer.addTrack(trackFormat)
                    if (audioExtractor != null) break else if (mime.startsWith("video/")) break
                }
            }

            val audioMap = HashMap<Int, Int>()
            if (audioExtractor != null) {
                for (i in 0 until audioExtractor.trackCount) {
                    val trackFormat = audioExtractor.getTrackFormat(i)
                    val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        audioExtractor.selectTrack(i)
                        audioMap[i] = muxer.addTrack(trackFormat)
                        break
                    }
                }
            }

            if (videoMap.isEmpty() && audioMap.isEmpty()) return false

            muxer.start()
            if (videoMap.isNotEmpty()) {
                copySelectedTracks(videoExtractor, muxer, videoMap)
            }
            if (audioMap.isNotEmpty()) {
                copySelectedTracks(audioExtractor!!, muxer, audioMap)
            }
            muxer.stop()
            return outputFile.exists() && outputFile.length() > 0L
        } catch (ignored: Exception) {
            try {
                muxer?.release()
            } catch (ignored2: Exception) {
            }
            outputFile.delete()
            return false
        } finally {
            try {
                muxer?.release()
            } catch (ignored: Exception) {
            }
            try {
                videoExtractor.release()
            } catch (ignored: Exception) {
            }
            try {
                audioExtractor?.release()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun copySelectedTracks(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        trackIndexMap: Map<Int, Int>
    ) {
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)
        val info = MediaCodec.BufferInfo()
        val lastPtsByTrack = HashMap<Int, Long>()
        // After a discontinuity (HLS) or period boundary (DASH) timestamps can restart near zero.
        // Advancing every regressed sample by just 1 us would compress the whole remainder of the
        // stream into back-to-back samples (fast-forward garbage). Instead we shift the rest of
        // the track forward so relative timing is preserved.
        val shiftByTrack = HashMap<Int, Long>()

        while (true) {
            val sampleTrack = extractor.sampleTrackIndex
            if (sampleTrack < 0) break
            val destTrack = trackIndexMap[sampleTrack]
            if (destTrack == null) {
                extractor.advance()
                continue
            }

            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            var pts = extractor.sampleTime + (shiftByTrack[destTrack] ?: 0L)
            val prevPts = lastPtsByTrack[destTrack]
            if (prevPts != null && pts < prevPts) {
                val shift = (shiftByTrack[destTrack] ?: 0L) + (prevPts - pts) + 1L
                shiftByTrack[destTrack] = shift
                pts = extractor.sampleTime + shift
            }
            lastPtsByTrack[destTrack] = pts

            info.offset = 0
            info.size = sampleSize
            info.presentationTimeUs = maxOf(0L, pts)
            val sFlags = extractor.sampleFlags
            info.flags = if ((sFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
            muxer.writeSampleData(destTrack, buffer, info)
            extractor.advance()
        }
    }
}
