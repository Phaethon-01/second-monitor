package com.tether.display.input

import com.tether.display.proto.TetherProtocol.TouchAction

/**
 * Zero-allocation bounded ring buffer for outgoing TOUCH events.
 *
 * Policies per PRD 3.5:
 * - Coalesces consecutive MOVE events in-place so the socket is not flooded.
 * - Under backlog, drops intermediate MOVE events.
 * - NEVER drops DOWN, UP, or CANCEL events (evicts oldest MOVE if full).
 */
class TouchCoalescer(
    private val capacity: Int = 16
) {
    data class OutgoingTouch(
        var action: TouchAction = TouchAction.MOVE,
        var pointerId: Int = 0,
        var x: Int = 0,
        var y: Int = 0
    )

    private val lock = Any()
    private val actions = Array(capacity) { TouchAction.MOVE }
    private val pointerIds = IntArray(capacity)
    private val xs = IntArray(capacity)
    private val ys = IntArray(capacity)
    private var head = 0
    private var count = 0

    var droppedMoves: Long = 0L
        private set
    var coalescedMoves: Long = 0L
        private set

    fun enqueue(action: TouchAction, pointerId: Int, x: Int, y: Int) {
        synchronized(lock) {
            if (action == TouchAction.MOVE && count > 0) {
                val tailIdx = (head + count - 1) % capacity
                if (actions[tailIdx] == TouchAction.MOVE && pointerIds[tailIdx] == pointerId) {
                    // Coalesce latest MOVE into trailing MOVE
                    xs[tailIdx] = x
                    ys[tailIdx] = y
                    coalescedMoves++
                    return
                }
            }

            if (count == capacity) {
                if (action == TouchAction.MOVE) {
                    // Drop incoming MOVE under backlog
                    droppedMoves++
                    return
                }
                // Non-MOVE (DOWN/UP/CANCEL): evict the oldest MOVE if one exists
                if (!evictOldestMoveLocked()) {
                    // Fallback: overwrite oldest entry only if queue contains zero MOVEs
                    head = (head + 1) % capacity
                    count--
                }
            }

            val insertIdx = (head + count) % capacity
            actions[insertIdx] = action
            pointerIds[insertIdx] = pointerId
            xs[insertIdx] = x
            ys[insertIdx] = y
            count++
        }
    }

    fun poll(out: OutgoingTouch): Boolean {
        synchronized(lock) {
            if (count == 0) return false
            out.action = actions[head]
            out.pointerId = pointerIds[head]
            out.x = xs[head]
            out.y = ys[head]
            head = (head + 1) % capacity
            count--
            return true
        }
    }

    fun size(): Int = synchronized(lock) { count }

    fun clear() {
        synchronized(lock) {
            head = 0
            count = 0
        }
    }

    private fun evictOldestMoveLocked(): Boolean {
        for (i in 0 until count) {
            val idx = (head + i) % capacity
            if (actions[idx] == TouchAction.MOVE) {
                // Shift subsequent elements left by 1
                for (j in i until count - 1) {
                    val dst = (head + j) % capacity
                    val src = (head + j + 1) % capacity
                    actions[dst] = actions[src]
                    pointerIds[dst] = pointerIds[src]
                    xs[dst] = xs[src]
                    ys[dst] = ys[src]
                }
                count--
                droppedMoves++
                return true
            }
        }
        return false
    }
}
