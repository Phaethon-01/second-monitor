package com.tether.display.input

import android.view.MotionEvent
import com.tether.display.proto.TetherProtocol.TouchAction
import kotlin.math.roundToInt

/**
 * Maps MotionEvent / raw view coordinates onto normalized (0..65535) coordinates
 * strictly over the visible video rectangle (ignoring letterbox/pillarbox margins).
 *
 * v1 tracks the primary pointer only and supports DOWN, MOVE, UP, CANCEL.
 */
class TouchMapper {
    data class NormalizedTouch(
        val action: TouchAction,
        val pointerId: Int,
        val x: Int,
        val y: Int
    )

    data class VideoBounds(
        var left: Float = 0f,
        var top: Float = 0f,
        var right: Float = 0f,
        var bottom: Float = 0f
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        fun isValid(): Boolean = width > 0f && height > 0f
        fun contains(px: Float, py: Float): Boolean =
            isValid() && px >= left && px <= right && py >= top && py <= bottom
    }

    private var activePointerId: Int = INVALID_POINTER_ID

    fun reset() {
        activePointerId = INVALID_POINTER_ID
    }

    /**
     * Pure coordinate helper for JVM unit tests and MotionEvent processing.
     * Returns null if there is no active touch or a DOWN started outside [bounds].
     */
    fun mapRawEvent(
        actionMasked: Int,
        pointerId: Int,
        rawX: Float,
        rawY: Float,
        bounds: VideoBounds
    ): NormalizedTouch? {
        if (!bounds.isValid()) {
            if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
                activePointerId = INVALID_POINTER_ID
            }
            return null
        }

        return when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!bounds.contains(rawX, rawY)) {
                    activePointerId = INVALID_POINTER_ID
                    null
                } else {
                    activePointerId = pointerId
                    NormalizedTouch(
                        action = TouchAction.DOWN,
                        pointerId = 0, // v1 primary pointer normalized to 0
                        x = normalizeCoord(rawX, bounds.left, bounds.width),
                        y = normalizeCoord(rawY, bounds.top, bounds.height)
                    )
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointerId == INVALID_POINTER_ID || pointerId != activePointerId) {
                    null
                } else {
                    NormalizedTouch(
                        action = TouchAction.MOVE,
                        pointerId = 0,
                        x = normalizeCoord(rawX, bounds.left, bounds.width),
                        y = normalizeCoord(rawY, bounds.top, bounds.height)
                    )
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (activePointerId == INVALID_POINTER_ID || pointerId != activePointerId) {
                    null
                } else {
                    activePointerId = INVALID_POINTER_ID
                    NormalizedTouch(
                        action = TouchAction.UP,
                        pointerId = 0,
                        x = normalizeCoord(rawX, bounds.left, bounds.width),
                        y = normalizeCoord(rawY, bounds.top, bounds.height)
                    )
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (activePointerId == INVALID_POINTER_ID) {
                    null
                } else {
                    activePointerId = INVALID_POINTER_ID
                    NormalizedTouch(
                        action = TouchAction.CANCEL,
                        pointerId = 0,
                        x = normalizeCoord(rawX, bounds.left, bounds.width),
                        y = normalizeCoord(rawY, bounds.top, bounds.height)
                    )
                }
            }

            else -> null
        }
    }

    /** Processes an Android MotionEvent directly with zero extra allocations when possible. */
    fun processMotionEvent(
        event: MotionEvent,
        bounds: VideoBounds,
        onMapped: (TouchAction, Int, Int, Int) -> Unit
    ): Boolean {
        val actionMasked = event.actionMasked
        return when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pid = event.getPointerId(0)
                val mapped = mapRawEvent(actionMasked, pid, event.x, event.y, bounds) ?: return false
                onMapped(mapped.action, mapped.pointerId, mapped.x, mapped.y)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val active = activePointerId
                if (active == INVALID_POINTER_ID) return false
                val idx = event.findPointerIndex(active)
                if (idx < 0) return false
                val mapped = mapRawEvent(actionMasked, active, event.getX(idx), event.getY(idx), bounds)
                    ?: return false
                onMapped(mapped.action, mapped.pointerId, mapped.x, mapped.y)
                true
            }

            MotionEvent.ACTION_UP -> {
                val active = activePointerId
                if (active == INVALID_POINTER_ID) return false
                val idx = event.findPointerIndex(active).let { if (it >= 0) it else 0 }
                val pid = event.getPointerId(idx)
                val mapped = mapRawEvent(actionMasked, pid, event.getX(idx), event.getY(idx), bounds)
                    ?: return false
                onMapped(mapped.action, mapped.pointerId, mapped.x, mapped.y)
                true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val actionIdx = event.actionIndex
                val pid = event.getPointerId(actionIdx)
                val mapped = mapRawEvent(actionMasked, pid, event.getX(actionIdx), event.getY(actionIdx), bounds)
                    ?: return false
                onMapped(mapped.action, mapped.pointerId, mapped.x, mapped.y)
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                val active = activePointerId
                if (active == INVALID_POINTER_ID) return false
                val idx = event.findPointerIndex(active).let { if (it >= 0) it else 0 }
                val mapped = mapRawEvent(actionMasked, active, event.getX(idx), event.getY(idx), bounds)
                    ?: return false
                onMapped(mapped.action, mapped.pointerId, mapped.x, mapped.y)
                true
            }

            else -> false
        }
    }

    companion object {
        const val INVALID_POINTER_ID = -1
        const val MAX_NORM = 65535

        fun normalizeCoord(value: Float, origin: Float, span: Float): Int {
            if (span <= 0f) return 0
            val fraction = ((value - origin) / span).coerceIn(0f, 1f)
            return (fraction * MAX_NORM).roundToInt().coerceIn(0, MAX_NORM)
        }
    }
}
