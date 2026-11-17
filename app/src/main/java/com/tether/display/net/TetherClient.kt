package com.tether.display.net

import android.util.Log
import com.tether.display.codec.DecoderCapabilities
import com.tether.display.codec.FrameQueueController
import com.tether.display.input.TouchCoalescer
import com.tether.display.proto.TetherProtocol
import com.tether.display.proto.TetherProtocol.MsgType
import com.tether.display.proto.TetherProtocol.PacketHeader
import com.tether.display.proto.TetherProtocol.PacketReader
import com.tether.display.proto.TetherProtocol.PacketWriter
import com.tether.display.proto.TetherProtocol.VideoFrameMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages the TCP connection to 127.0.0.1:PORT over ADB reverse,
 * handling reconnects (500ms backoff), HELLO handshake, CONFIG, zero-alloc VIDEO_FRAME ingestion,
 * coalesced TOUCH transmission, PING/PONG keepalive, REQUEST_KEYFRAME, and DISPLAY_CHANGE.
 */
class TetherClient(
    private val port: Int = TetherProtocol.DEFAULT_PORT,
    private val frameQueue: FrameQueueController,
    private val touchCoalescer: TouchCoalescer,
    private val capabilitiesProvider: () -> DecoderCapabilities?,
    private val displayMetricsProvider: () -> DisplayMetricsSnapshot,
    private val listener: Listener
) {
    companion object {
        private const val TAG = "TetherClient"
        private const val HOST = "127.0.0.1"
        private const val CONNECT_TIMEOUT_MS = 2000
        private const val READ_TIMEOUT_MS = 6000
        private const val RECONNECT_DELAY_MS = 500L
        private const val PING_INTERVAL_MS = 2000L
        private const val IO_BUFFER_BYTES = 64 * 1024
    }

    data class DisplayMetricsSnapshot(
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val rotationDegrees: Int
    )

    enum class ConnectionState {
        CONNECTING,
        AWAITING_CONFIG,
        STREAMING,
        RECONNECTING
    }

    interface Listener {
        fun onStateChanged(state: ConnectionState, detail: String? = null)
        fun onConfigReceived(config: TetherProtocol.Message.Config)
        fun onFrameQueued()
        fun onRttUpdated(rttMs: Float)
        fun onDisconnected(reason: String)
    }

    private var scope: CoroutineScope? = null
    private var loopJob: Job? = null
    private val running = AtomicBoolean(false)

    // Signals to wake the single writer coroutine without blocking callers
    private val writerWakeup = Channel<Unit>(Channel.CONFLATED)
    private val pendingKeyframeRequest = AtomicBoolean(false)
    private val pendingPing = AtomicBoolean(false)
    private val pendingPongTimestamp = AtomicReference<Long?>(null)
    private val pendingDisplayChange = AtomicReference<DisplayMetricsSnapshot?>(null)

    @Volatile
    private var activeSocket: Socket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        loopJob = newScope.launch {
            runConnectionLoop()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        closeActiveSocketQuietly()
        loopJob?.cancel()
        loopJob = null
        scope?.cancel()
        scope = null
    }

    fun notifyTouchEnqueued() {
        writerWakeup.trySend(Unit)
    }

    fun requestKeyframe() {
        pendingKeyframeRequest.set(true)
        writerWakeup.trySend(Unit)
    }

    fun sendDisplayChange(metrics: DisplayMetricsSnapshot) {
        pendingDisplayChange.set(metrics)
        writerWakeup.trySend(Unit)
    }

    /** Called by decoder error path to force immediate socket teardown & 500ms reconnect. */
    fun triggerReconnect(reason: String) {
        Log.w(TAG, "Triggering reconnect: $reason")
        closeActiveSocketQuietly()
    }

    private suspend fun runConnectionLoop() {
        var isFirstAttempt = true
        while (running.get()) {
            listener.onStateChanged(
                if (isFirstAttempt) ConnectionState.CONNECTING else ConnectionState.RECONNECTING,
                null
            )
            isFirstAttempt = false

            var socket: Socket? = null
            var disconnectReason = "Disconnected"

            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.soTimeout = READ_TIMEOUT_MS
                s.receiveBufferSize = 256 * 1024
                s.connect(InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MS)
                socket = s
                activeSocket = s

                Log.i(TAG, "Connected to $HOST:$port (TCP_NODELAY=true)")
                clearPendingSignals()
                touchCoalescer.clear()

                val input = BufferedInputStream(s.getInputStream(), IO_BUFFER_BYTES)
                val output = BufferedOutputStream(s.getOutputStream(), 8192)

                // Send HELLO immediately after TCP connect
                val writer = PacketWriter()
                val metrics = displayMetricsProvider()
                val caps = capabilitiesProvider()
                val hello = TetherProtocol.Message.Hello(
                    displayWidth = metrics.width,
                    displayHeight = metrics.height,
                    densityDpi = metrics.densityDpi,
                    maxDecodeWidth = caps?.maxWidth ?: metrics.width,
                    maxDecodeHeight = caps?.maxHeight ?: metrics.height,
                    maxFps = caps?.maxFps ?: 60,
                    decoderName = caps?.decoderName ?: "unknown"
                )
                writer.writeHello(output, hello)
                listener.onStateChanged(ConnectionState.AWAITING_CONFIG, null)

                disconnectReason = runSession(input, output, writer)
            } catch (t: Throwable) {
                disconnectReason = t.message ?: t.javaClass.simpleName
                Log.d(TAG, "Connection/session ended: $disconnectReason")
            } finally {
                activeSocket = null
                closeSocketQuietly(socket)
                frameQueue.reset(requireIdr = true)
                listener.onDisconnected(disconnectReason)
            }

            if (running.get()) {
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private suspend fun runSession(
        input: InputStream,
        output: OutputStream,
        writer: PacketWriter
    ): String = coroutineScope {
        val reader = PacketReader()
        val header = PacketHeader()
        val videoMeta = VideoFrameMeta()
        val outgoingTouch = TouchCoalescer.OutgoingTouch()

        val writerJob = launch(Dispatchers.IO) {
            while (isActive && running.get()) {
                writerWakeup.receive()
                var wroteAnything = false

                if (pendingKeyframeRequest.compareAndSet(true, false)) {
                    writer.writeRequestKeyframe(output)
                    wroteAnything = true
                }

                val dc = pendingDisplayChange.getAndSet(null)
                if (dc != null) {
                    writer.writeDisplayChange(
                        output,
                        TetherProtocol.Message.DisplayChange(
                            width = dc.width,
                            height = dc.height,
                            densityDpi = dc.densityDpi,
                            rotationDegrees = dc.rotationDegrees
                        )
                    )
                    wroteAnything = true
                }

                val pongTs = pendingPongTimestamp.getAndSet(null)
                if (pongTs != null) {
                    writer.writePong(output, pongTs)
                    wroteAnything = true
                }

                if (pendingPing.compareAndSet(true, false)) {
                    writer.writePing(output, System.nanoTime())
                    wroteAnything = true
                }

                // Drain coalesced touches without flushing per event; flush once at batch end
                var drainedTouch = false
                while (touchCoalescer.poll(outgoingTouch)) {
                    writer.writeTouch(
                        out = output,
                        action = outgoingTouch.action,
                        pointerId = outgoingTouch.pointerId,
                        x = outgoingTouch.x,
                        y = outgoingTouch.y,
                        flush = false
                    )
                    drainedTouch = true
                    wroteAnything = true
                }

                if (drainedTouch || wroteAnything) {
                    output.flush()
                }
            }
        }

        val pingJob = launch(Dispatchers.IO) {
            while (isActive && running.get()) {
                delay(PING_INTERVAL_MS)
                pendingPing.set(true)
                writerWakeup.trySend(Unit)
            }
        }

        try {
            while (running.get()) {
                if (!reader.readHeader(input, header)) {
                    return@coroutineScope "Host closed connection (EOF)"
                }

                when (header.msgType) {
                    MsgType.CONFIG -> {
                        if (header.payloadLength != TetherProtocol.CONFIG_PAYLOAD_SIZE) {
                            throw TetherProtocol.ProtocolException("Bad CONFIG size: ${header.payloadLength}")
                        }
                        val buf = reader.readControlBuffer(input, header.payloadLength)
                        val cfg = TetherProtocol.Message.Config(
                            width = buf.int,
                            height = buf.int,
                            fps = buf.int,
                            codecFlags = buf.int
                        )
                        frameQueue.reset(requireIdr = true)
                        listener.onStateChanged(ConnectionState.STREAMING, "${cfg.width}x${cfg.height}@${cfg.fps}")
                        listener.onConfigReceived(cfg)
                    }

                    MsgType.VIDEO_FRAME -> {
                        reader.readVideoMeta(input, header.payloadLength, videoMeta)
                        val slot = frameQueue.acquireFreeSlot(videoMeta.nalLength)
                        try {
                            if (videoMeta.nalLength > 0) {
                                TetherProtocol.readExactly(input, slot.data, 0, videoMeta.nalLength)
                            }
                            slot.size = videoMeta.nalLength
                            slot.frameSeq = videoMeta.frameSeq
                            slot.ptsUs = videoMeta.ptsUs
                            slot.isKeyFrame = (header.flags and TetherProtocol.FLAG_KEYFRAME) != 0
                            slot.isCodecConfig = (header.flags and TetherProtocol.FLAG_CODEC_CONFIG) != 0
                            slot.receiveNs = System.nanoTime()

                            when (frameQueue.enqueue(slot)) {
                                FrameQueueController.EnqueueResult.QUEUED -> {
                                    listener.onFrameQueued()
                                }
                                FrameQueueController.EnqueueResult.DROPPED_REQUEST_KEYFRAME -> {
                                    requestKeyframe()
                                }
                                FrameQueueController.EnqueueResult.DROPPED_AWAITING_IDR -> {
                                    // Dropped cleanly inside queue while waiting for IDR
                                }
                            }
                        } catch (t: Throwable) {
                            frameQueue.releaseSlot(slot)
                            throw t
                        }
                    }

                    MsgType.PONG -> {
                        if (header.payloadLength != TetherProtocol.PING_PONG_PAYLOAD_SIZE) {
                            reader.skipFully(input, header.payloadLength)
                        } else {
                            val buf = reader.readControlBuffer(input, header.payloadLength)
                            val sentNs = buf.long
                            val rttMs = ((System.nanoTime() - sentNs).coerceAtLeast(0L)) / 1_000_000f
                            listener.onRttUpdated(rttMs)
                        }
                    }

                    MsgType.PING -> {
                        if (header.payloadLength == TetherProtocol.PING_PONG_PAYLOAD_SIZE) {
                            val buf = reader.readControlBuffer(input, header.payloadLength)
                            pendingPongTimestamp.set(buf.long)
                            writerWakeup.trySend(Unit)
                        } else {
                            reader.skipFully(input, header.payloadLength)
                        }
                    }

                    MsgType.BYE -> {
                        val reasonCode = if (header.payloadLength == TetherProtocol.BYE_PAYLOAD_SIZE) {
                            reader.readControlBuffer(input, header.payloadLength).int
                        } else {
                            reader.skipFully(input, header.payloadLength)
                            0
                        }
                        return@coroutineScope "Host sent BYE ($reasonCode)"
                    }

                    else -> {
                        // Ignore unknown/unexpected client-bound message gracefully
                        reader.skipFully(input, header.payloadLength)
                    }
                }
            }
            "Client stopped"
        } finally {
            writerJob.cancel()
            pingJob.cancel()
        }
    }

    private fun clearPendingSignals() {
        pendingKeyframeRequest.set(false)
        pendingPing.set(false)
        pendingPongTimestamp.set(null)
        pendingDisplayChange.set(null)
    }

    private fun closeActiveSocketQuietly() {
        val s = activeSocket
        activeSocket = null
        closeSocketQuietly(s)
    }

    private fun closeSocketQuietly(s: Socket?) {
        if (s == null) return
        try {
            s.close()
        } catch (_: Throwable) {
        }
    }
}
