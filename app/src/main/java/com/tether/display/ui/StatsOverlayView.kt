package com.tether.display.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.R
import com.tether.display.codec.FrameQueueController

/**
 * Optional translucent HUD overlay displaying live decode+render latency,
 * dropped frames, current FPS, RTT, and active stream/decoder info.
 * Zero allocations during normal streaming; refreshed at 2 Hz only when enabled.
 */
class StatsOverlayView(
    context: Context,
    onToggleOverlay: () -> Unit
) : LinearLayout(context) {

    private val toggleButton: TextView
    private val metricsPanel: TextView
    private var panelExpanded = false

    init {
        orientation = VERTICAL
        gravity = Gravity.END

        toggleButton = TextView(context).apply {
            text = context.getString(R.string.toggle_stats)
            contentDescription = context.getString(R.string.cd_stats_toggle)
            tag = "hud_stats_toggle"
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#660D131D"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#44334155"))
            }
            setOnClickListener { onToggleOverlay() }
        }

        metricsPanel = TextView(context).apply {
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2).toFloat(), 1.0f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC0D131D"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#6600E5FF"))
            }
        }

        addView(toggleButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
        addView(metricsPanel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
    }

    fun setExpanded(expanded: Boolean) {
        panelExpanded = expanded
        metricsPanel.visibility = if (expanded) View.VISIBLE else View.GONE
        toggleButton.setTextColor(
            Color.parseColor(if (expanded) "#00E5FF" else "#94A3B8")
        )
    }

    fun isExpanded(): Boolean = panelExpanded

    fun updateStats(
        snapshot: FrameQueueController.StatsSnapshot,
        streamWidth: Int,
        streamHeight: Int,
        targetFps: Int,
        rttMs: Float,
        decoderTag: String
    ) {
        if (!panelExpanded) return
        val resLine = if (streamWidth > 0 && streamHeight > 0) {
            "${streamWidth}x$streamHeight@${targetFps}"
        } else {
            "—"
        }
        metricsPanel.text = String.format(
            "STREAM %s\nFPS    %.1f\nDEC    %.1f ms (avg %.1f)\nDROP   %d\nRTT    %.1f ms\nCODEC  %s",
            resLine,
            snapshot.currentFps,
            snapshot.lastDecodeRenderMs,
            snapshot.avgDecodeRenderMs,
            snapshot.droppedFrames,
            rttMs,
            decoderTag
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
