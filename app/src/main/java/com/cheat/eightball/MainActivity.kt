package com.cheat.eightball

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

// ─── MainActivity ─────────────────────────────────────────────────────────────
// Control panel UI:
//   • Start / Stop service
//   • Toggle overlay visibility
//   • Toggle HUD
//   • Toggle auto-aim
//   • Power slider (0–100% pull distance)

class MainActivity : AppCompatActivity() {

    private val OVERLAY_PERMISSION_REQ = 1001
    private val SCREEN_CAPTURE_REQ     = 1002
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private var serviceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val btnStart      = findViewById<Button>(R.id.btnStart)
        val swOverlay     = findViewById<Switch>(R.id.switchOverlay)
        val swHud         = findViewById<Switch>(R.id.switchHud)
        val swAutoAim     = findViewById<Switch>(R.id.switchAutoAim)
        val seekPower     = findViewById<SeekBar>(R.id.seekPower)
        val tvPower       = findViewById<TextView>(R.id.tvPower)
        val tvAccessibility = findViewById<TextView>(R.id.tvAccessibility)

        // Default states
        swOverlay.isChecked  = true
        swHud.isChecked      = true
        swAutoAim.isChecked  = false
        seekPower.progress   = 100
        tvPower.text         = "Power: 100%"

        // ── Start button ──────────────────────────────────────────────────────
        btnStart.setOnClickListener {
            if (serviceRunning) {
                stopService(Intent(this, OverlayService::class.java))
                btnStart.text  = "START"
                serviceRunning = false
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                        OVERLAY_PERMISSION_REQ
                    )
                } else {
                    requestScreenCapture()
                }
            }
        }

        // ── Overlay toggle ────────────────────────────────────────────────────
        swOverlay.setOnCheckedChangeListener { _, checked ->
            sendBroadcast(Intent(OverlayService.ACTION_TOGGLE_OVERLAY)
                .putExtra(OverlayService.EXTRA_ENABLED, checked))
        }

        // ── HUD toggle ────────────────────────────────────────────────────────
        swHud.setOnCheckedChangeListener { _, checked ->
            sendBroadcast(Intent(OverlayService.ACTION_TOGGLE_HUD)
                .putExtra(OverlayService.EXTRA_ENABLED, checked))
        }

        // ── Auto-aim toggle ───────────────────────────────────────────────────
        swAutoAim.setOnCheckedChangeListener { _, checked ->
            if (checked && AutoAimAccessibility.instance == null) {
                Toast.makeText(this, "Enable Accessibility Service first!", Toast.LENGTH_LONG).show()
                swAutoAim.isChecked = false
                return@setOnCheckedChangeListener
            }
            sendBroadcast(Intent(OverlayService.ACTION_TOGGLE_AUTOAIM)
                .putExtra(OverlayService.EXTRA_ENABLED, checked))
        }

        // ── Power slider ──────────────────────────────────────────────────────
        seekPower.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvPower.text = "Power: $progress%"
                sendBroadcast(Intent(OverlayService.ACTION_SET_POWER)
                    .putExtra(OverlayService.EXTRA_POWER, progress / 100f))
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // ── Accessibility hint ────────────────────────────────────────────────
        tvAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun requestScreenCapture() {
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            SCREEN_CAPTURE_REQ
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            OVERLAY_PERMISSION_REQ -> {
                if (Settings.canDrawOverlays(this)) requestScreenCapture()
                else Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show()
            }
            SCREEN_CAPTURE_REQ -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val svcIntent = Intent(this, OverlayService::class.java)
                    svcIntent.putExtra("resultCode", resultCode)
                    svcIntent.putExtra("data", data)
                    startForegroundService(svcIntent)
                    serviceRunning = true
                    findViewById<Button>(R.id.btnStart).text = "STOP"
                    Toast.makeText(this, "Cheat aktif!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
