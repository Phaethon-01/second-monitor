package com.tether.display.input

import android.view.MotionEvent
import com.tether.display.proto.TetherProtocol.TouchAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchInputTest {

    @Test
    fun `maps coordinates strictly over letterboxed visible video area`() {
        val mapper = TouchMapper()
        // Screen 1920x1080, centered 4:3 video -> left=240, top=0, right=1680, bottom=1080 (width=1440)
        val bounds = TouchMapper.VideoBounds(left = 240f, top = 0f, right = 1680f, bottom = 1080f)

        // Touch in left black bar is ignored
        assertNull(mapper.mapRawEvent(MotionEvent.ACTION_DOWN, 0, 100f, 540f, bounds))

        // Touch at video top-left corner -> (0, 0)
        val down = mapper.mapRawEvent(MotionEvent.ACTION_DOWN, 0, 240f, 0f, bounds)
        assertNotNull(down)
        assertEquals(TouchAction.DOWN, down!!.action)
        assertEquals(0, down.x)
        assertEquals(0, down.y)

        // Drag to exact center -> (~32768, ~32768)
        val moveCenter = mapper.mapRawEvent(MotionEvent.ACTION_MOVE, 0, 960f, 540f, bounds)
        assertNotNull(moveCenter)
        assertEquals(TouchAction.MOVE, moveCenter!!.action)
        assertTrue(moveCenter.x in 32767..32768)
        assertTrue(moveCenter.y in 32767..32768)

        // Secondary pointer is ignored
        assertNull(mapper.mapRawEvent(MotionEvent.ACTION_MOVE, 1, 500f, 500f, bounds))

        // Dragging outside right edge clamps to 65535
        val upOutside = mapper.mapRawEvent(MotionEvent.ACTION_UP, 0, 1900f, 1080f, bounds)
        assertNotNull(upOutside)
        assertEquals(TouchAction.UP, upOutside!!.action)
        assertEquals(65535, upOutside.x)
        assertEquals(65535, upOutside.y)
    }

    @Test
    fun `handles cancel cleanly and clears active pointer`() {
        val mapper = TouchMapper()
        val bounds = TouchMapper.VideoBounds(0f, 0f, 1000f, 500f)

        assertNotNull(mapper.mapRawEvent(MotionEvent.ACTION_DOWN, 2, 500f, 250f, bounds))
        val cancel = mapper.mapRawEvent(MotionEvent.ACTION_CANCEL, 2, 500f, 250f, bounds)
        assertNotNull(cancel)
        assertEquals(TouchAction.CANCEL, cancel!!.action)
        assertEquals(0, cancel.pointerId)

        // Subsequent MOVE without DOWN is ignored
        assertNull(mapper.mapRawEvent(MotionEvent.ACTION_MOVE, 2, 510f, 260f, bounds))
    }

    @Test
    fun `coalescer merges moves and never drops UP or CANCEL under backlog`() {
        val coalescer = TouchCoalescer(capacity = 4)

        coalescer.enqueue(TouchAction.DOWN, 0, 100, 100)
        coalescer.enqueue(TouchAction.MOVE, 0, 200, 200)
        coalescer.enqueue(TouchAction.MOVE, 0, 300, 300)
        coalescer.enqueue(TouchAction.MOVE, 0, 400, 400)

        // Only 2 slots should be occupied: DOWN + coalesced MOVE(400, 400)
        assertEquals(2, coalescer.size())
        assertEquals(2L, coalescer.coalescedMoves)

        // Fill remaining slots with distinct pointer/non-coalescing pattern, then push UP
        coalescer.enqueue(TouchAction.DOWN, 1, 500, 500)
        coalescer.enqueue(TouchAction.MOVE, 0, 600, 600)
        assertEquals(4, coalescer.size())

        // Overflow with another MOVE -> dropped cleanly
        coalescer.enqueue(TouchAction.MOVE, 1, 700, 700)
        assertEquals(4, coalescer.size())
        assertEquals(1L, coalescer.droppedMoves)

        // Overflow with UP -> must evict an older MOVE and retain UP!
        coalescer.enqueue(TouchAction.UP, 0, 800, 800)
        assertEquals(4, coalescer.size())

        val drained = mutableListOf<TouchAction>()
        val out = TouchCoalescer.OutgoingTouch()
        while (coalescer.poll(out)) {
            drained.add(out.action)
        }
        assertFalse(coalescer.poll(out))
        assertEquals(TouchAction.UP, drained.last())
        assertEquals(TouchAction.DOWN, drained.first())
    }
}
