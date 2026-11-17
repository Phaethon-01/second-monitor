package com.tether.display.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.tether.display.codec.DecoderCapabilities
import com.tether.display.codec.FrameQueueController
import com.tether.display.codec.VideoDecoder
import com.tether.display.input.TouchCoalescer
import com.tether.display.input.TouchMapper
import com.tether.display.net.TetherClient
import com.tether.display.proto.TetherProtocol

/**
 * Main Tether display client Activity built purely on the Android View system
 * (no Compose) for minimal overhead and direct SurfaceView MediaCodec rendering.
 */
open class TetherActivity : Activity() {

    companion object {
        private const val TAG = "TetherActivity"
        const val EXTRA_PORT = "port"
        private const val STATS_REFRESH_MS = 500L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameQueue = FrameQueueController()
    private val touchMapper = TouchMapper()
    private val touchCoalescer = TouchCoalescer()

    private lateinit var rootContainer: FrameLayout
    private lateinit var surfaceView: AspectSurfaceView
    private lateinit var waitingOverlay: WaitingOverlayView
    private lateinit var statsOverlay: StatsOverlayView

    private var decoderCapabilities: DecoderCapabilities? = null
    private lateinit var videoDecoder: VideoDecoder
    private lateinit var tetherClient: TetherClient

    private var activeSurface: Surface? = null
    private var currentConfig: TetherProtocol.Message.Config? = null
    private var autoRotateEnabled: Boolean = false
    private var lastReportedWidth = 0
    private var lastReportedHeight = 0
    private var lastReportedRotation = -1
    @Volatile
    private var lastRttMs: Float = 0f

    private var displayManager: DisplayManager? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            checkAndSendDisplayChange()
        }
    }

    private val statsRefreshTask = object : Runnable {
        override fun run() {
            if (statsOverlay.isExpanded()) {
                val cfg = currentConfig
                val caps = decoderCapabilities
                val codecTag = if (caps != null) {
                    val hw = if (caps.isHardware) "HW" else "SW"
                    "${caps.decoderName.takeLast(22)} [$hw]"
                } else {
                    "none"
                }
                statsOverlay.updateStats(
                    snapshot = frameQueue.snapshot(),
                    streamWidth = cfg?.width ?: 0,
                    streamHeight = cfg?.height ?: 0,
                    targetFps = cfg?.fps ?: 0,
                    rttMs = lastRttMs,
                    decoderTag = codecTag
                )
            }
            mainHandler.postDelayed(this, STATS_REFRESH_MS)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val port = intent?.getIntExtra(EXTRA_PORT, TetherProtocol.DEFAULT_PORT)
            ?: TetherProtocol.DEFAULT_PORT

        decoderCapabilities = DecoderCapabilities.queryBestAvcDecoder()

        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        surfaceView = AspectSurfaceView(this)
        rootContainer.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        waitingOverlay = WaitingOverlayView(
            context = this,
            port = port,
            onToggleStats = { toggleStatsOverlay() },
            onToggleRotation = { toggleOrientationLock() }
        ).apply {
            updateDecoderCapabilities(decoderCapabilities)
            updateRotationLabel(autoRotateEnabled)
            updateStatsButtonState(false)
        }
        rootContainer.addView(
            waitingOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        statsOverlay = StatsOverlayView(
            context = this,
            onToggleOverlay = { toggleStatsOverlay() }
        )
        val statsLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        )
        val baseMargin = (12 * resources.displayMetrics.density).toInt()
        statsLp.setMargins(baseMargin, baseMargin, baseMargin, baseMargin)
        rootContainer.addView(statsOverlay, statsLp)

        // Apply safe display cutout / system bar padding to HUD overlay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            rootContainer.setOnApplyWindowInsetsListener { _, insets ->
                val topInset: Int
                val rightInset: Int
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val safe = insets.getInsets(
                        WindowInsets.Type.displayCutout()
                    )
                    topInset = safe.top
                    rightInset = safe.right
                } else {
                    @Suppress("DEPRECATION")
                    topInset = insets.systemWindowInsetTop
                    @Suppress("DEPRECATION")
                    rightInset = insets.systemWindowInsetRight
                }
                val lp = statsOverlay.layoutParams as FrameLayout.LayoutParams
                lp.topMargin = baseMargin + topInset
                lp.rightMargin = baseMargin + rightInset
                statsOverlay.layoutParams = lp
                insets
            }
        }

        // Capture primary touches across rootContainer and map strictly over surfaceView.visibleBounds
        rootContainer.setOnTouchListener { _, event ->
            if (waitingOverlay.visibility == View.VISIBLE) {
                return@setOnTouchListener false
            }
            val handled = touchMapper.processMotionEvent(
                event = event,
                bounds = surfaceView.visibleBounds
            ) { action, pointerId, normX, normY ->
                touchCoalescer.enqueue(action, pointerId, normX, normY)
                tetherClient.notifyTouchEnqueued()
            }
            handled || event.actionMasked == MotionEvent.ACTION_DOWN
        }

        setContentView(rootContainer)
        applyFullscreenImmersiveMode()

        videoDecoder = VideoDecoder(
            frameQueue = frameQueue,
            onFirstFrameRendered = {
                mainHandler.post {
                    waitingOverlay.visibility = View.GONE
                }
            },
            onDecoderError = { reason ->
                mainHandler.post {
                    waitingOverlay.visibility = View.VISIBLE
                    waitingOverlay.updateState(TetherClient.ConnectionState.RECONNECTING, reason)
                }
                tetherClient.triggerReconnect(reason)
            }
        )

        tetherClient = TetherClient(
            port = port,
            frameQueue = frameQueue,
            touchCoalescer = touchCoalescer,
            capabilitiesProvider = { decoderCapabilities },
            displayMetricsProvider = { readCurrentDisplayMetrics() },
            listener = clientListener
        )

        surfaceView.holder.addCallback(surfaceCallback)

        displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        displayManager?.registerDisplayListener(displayListener, mainHandler)

        val initialMetrics = readCurrentDisplayMetrics()
        lastReportedWidth = initialMetrics.width
        lastReportedHeight = initialMetrics.height
        lastReportedRotation = initialMetrics.rotationDegrees

        tetherClient.start()
        mainHandler.postDelayed(statsRefreshTask, STATS_REFRESH_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyFullscreenImmersiveMode()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyFullscreenImmersiveMode()
        checkAndSendDisplayChange()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(statsRefreshTask)
        displayManager?.unregisterDisplayListener(displayListener)
        surfaceView.holder.removeCallback(surfaceCallback)
        videoDecoder.stop()
        tetherClient.stop()
        super.onDestroy()
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            activeSurface = holder.surface
            val cfg = currentConfig
            if (cfg != null) {
                startDecoderOnSurface(holder.surface, cfg)
                tetherClient.requestKeyframe()
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            activeSurface = holder.surface
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            activeSurface = null
            videoDecoder.stop()
            touchMapper.reset()
        }
    }

    private val clientListener = object : TetherClient.Listener {
        override fun onStateChanged(state: TetherClient.ConnectionState, detail: String?) {
            mainHandler.post {
                waitingOverlay.updateState(state, detail)
                if (state != TetherClient.ConnectionState.STREAMING) {
                    waitingOverlay.visibility = View.VISIBLE
                }
            }
        }

        override fun onConfigReceived(config: TetherProtocol.Message.Config) {
            currentConfig = config
            mainHandler.post {
                surfaceView.setVideoAspect(config.width, config.height)
                val surf = activeSurface
                if (surf != null && surf.isValid) {
                    startDecoderOnSurface(surf, config)
                }
            }
        }

        override fun onFrameQueued() {
            videoDecoder.notifyFrameAvailable()
        }

        override fun onRttUpdated(rttMs: Float) {
            lastRttMs = rttMs
        }

        override fun onDisconnected(reason: String) {
            currentConfig = null
            videoDecoder.stop()
            touchMapper.reset()
            mainHandler.post {
                waitingOverlay.visibility = View.VISIBLE
                waitingOverlay.updateState(TetherClient.ConnectionState.RECONNECTING, reason)
            }
        }
    }

    private fun startDecoderOnSurface(surface: Surface, config: TetherProtocol.Message.Config) {
        val started = videoDecoder.start(
            surface = surface,
            width = config.width,
            height = config.height,
            targetFps = config.fps,
            preferredDecoderName = decoderCapabilities?.decoderName
        )
        if (!started) {
            Log.e(TAG, "Unable to start VideoDecoder for ${config.width}x${config.height}")
            tetherClient.triggerReconnect("Decoder start failed")
        }
    }

    private fun toggleStatsOverlay() {
        val next = !statsOverlay.isExpanded()
        statsOverlay.setExpanded(next)
        waitingOverlay.updateStatsButtonState(next)
    }

    private fun toggleOrientationLock() {
        autoRotateEnabled = !autoRotateEnabled
        requestedOrientation = if (autoRotateEnabled) {
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        waitingOverlay.updateRotationLabel(autoRotateEnabled)
    }

    private fun checkAndSendDisplayChange() {
        val metrics = readCurrentDisplayMetrics()
        if (metrics.width != lastReportedWidth ||
            metrics.height != lastReportedHeight ||
            metrics.rotationDegrees != lastReportedRotation
        ) {
            lastReportedWidth = metrics.width
            lastReportedHeight = metrics.height
            lastReportedRotation = metrics.rotationDegrees
            tetherClient.sendDisplayChange(metrics)
        }
    }

    private fun readCurrentDisplayMetrics(): TetherClient.DisplayMetricsSnapshot {
        val width: Int
        val height: Int
        val densityDpi = resources.displayMetrics.densityDpi

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            width = dm.widthPixels
            height = dm.heightPixels
        }

        @Suppress("DEPRECATION")
        val rotEnum = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            windowManager.defaultDisplay.rotation
        }

        val rotationDegrees = when (rotEnum) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        return TetherClient.DisplayMetricsSnapshot(
            width = width,
            height = height,
            densityDpi = densityDpi,
            rotationDegrees = rotationDegrees
        )
    }

    private fun applyFullscreenImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }
    }
}
