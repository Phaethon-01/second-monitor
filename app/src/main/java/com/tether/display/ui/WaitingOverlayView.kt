package com.tether.display.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.example.R
import com.tether.display.codec.DecoderCapabilities
import com.tether.display.net.TetherClient.ConnectionState

/**
 * Lightweight View-system waiting screen shown while connecting, awaiting CONFIG/IDR,
 * or recovering from host disconnect / cable replug.
 */
class WaitingOverlayView(
    context: Context,
    port: Int,
    onToggleStats: () -> Unit,
    onToggleRotation: () -> Unit
) : LinearLayout(context) {

    private val statusDot: View
    private val statusText: TextView
    private val spinner: ProgressBar
    private val decoderText: TextView
    private val statsButton: TextView
    private val rotationButton: TextView

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.parseColor("#EE080C12"))
        val pad = dp(24)
        setPadding(pad, pad, pad, pad)
        isClickable = true // intercept touches only while waiting overlay is visible
        isFocusable = true

        // Card container
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val cardPadH = dp(28)
            val cardPadV = dp(24)
            setPadding(cardPadH, cardPadV, cardPadH, cardPadV)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#121A27"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#243247"))
            }
        }

        // Header row: Logo + Title
        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val logo = ImageView(context).apply {
            setImageResource(R.mipmap.ic_launcher_round)
            contentDescription = context.getString(R.string.cd_tether_logo)
        }
        headerRow.addView(logo, LayoutParams(dp(44), dp(44)).apply {
            marginEnd = dp(12)
        })

        val titleCol = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        val titleView = TextView(context).apply {
            text = context.getString(R.string.waiting_title)
            setTextColor(Color.parseColor("#F1F5F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val endpointView = TextView(context).apply {
            text = "127.0.0.1:$port  •  ${context.getString(R.string.adb_hint, port)}"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
        }
        titleCol.addView(titleView)
        titleCol.addView(endpointView)
        headerRow.addView(titleCol)
        card.addView(headerRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(18)
        })

        // Status pill row
        val statusPill = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0B111A"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#1E293B"))
            }
        }

        statusDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#00E5FF"))
            }
        }
        statusPill.addView(statusDot, LayoutParams(dp(10), dp(10)).apply {
            marginEnd = dp(10)
        })

        spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
        }
        statusPill.addView(spinner, LayoutParams(dp(18), dp(18)).apply {
            marginEnd = dp(10)
        })

        statusText = TextView(context).apply {
            text = context.getString(R.string.status_connecting)
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        statusPill.addView(statusText)
        card.addView(statusPill, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        // Decoder info label
        decoderText = TextView(context).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        card.addView(decoderText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(18)
        })

        // Quick action pills (>= 48dp touch targets)
        val actionsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }

        statsButton = buildActionChip(context.getString(R.string.toggle_stats)).apply {
            contentDescription = context.getString(R.string.cd_stats_toggle)
            tag = "waiting_stats_toggle"
            setOnClickListener { onToggleStats() }
        }
        rotationButton = buildActionChip(context.getString(R.string.toggle_rotation_landscape)).apply {
            contentDescription = context.getString(R.string.cd_rotation_toggle)
            tag = "waiting_rotation_toggle"
            setOnClickListener { onToggleRotation() }
        }

        actionsRow.addView(statsButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)).apply {
            marginEnd = dp(12)
        })
        actionsRow.addView(rotationButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
        card.addView(actionsRow)

        addView(card, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
    }

    fun updateDecoderCapabilities(caps: DecoderCapabilities?) {
        decoderText.text = if (caps != null) {
            val tag = if (caps.isHardware) "HW" else "SW"
            "[$tag] ${caps.decoderName} • max ${caps.maxWidth}x${caps.maxHeight} @ ${caps.maxFps}fps"
        } else {
            context.getString(R.string.decoder_unavailable)
        }
    }

    fun updateState(state: ConnectionState, detail: String? = null) {
        val (label, dotColorHex) = when (state) {
            ConnectionState.CONNECTING ->
                context.getString(R.string.status_connecting) to "#00E5FF"
            ConnectionState.AWAITING_CONFIG ->
                context.getString(R.string.status_waiting_config) to "#10B981"
            ConnectionState.STREAMING ->
                (detail?.let { context.getString(R.string.status_waiting_keyframe) + " ($it)" }
                    ?: context.getString(R.string.status_waiting_keyframe)) to "#10B981"
            ConnectionState.RECONNECTING ->
                context.getString(R.string.status_reconnecting) to "#F59E0B"
        }
        statusText.text = label
        (statusDot.background as? GradientDrawable)?.setColor(Color.parseColor(dotColorHex))
    }

    fun updateRotationLabel(autoRotateEnabled: Boolean) {
        rotationButton.text = if (autoRotateEnabled) {
            context.getString(R.string.toggle_rotation_auto)
        } else {
            context.getString(R.string.toggle_rotation_landscape)
        }
    }

    fun updateStatsButtonState(enabled: Boolean) {
        statsButton.text = if (enabled) "Stats: ON" else "Stats: OFF"
        (statsButton.background as? GradientDrawable)?.setStroke(
            dp(1),
            Color.parseColor(if (enabled) "#00E5FF" else "#334155")
        )
    }

    private fun buildActionChip(label: String): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            minWidth = dp(96)
            setPadding(dp(18), 0, dp(18), 0)
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A2436"))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
