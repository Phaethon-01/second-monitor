package com.tether.display.codec

import com.tether.display.codec.FrameQueueController.EnqueueResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameQueueControllerTest {

    private var fakeTimeNs = 1_000_000_000L

    private fun newController(maxBacklog: Int = 3): FrameQueueController =
        FrameQueueController(
            maxBacklog = maxBacklog,
            initialSlotCapacityBytes = 256,
            clockNs = { fakeTimeNs }
        )

    private fun enqueueFrame(
        ctrl: FrameQueueController,
        seq: Long,
        ptsUs: Long,
        isKey: Boolean = false,
        isConfig: Boolean = false
    ): EnqueueResult {
        val slot = ctrl.acquireFreeSlot(16)
        slot.size = 16
        slot.frameSeq = seq
        slot.ptsUs = ptsUs
        slot.isKeyFrame = isKey
        slot.isCodecConfig = isConfig
        slot.receiveNs = fakeTimeNs
        return ctrl.enqueue(slot)
    }

    @Test
    fun `drops non-key frames until first IDR arrives while keeping codec config`() {
        val ctrl = newController()
        assertTrue(ctrl.isAwaitingIdr())

        assertEquals(EnqueueResult.DROPPED_AWAITING_IDR, enqueueFrame(ctrl, 1, 1000L, isKey = false))
        assertEquals(EnqueueResult.QUEUED, enqueueFrame(ctrl, 2, 0L, isConfig = true))
        assertEquals(EnqueueResult.DROPPED_AWAITING_IDR, enqueueFrame(ctrl, 3, 2000L, isKey = false))
        assertEquals(2L, ctrl.snapshot().droppedFrames)
        assertTrue(ctrl.isAwaitingIdr())

        assertEquals(EnqueueResult.QUEUED, enqueueFrame(ctrl, 4, 3000L, isKey = true))
        assertFalse(ctrl.isAwaitingIdr())

        val first = ctrl.pollNextPending()!!
        assertTrue(first.isCodecConfig)
        ctrl.releaseSlot(first)

        val second = ctrl.pollNextPending()!!
        assertTrue(second.isKeyFrame)
        assertEquals(4L, second.frameSeq)
        ctrl.releaseSlot(second)
        assertNull(ctrl.pollNextPending())
    }

    @Test
    fun `backlog overflow drops non-key frames, requests keyframe, and recovers on next IDR`() {
        val ctrl = newController(maxBacklog = 3)

        // Start with IDR + 2 P-frames (queue reaches capacity = 3)
        assertEquals(EnqueueResult.QUEUED, enqueueFrame(ctrl, 1, 1000L, isKey = true))
        assertEquals(EnqueueResult.QUEUED, enqueueFrame(ctrl, 2, 2000L, isKey = false))
        assertEquals(EnqueueResult.QUEUED, enqueueFrame(ctrl, 3, 3000L, isKey = false))
        assertEquals(3, ctrl.snapshot().pendingQueueSize)

        // 4th frame arrives while backlog >= 3 -> drop all & request keyframe
        assertEquals(EnqueueResult.DROPPED_REQUEST_KEYFRAME, enqueueFrame(ctrl, 4, 4000L, isKey = false))
        assertTrue(ctrl.isAwaitingIdr())
        assertEquals(0, ctrl.snapshot().pendingQueueSize)
        assertEquals(4L, ctrl.snapshot().droppedFrames)

        // Subsequent P-frame is dropped while awaiting IDR
        assertEquals(EnqueueResult.DROPPED_AWAITING_IDR, enqueueFrame(ctrl, 5, 5000L, isKey = false))
        assertEquals(5L, ctrl.snapshot().droppedFrames)

        // Recovery IDR arrives
        assertEquals(EnqueueResult.QUEUED, enqueueFrame(ctrl, 6, 6000L, isKey = true))
        assertFalse(ctrl.isAwaitingIdr())
        val recovered = ctrl.pollNextPending()
        assertNotNull(recovered)
        assertEquals(6L, recovered!!.frameSeq)
        ctrl.releaseSlot(recovered)
    }

    @Test
    fun `tracks per-stage latency and fps with fake clock`() {
        val ctrl = newController()

        // Frame 1 at t = 1.000s, decode start +1ms, render +5ms
        val receive1 = 1_000_000_000L
        fakeTimeNs = receive1 + 1_000_000L
        ctrl.recordDecodeStart(ptsUs = 1000L, receiveNs = receive1)
        fakeTimeNs = receive1 + 5_000_000L
        ctrl.recordDecodeAndRenderDone(ptsUs = 1000L)

        val snap1 = ctrl.snapshot()
        assertEquals(4.0f, snap1.lastDecodeRenderMs, 0.01f)
        assertEquals(5.0f, snap1.lastTotalLatencyMs, 0.01f)
        assertEquals(1L, snap1.renderedFrames)

        // 30 more frames over 1 second -> FPS updates cleanly
        for (i in 1..30) {
            fakeTimeNs += 33_333_333L
            ctrl.recordDecodeStart(ptsUs = 1000L + i * 33_333L, receiveNs = fakeTimeNs - 1_000_000L)
            fakeTimeNs += 3_000_000L
            ctrl.recordDecodeAndRenderDone(ptsUs = 1000L + i * 33_333L)
        }

        val snap2 = ctrl.snapshot()
        assertTrue(snap2.currentFps in 25.0f..32.0f)
        assertEquals(31L, snap2.renderedFrames)
    }
}
