package com.cheat.eightball

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    companion object {
        const val ACTION_TOGGLE_OVERLAY = "com.cheat.eightball.TOGGLE_OVERLAY"
        const val ACTION_TOGGLE_AUTOAIM = "com.cheat.eightball.TOGGLE_AUTOAIM"
        const val ACTION_TOGGLE_HUD     = "com.cheat.eightball.TOGGLE_HUD"
        const val ACTION_SET_POWER      = "com.cheat.eightball.SET_POWER"
        const val EXTRA_ENABLED         = "enabled"
        const val EXTRA_POWER           = "power"

        private const val CHANNEL_ID          = "cheat_channel"
        private const val NOTIF_ID            = 1
        private const val CAPTURE_INTERVAL_MS = 85L
    }

    private lateinit var windowManager  : WindowManager
    private lateinit var overlayView    : OverlayView
    private lateinit var mediaProjection: MediaProjection
    private lateinit var virtualDisplay : VirtualDisplay
    private lateinit var imageReader    : ImageReader
    private lateinit var ballDetector   : BallDetector
    private lateinit var physicsEngine  : PhysicsEngine
    private lateinit var captureThread  : HandlerThread
    private lateinit var captureHandler : Handler

    // BUG FIX #12: overlayEnabled and autoAimEnabled were plain var — written from
    // BroadcastReceiver (main thread) and read from captureThread. Race condition.
    // Fix: @Volatile. Reads/writes are single-word booleans so volatile is sufficient.
    @Volatile private var overlayEnabled = true
    @Volatile private var autoAimEnabled = false

    private var hudEnabled   = true
    private var screenWidth  = 0
    private var screenHeight = 0

    // BUG FIX #13: BroadcastReceiver must use RECEIVER_NOT_EXPORTED on API 34+.
    // Without this flag, registerReceiver() throws on Android 14.
    // We also need to specify the flag explicitly.
    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TOGGLE_OVERLAY -> {
                    overlayEnabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                    overlayView.setOverlayVisible(overlayEnabled)
                }
                ACTION_TOGGLE_AUTOAIM -> {
                    autoAimEnabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
                }
                ACTION_TOGGLE_HUD -> {
                    hudEnabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                    overlayView.setHudVisible(hudEnabled)
                }
                ACTION_SET_POWER -> {
                    val p = intent.getFloatExtra(EXTRA_POWER, 1.0f).coerceIn(0f, 1f)
                    AutoAimAccessibility.powerMultiplier = p
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: return START_NOT_STICKY
        val data       = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY

        val metrics   = resources.displayMetrics
        screenWidth   = metrics.widthPixels
        screenHeight  = metrics.heightPixels

        ballDetector  = BallDetector(screenWidth, screenHeight)
        physicsEngine = PhysicsEngine(screenWidth, screenHeight)

        AutoAimAccessibility.pullDistancePx = screenWidth * 0.185f

        val projManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projManager.getMediaProjection(resultCode, data)

        captureThread  = HandlerThread("CaptureThread").also { it.start() }
        captureHandler = Handler(captureThread.looper)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "CheatCapture", screenWidth, screenHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, captureHandler
        )

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView   = OverlayView(this)
        val params = WindowManager.LayoutParams(
            screenWidth, screenHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlayView, params)

        val filter = IntentFilter().apply {
            addAction(ACTION_TOGGLE_OVERLAY)
            addAction(ACTION_TOGGLE_AUTOAIM)
            addAction(ACTION_TOGGLE_HUD)
            addAction(ACTION_SET_POWER)
        }
        // BUG FIX #13 applied: RECEIVER_NOT_EXPORTED required on API 34+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(controlReceiver, filter)
        }

        startCaptureLoop()
        return START_STICKY
    }

    private fun startCaptureLoop() {
        captureHandler.post(object : Runnable {
            override fun run() {
                if (overlayEnabled) processSingleFrame()
                captureHandler.postDelayed(this, CAPTURE_INTERVAL_MS)
            }
        })
    }

    private fun processSingleFrame() {
        val t0    = System.currentTimeMillis()
        val image = try { imageReader.acquireLatestImage() } catch (e: Exception) { return } ?: return

        try {
            val plane       = image.planes[0]
            val buffer      = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride   = plane.rowStride
            val rowPadding  = rowStride - pixelStride * screenWidth
            val bitmapW     = screenWidth + rowPadding / pixelStride

            val bitmap = Bitmap.createBitmap(bitmapW, screenHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            val balls    = ballDetector.detect(bitmap)
            bitmap.recycle()

            val detectMs = System.currentTimeMillis() - t0
            val cue      = balls.cueBall ?: return
            if (balls.targetBalls.isEmpty()) return

            val trajectoryResult = physicsEngine.calculateTrajectories(cue, balls.targetBalls, balls.pockets)

            overlayView.post { overlayView.updateData(trajectoryResult, balls, detectMs, autoAimEnabled) }

            if (autoAimEnabled) {
                val aimService = AutoAimAccessibility.instance
                val bestShot   = trajectoryResult.bestShot
                if (aimService != null && bestShot != null) {
                    aimService.performAutoAim(cue, bestShot.ghost)
                }
            }

        } finally {
            image.close()
        }
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(CHANNEL_ID, "System Service", NotificationManager.IMPORTANCE_MIN)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System UI")
            .setContentText("")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(controlReceiver) } catch (_: Exception) {}
        captureThread.quitSafely()
        try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        virtualDisplay.release()
        mediaProjection.stop()
        imageReader.close()
    }
}
