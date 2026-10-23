package com.cheat.eightball

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

// ─── AutoAimAccessibility ─────────────────────────────────────────────────────
// Dispatches a swipe gesture to aim and shoot toward the computed ghost ball.
//
// FIXED: gesture is now aimed at GHOST BALL position, not target center.
// Ghost ball = exact contact point for cue ball. Aiming at target center was
// mathematically wrong — it created a cut angle error of (ball_radius / dist) radians.
//
// Gesture model for 8 Ball Pool (Android):
//   - Touch DOWN at cue ball position
//   - Swipe AWAY from ghost (pull-back model) — game interprets drag as aim direction
//   - Release → game fires in the direction of the pull
//   - pullDist tunable (default 200px, scales with screen density)

class AutoAimAccessibility : AccessibilityService() {

    companion object {
        @Volatile var instance: AutoAimAccessibility? = null

        // Config — readable from OverlayService
        var pullDistancePx : Float = 200f
        var gestureDurationMs: Long = 400L
        var delayAfterShotMs : Long = 800L   // wait before next auto-aim cycle
        var powerMultiplier  : Float = 1.0f  // 0.5 = half power, 1.0 = full pull
    }

    private val gestureInFlight = AtomicBoolean(false)

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { super.onDestroy(); instance = null }

    // ── Auto aim ─────────────────────────────────────────────────────────────
    // aimX/aimY = ghost ball position (NOT target center).
    // Direction vector: cue ball → ghost ball = shot direction.
    // Pull-back: opposite direction × pullDist.

    fun performAutoAim(cueBall: BallPosition, ghost: GhostBall?) {
        ghost ?: return
        if (!gestureInFlight.compareAndSet(false, true)) return

        // Shot direction vector (normalized)
        val dx   = ghost.x - cueBall.x
        val dy   = ghost.y - cueBall.y
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist < 1f) { gestureInFlight.set(false); return }

        val nx = dx / dist
        val ny = dy / dist

        // Pull-back: start at cue center, drag OPPOSITE to shot direction
        val effectivePull = pullDistancePx * powerMultiplier
        val startX = cueBall.x
        val startY = cueBall.y
        val endX   = cueBall.x - nx * effectivePull
        val endY   = cueBall.y - ny * effectivePull

        val gesturePath = Path().apply {
            moveTo(startX, startY)
            lineTo(endX,   endY)
        }

        val stroke  = GestureDescription.StrokeDescription(gesturePath, 0L, gestureDurationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                // Small delay before allowing next gesture — prevents double-fire
                android.os.Handler(mainLooper).postDelayed(
                    { gestureInFlight.set(false) },
                    delayAfterShotMs
                )
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                gestureInFlight.set(false)
            }
        }, null)
    }
}
