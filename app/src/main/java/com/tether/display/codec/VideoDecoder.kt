package com.tether.display.codec

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * Hardware-accelerated H.264 MediaCodec decoder in async callback mode.
 * Renders directly to the SurfaceView's Surface without passing frames through the CPU.
 */
class VideoDecoder(
    private val frameQueue: FrameQueueController,
    private val onFirstFrameRendered: () -> Unit,
    private val onDecoderError: (String) -> Unit
) {
    companion object {
        private const val TAG = "TetherVideoDecoder"
        private const val MIME_AVC = "video/avc"
        private const val INPUT_INDEX_RING_CAP = 32
    }

    private val lock = Any()
    private var codec: MediaCodec? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var running = false
    private var firstFrameNotified = false

    // Primitive ring buffer for available input buffer indices (zero allocation)
    private val availableInputIndices = IntArray(INPUT_INDEX_RING_CAP)
    private var inputHead = 0
    private var inputTail = 0
    private var inputCount = 0

    fun start(
        surface: Surface,
        width: Int,
        height: Int,
        targetFps: Int,
        preferredDecoderName: String?
    ): Boolean {
        stop()

        synchronized(lock) {
            firstFrameNotified = false
            clearInputIndicesLocked()

            val thread = HandlerThread("TetherDecoderThread").apply { start() }
            val threadHandler = Handler(thread.looper)
            handlerThread = thread
            handler = threadHandler

            val createdCodec = try {
                if (!preferredDecoderName.isNullOrEmpty()) {
                    MediaCodec.createByCodecName(preferredDecoderName)
                } else {
                    MediaCodec.createDecoderByType(MIME_AVC)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Preferred decoder $preferredDecoderName failed, falling back", t)
                try {
                    MediaCodec.createDecoderByType(MIME_AVC)
                } catch (fallbackErr: Throwable) {
                    Log.e(TAG, "Failed to create H.264 decoder", fallbackErr)
                    thread.quitSafely()
                    handlerThread = null
                    handler = null
                    return false
                }
            }

            createdCodec.setCallback(codecCallback, threadHandler)

            val configured = configureWithLowLatencyFallback(createdCodec, surface, width, height, targetFps)
            if (!configured) {
                releaseCodecQuietly(createdCodec)
                thread.quitSafely()
                handlerThread = null
                handler = null
                return false
            }

            try {
                createdCodec.start()
                codec = createdCodec
                running = true
                Log.i(TAG, "Started MediaCodec (${width}x$height @ ${targetFps}fps)")
                return true
            } catch (t: Throwable) {
                Log.e(TAG, "MediaCodec.start() failed", t)
                releaseCodecQuietly(createdCodec)
                thread.quitSafely()
                handlerThread = null
                handler = null
                return false
            }
        }
    }

    /** Called by network reader after a FrameSlot has been enqueued into [frameQueue]. */
    fun notifyFrameAvailable() {
        val h: Handler?
        synchronized(lock) {
            if (!running) return
            h = handler
        }
        h?.post(drainTask)
    }

    fun stop() {
        val oldCodec: MediaCodec?
        val oldThread: HandlerThread?
        synchronized(lock) {
            running = false
            clearInputIndicesLocked()
            oldCodec = codec
            oldThread = handlerThread
            codec = null
            handlerThread = null
            handler = null
        }
        releaseCodecQuietly(oldCodec)
        oldThread?.quitSafely()
    }

    private fun configureWithLowLatencyFallback(
        mediaCodec: MediaCodec,
        surface: Surface,
        width: Int,
        height: Int,
        targetFps: Int
    ): Boolean {
        // Attempt 1: Aggressive low-latency settings
        val aggressive = buildMediaFormat(width, height, targetFps, includeVendorAndHighRate = true)
        try {
            mediaCodec.configure(aggressive, surface, null, 0)
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "Aggressive low-latency MediaFormat rejected, retrying baseline", t)
            try {
                mediaCodec.reset()
                mediaCodec.setCallback(codecCallback, handler)
            } catch (_: Throwable) {
                // Continue to baseline attempt
            }
        }

        // Attempt 2: Safe baseline (API 23+ KEY_PRIORITY=0 + optional API 30+ KEY_LOW_LATENCY)
        val baseline = buildMediaFormat(width, height, targetFps, includeVendorAndHighRate = false)
        return try {
            mediaCodec.configure(baseline, surface, null, 0)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Baseline MediaCodec.configure() failed", t)
            false
        }
    }

    private fun buildMediaFormat(
        width: Int,
        height: Int,
        targetFps: Int,
        includeVendorAndHighRate: Boolean
    ): MediaFormat {
        val format = MediaFormat.createVideoFormat(MIME_AVC, width, height)
        // API 23+ real-time priority
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }

        if (includeVendorAndHighRate) {
            // Operating rate API 23+
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, maxOf(targetFps, 120))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                trySetVendorInt(format, "vendor.qti-ext-dec-low-latency.enable", 1)
                trySetVendorInt(format, "vendor.rtc-ext-dec-low-latency.enable", 1)
                trySetVendorInt(format, "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1)
                trySetVendorInt(format, "low-latency", 1)
            }
        }
        return format
    }

    private fun trySetVendorInt(format: MediaFormat, key: String, value: Int) {
        try {
            format.setInteger(key, value)
        } catch (_: Throwable) {
            // Guarded per PRD: unknown key must never crash
        }
    }

    private val drainTask = Runnable {
        drainInputQueue()
    }

    private fun drainInputQueue() {
        while (true) {
            val activeCodec: MediaCodec
            val inputIndex: Int
            val slot: FrameQueueController.FrameSlot

            synchronized(lock) {
                if (!running || inputCount == 0) return
                val c = codec ?: return
                val nextSlot = frameQueue.pollNextPending() ?: return
                activeCodec = c
                inputIndex = popInputIndexLocked()
                slot = nextSlot
            }

            try {
                val inputBuf = activeCodec.getInputBuffer(inputIndex)
                if (inputBuf == null) {
                    frameQueue.releaseSlot(slot)
                    continue
                }
                inputBuf.clear()
                if (slot.size > inputBuf.remaining()) {
                    // Unusually large frame for codec input buffer: drop & wait for next IDR
                    Log.w(TAG, "Frame size ${slot.size} exceeds MediaCodec input buffer ${inputBuf.remaining()}")
                    activeCodec.queueInputBuffer(inputIndex, 0, 0, 0L, 0)
                    frameQueue.releaseSlot(slot)
                    continue
                }
                inputBuf.put(slot.data, 0, slot.size)

                var flags = 0
                if (slot.isCodecConfig) {
                    flags = flags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                }
                if (slot.isKeyFrame && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                }

                if (!slot.isCodecConfig) {
                    frameQueue.recordDecodeStart(slot.ptsUs, slot.receiveNs, System.nanoTime())
                }
                activeCodec.queueInputBuffer(inputIndex, 0, slot.size, slot.ptsUs, flags)
                frameQueue.releaseSlot(slot)
            } catch (t: Throwable) {
                frameQueue.releaseSlot(slot)
                handleFatalError("Input queue failed: ${t.message}")
                return
            }
        }
    }

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
            synchronized(lock) {
                if (!running || mc !== codec) return
                pushInputIndexLocked(index)
            }
            drainInputQueue()
        }

        override fun onOutputBufferAvailable(
            mc: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            val active: Boolean
            var notifyFirst = false
            synchronized(lock) {
                active = running && mc === codec
                if (active && !firstFrameNotified && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                    firstFrameNotified = true
                    notifyFirst = true
                }
            }
            if (!active) return

            try {
                val renderTs = System.nanoTime()
                mc.releaseOutputBuffer(index, renderTs)
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    frameQueue.recordDecodeAndRenderDone(info.presentationTimeUs, renderTs)
                }
                if (notifyFirst) {
                    onFirstFrameRendered()
                }
            } catch (t: Throwable) {
                handleFatalError("Output release failed: ${t.message}")
            }
        }

        override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
            synchronized(lock) {
                if (!running || mc !== codec) return
            }
            handleFatalError("MediaCodec error: ${e.diagnosticInfo}")
        }

        override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "Output format changed: $format")
        }
    }

    private fun handleFatalError(reason: String) {
        Log.e(TAG, reason)
        stop()
        onDecoderError(reason)
    }

    private fun pushInputIndexLocked(index: Int) {
        if (inputCount >= INPUT_INDEX_RING_CAP) return
        availableInputIndices[inputTail] = index
        inputTail = (inputTail + 1) % INPUT_INDEX_RING_CAP
        inputCount++
    }

    private fun popInputIndexLocked(): Int {
        val idx = availableInputIndices[inputHead]
        inputHead = (inputHead + 1) % INPUT_INDEX_RING_CAP
        inputCount--
        return idx
    }

    private fun clearInputIndicesLocked() {
        inputHead = 0
        inputTail = 0
        inputCount = 0
    }

    private fun releaseCodecQuietly(c: MediaCodec?) {
        if (c == null) return
        try {
            c.stop()
        } catch (_: Throwable) {
        }
        try {
            c.release()
        } catch (_: Throwable) {
        }
    }
}
