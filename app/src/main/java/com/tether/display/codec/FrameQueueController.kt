package com.tether.display.codec

/**
 * Pure-Kotlin bounded frame queue, drop-and-resync controller, and zero-allocation
 * per-stage latency tracker. Thread-safe via a single lightweight lock.
 *
 * Hot-path invariant: Once warmed up, acquiring, enqueuing, polling, releasing slots,
 * and recording per-stage timestamps perform zero heap allocations.
 */
class FrameQueueController(
    val maxBacklog: Int = DEFAULT_MAX_BACKLOG,
    initialSlotCapacityBytes: Int = DEFAULT_SLOT_CAPACITY_BYTES,
    private val clockNs: () -> Long = { System.nanoTime() }
) {
    companion object {
        const val DEFAULT_MAX_BACKLOG = 3
        const val DEFAULT_SLOT_CAPACITY_BYTES = 128 * 1024
        private const val EXTRA_POOL_SLOTS = 4
        private const val LATENCY_RING_SIZE = 32
        private const val STATS_WINDOW_NS = 1_000_000_000L
    }

    enum class EnqueueResult {
        QUEUED,
        DROPPED_AWAITING_IDR,
        DROPPED_REQUEST_KEYFRAME
    }

    class FrameSlot(initialCapacity: Int) {
        var data: ByteArray = ByteArray(initialCapacity)
        var size: Int = 0
        var frameSeq: Long = 0L
        var ptsUs: Long = 0L
        var isKeyFrame: Boolean = false
        var isCodecConfig: Boolean = false
        var receiveNs: Long = 0L

        fun ensureCapacity(needed: Int) {
            if (data.size < needed) {
                val newCap = maxOf(needed, data.size * 2)
                data = ByteArray(newCap)
            }
        }

        fun reset() {
            size = 0
            frameSeq = 0L
            ptsUs = 0L
            isKeyFrame = false
            isCodecConfig = false
            receiveNs = 0L
        }
    }

    data class StatsSnapshot(
        val droppedFrames: Long,
        val renderedFrames: Long,
        val currentFps: Float,
        val lastDecodeRenderMs: Float,
        val avgDecodeRenderMs: Float,
        val lastTotalLatencyMs: Float,
        val awaitingIdr: Boolean,
        val pendingQueueSize: Int
    )

    private val lock = Any()
    private val poolCapacity = maxBacklog + EXTRA_POOL_SLOTS
    private val freeSlots = ArrayDeque<FrameSlot>(poolCapacity)
    private val pendingQueue = ArrayDeque<FrameSlot>(maxBacklog + 2)

    private var awaitingIdr: Boolean = true
    private var droppedFrames: Long = 0L
    private var renderedFrames: Long = 0L

    // Fixed primitive ring buffer for per-frame timestamps (indexed by ptsUs match)
    private val ringPtsUs = LongArray(LATENCY_RING_SIZE) { Long.MIN_VALUE }
    private val ringReceiveNs = LongArray(LATENCY_RING_SIZE)
    private val ringDecodeStartNs = LongArray(LATENCY_RING_SIZE)
    private var ringCursor = 0

    // Rolling FPS & latency stats (zero-alloc)
    private var fpsWindowStartNs = 0L
    private var fpsWindowFrames = 0
    private var currentFps = 0f
    private var lastDecodeRenderMs = 0f
    private var avgDecodeRenderMs = 0f
    private var lastTotalLatencyMs = 0f

    init {
        for (i in 0 until poolCapacity) {
            freeSlots.addLast(FrameSlot(initialSlotCapacityBytes))
        }
    }

    fun acquireFreeSlot(neededBytes: Int): FrameSlot {
        val slot = synchronized(lock) {
            if (freeSlots.isNotEmpty()) freeSlots.removeFirst() else null
        } ?: FrameSlot(maxOf(neededBytes, DEFAULT_SLOT_CAPACITY_BYTES))
        slot.reset()
        slot.ensureCapacity(neededBytes)
        return slot
    }

    fun releaseSlot(slot: FrameSlot) {
        slot.reset()
        synchronized(lock) {
            if (freeSlots.size < poolCapacity) {
                freeSlots.addLast(slot)
            }
        }
    }

    fun enqueue(slot: FrameSlot): EnqueueResult {
        synchronized(lock) {
            if (awaitingIdr) {
                if (slot.isCodecConfig) {
                    pendingQueue.addLast(slot)
                    return EnqueueResult.QUEUED
                }
                if (slot.isKeyFrame) {
                    awaitingIdr = false
                    pendingQueue.addLast(slot)
                    return EnqueueResult.QUEUED
                }
                droppedFrames++
                slot.reset()
                if (freeSlots.size < poolCapacity) freeSlots.addLast(slot)
                return EnqueueResult.DROPPED_AWAITING_IDR
            }

            if (slot.isKeyFrame) {
                // Fresh IDR arrived: flush stale non-config frames to minimize latency
                dropPendingNonConfigLocked()
                pendingQueue.addLast(slot)
                return EnqueueResult.QUEUED
            }

            if (!slot.isCodecConfig && countPendingNonConfigLocked() >= maxBacklog) {
                dropPendingNonConfigLocked()
                droppedFrames++
                slot.reset()
                if (freeSlots.size < poolCapacity) freeSlots.addLast(slot)
                awaitingIdr = true
                return EnqueueResult.DROPPED_REQUEST_KEYFRAME
            }

            pendingQueue.addLast(slot)
            return EnqueueResult.QUEUED
        }
    }

    fun pollNextPending(): FrameSlot? {
        synchronized(lock) {
            return if (pendingQueue.isNotEmpty()) pendingQueue.removeFirst() else null
        }
    }

    /** Records when a frame's input buffer is submitted to MediaCodec. */
    fun recordDecodeStart(ptsUs: Long, receiveNs: Long, decodeStartNs: Long = clockNs()) {
        synchronized(lock) {
            ringPtsUs[ringCursor] = ptsUs
            ringReceiveNs[ringCursor] = receiveNs
            ringDecodeStartNs[ringCursor] = decodeStartNs
            ringCursor = (ringCursor + 1) % LATENCY_RING_SIZE
        }
    }

    /** Records when MediaCodec outputs and releases a frame to the Surface. */
    fun recordDecodeAndRenderDone(ptsUs: Long, renderNs: Long = clockNs()) {
        synchronized(lock) {
            renderedFrames++
            if (fpsWindowStartNs == 0L) {
                fpsWindowStartNs = renderNs
            }
            fpsWindowFrames++
            val elapsedWindow = renderNs - fpsWindowStartNs
            if (elapsedWindow >= STATS_WINDOW_NS) {
                currentFps = (fpsWindowFrames * 1_000_000_000.0 / elapsedWindow).toFloat()
                fpsWindowStartNs = renderNs
                fpsWindowFrames = 0
            }

            // Reverse search ring for matching ptsUs
            for (offset in 1..LATENCY_RING_SIZE) {
                val idx = (ringCursor - offset + LATENCY_RING_SIZE) % LATENCY_RING_SIZE
                if (ringPtsUs[idx] == ptsUs) {
                    val decodeNs = (renderNs - ringDecodeStartNs[idx]).coerceAtLeast(0L)
                    val totalNs = (renderNs - ringReceiveNs[idx]).coerceAtLeast(0L)
                    val decodeMs = decodeNs / 1_000_000f
                    val totalMs = totalNs / 1_000_000f
                    lastDecodeRenderMs = decodeMs
                    avgDecodeRenderMs = if (avgDecodeRenderMs == 0f) {
                        decodeMs
                    } else {
                        avgDecodeRenderMs * 0.85f + decodeMs * 0.15f
                    }
                    lastTotalLatencyMs = totalMs
                    ringPtsUs[idx] = Long.MIN_VALUE
                    break
                }
            }
        }
    }

    fun reset(requireIdr: Boolean = true) {
        synchronized(lock) {
            while (pendingQueue.isNotEmpty()) {
                val slot = pendingQueue.removeFirst()
                slot.reset()
                if (freeSlots.size < poolCapacity) freeSlots.addLast(slot)
            }
            awaitingIdr = requireIdr
            for (i in 0 until LATENCY_RING_SIZE) {
                ringPtsUs[i] = Long.MIN_VALUE
            }
            fpsWindowStartNs = 0L
            fpsWindowFrames = 0
            currentFps = 0f
        }
    }

    fun resetAllStats() {
        synchronized(lock) {
            droppedFrames = 0L
            renderedFrames = 0L
            lastDecodeRenderMs = 0f
            avgDecodeRenderMs = 0f
            lastTotalLatencyMs = 0f
            currentFps = 0f
            fpsWindowStartNs = 0L
            fpsWindowFrames = 0
        }
    }

    fun isAwaitingIdr(): Boolean = synchronized(lock) { awaitingIdr }

    fun snapshot(): StatsSnapshot = synchronized(lock) {
        StatsSnapshot(
            droppedFrames = droppedFrames,
            renderedFrames = renderedFrames,
            currentFps = currentFps,
            lastDecodeRenderMs = lastDecodeRenderMs,
            avgDecodeRenderMs = avgDecodeRenderMs,
            lastTotalLatencyMs = lastTotalLatencyMs,
            awaitingIdr = awaitingIdr,
            pendingQueueSize = pendingQueue.size
        )
    }

    private fun countPendingNonConfigLocked(): Int {
        var count = 0
        for (i in 0 until pendingQueue.size) {
            if (!pendingQueue[i].isCodecConfig) count++
        }
        return count
    }

    private fun dropPendingNonConfigLocked() {
        val count = pendingQueue.size
        for (i in 0 until count) {
            val s = pendingQueue.removeFirst()
            if (s.isCodecConfig) {
                pendingQueue.addLast(s)
            } else {
                droppedFrames++
                s.reset()
                if (freeSlots.size < poolCapacity) freeSlots.addLast(s)
            }
        }
    }
}
