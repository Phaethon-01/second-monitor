package com.tether.display.ui

import android.content.Context
import android.graphics.RectF
import android.util.AttributeSet
import android.view.SurfaceView
import com.tether.display.input.TouchMapper

/**
 * Aspect-ratio preserving SurfaceView for direct MediaCodec rendering.
 * Scales video to fit within parent bounds (including streams larger than the screen)
 * while maintaining exact aspect ratio and exposing the visible video bounds for touch mapping.
 */
class AspectSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs) {

    private var videoWidth: Int = 16
    private var videoHeight: Int = 9

    val visibleBounds = TouchMapper.VideoBounds()

    fun setVideoAspect(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (videoWidth == width && videoHeight == height) return
        videoWidth = width
        videoHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = MeasureSpec.getSize(widthMeasureSpec)
        val availH = MeasureSpec.getSize(heightMeasureSpec)

        if (availW <= 0 || availH <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val viewAspect = availW.toFloat() / availH.toFloat()
        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()

        val measuredW: Int
        val measuredH: Int
        if (videoAspect > viewAspect) {
            // Stream is wider than container: fit width, letterbox top/bottom
            measuredW = availW
            measuredH = (availW / videoAspect).toInt().coerceAtLeast(1)
        } else {
            // Stream is taller/narrower than container: fit height, pillarbox left/right
            measuredH = availH
            measuredW = (availH * videoAspect).toInt().coerceAtLeast(1)
        }
        setMeasuredDimension(measuredW, measuredH)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        visibleBounds.left = left.toFloat()
        visibleBounds.top = top.toFloat()
        visibleBounds.right = right.toFloat()
        visibleBounds.bottom = bottom.toFloat()
    }

    fun copyVisibleRect(out: RectF) {
        out.set(visibleBounds.left, visibleBounds.top, visibleBounds.right, visibleBounds.bottom)
    }
}
