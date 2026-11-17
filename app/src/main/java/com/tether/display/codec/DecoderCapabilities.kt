package com.tether.display.codec

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log

/**
 * Runtime query of device H.264 (video/avc) decoder capabilities via MediaCodecList.
 * Never hard-codes device specifications; prefers hardware decoders and logs selection.
 */
data class DecoderCapabilities(
    val decoderName: String,
    val isHardware: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFps: Int
) {
    companion object {
        private const val TAG = "TetherCodec"
        private const val MIME_AVC = "video/avc"

        fun queryBestAvcDecoder(): DecoderCapabilities? {
            val codecInfos = try {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            } catch (t: Throwable) {
                Log.w(TAG, "MediaCodecList query failed", t)
                return null
            }

            var best: DecoderCapabilities? = null
            var bestScore = Long.MIN_VALUE

            for (info in codecInfos) {
                if (info.isEncoder) continue
                if (!supportsMime(info, MIME_AVC)) continue

                val caps = try {
                    info.getCapabilitiesForType(MIME_AVC)
                } catch (_: Throwable) {
                    continue
                }
                val videoCaps = caps.videoCapabilities ?: continue

                val maxW = videoCaps.supportedWidths.upper
                val maxH = videoCaps.supportedHeights.upper
                if (maxW <= 0 || maxH <= 0) continue

                val maxFps = querySafeMaxFps(videoCaps, maxW, maxH)
                val isHw = isHardwareDecoder(info)

                // Strongly prefer hardware decoders, then higher resolution & fps
                val hwBonus = if (isHw) 1_000_000_000_000L else 0L
                val score = hwBonus + (maxW.toLong() * maxH.toLong() * maxFps.toLong())

                if (score > bestScore) {
                    bestScore = score
                    best = DecoderCapabilities(
                        decoderName = info.name,
                        isHardware = isHw,
                        maxWidth = maxW,
                        maxHeight = maxH,
                        maxFps = maxFps
                    )
                }
            }

            if (best != null) {
                Log.i(
                    TAG,
                    "Selected AVC decoder: ${best.decoderName} (hw=${best.isHardware}, " +
                        "max=${best.maxWidth}x${best.maxHeight}@${best.maxFps}fps)"
                )
            } else {
                Log.w(TAG, "No H.264 decoder found in MediaCodecList")
            }
            return best
        }

        private fun supportsMime(info: MediaCodecInfo, mime: String): Boolean {
            return info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }

        private fun isHardwareDecoder(info: MediaCodecInfo): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return info.isHardwareAccelerated
            }
            val lower = info.name.lowercase()
            val isSoftware = lower.startsWith("omx.google.") ||
                lower.startsWith("c2.android.") ||
                lower.contains(".sw.") ||
                lower.contains("ffmpeg")
            return !isSoftware
        }

        private fun querySafeMaxFps(
            videoCaps: MediaCodecInfo.VideoCapabilities,
            maxW: Int,
            maxH: Int
        ): Int {
            // Probe common landscape resolutions downward so maxFps reflects realistic playback rate
            val probeCandidates = arrayOf(
                maxW to maxH,
                minOf(maxW, 1920) to minOf(maxH, 1080),
                minOf(maxW, 1280) to minOf(maxH, 720),
                minOf(maxW, 800) to minOf(maxH, 480)
            )
            for ((w, h) in probeCandidates) {
                try {
                    if (videoCaps.isSizeSupported(w, h)) {
                        val rate = videoCaps.getSupportedFrameRatesFor(w, h).upper.toInt()
                        if (rate > 0) return rate
                    }
                } catch (_: Throwable) {
                    // Continue to next probe candidate
                }
            }
            return try {
                videoCaps.supportedFrameRates.upper.toInt().coerceIn(1, 240)
            } catch (_: Throwable) {
                30
            }
        }
    }
}
