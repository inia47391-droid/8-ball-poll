package com.cheat.eightball

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

class OverlayView(context: Context) : View(context) {

    private var trajectoryResult : TrajectoryResult? = null
    private var detectionResult  : DetectionResult?  = null
    private var overlayVisible   = true
    private var hudVisible       = true
    private var frameCount       = 0
    private var fps              = 0f
    private var lastFpsTime      = System.currentTimeMillis()
    private var detectMs         = 0L
    private var ballCount        = 0
    private var autoAimEnabled   = false

    // BUG FIX #10: arrowPaint was allocated inside drawBestShotArrow() — called 12× per
    // second. Paint allocation triggers GC. All paints moved to class-level val fields.
    private val solidLinePaint  = Paint().apply { style = Paint.Style.STROKE; isAntiAlias = true }
    private val dashedLinePaint = Paint().apply {
        style = Paint.Style.STROKE; isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(18f, 10f), 0f)
    }
    private val ghostStrokePaint = Paint().apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2.5f; isAntiAlias = true
    }
    private val ghostFillPaint = Paint().apply {
        style = Paint.Style.FILL; isAntiAlias = true
    }
    private val cuePaint = Paint().apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 3f; isAntiAlias = true
    }
    private val targetFillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val bestRingPaint   = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true
    }
    private val pocketPaint = Paint().apply {
        style = Paint.Style.STROKE; color = Color.argb(160, 255, 255, 0)
        strokeWidth = 3f; isAntiAlias = true
    }
    private val hudBgPaint = Paint().apply {
        style = Paint.Style.FILL; color = Color.argb(170, 0, 0, 0); isAntiAlias = true
    }
    private val hudTextPaint = Paint().apply {
        color = Color.WHITE; textSize = 28f; isAntiAlias = true; typeface = Typeface.MONOSPACE
    }
    private val easyPaint  = makeBadgePaint(Color.rgb(0, 220, 100))
    private val medPaint   = makeBadgePaint(Color.rgb(255, 180, 0))
    private val hardPaint  = makeBadgePaint(Color.rgb(255, 60, 60))
    private val badgeTextP = Paint().apply {
        color = Color.WHITE; textSize = 22f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD
    }
    private val arrowLinePaint = Paint().apply {
        style = Paint.Style.STROKE; color = Color.YELLOW; strokeWidth = 4f; isAntiAlias = true
    }
    private val arrowFillPaint = Paint().apply {
        style = Paint.Style.FILL_AND_STROKE; color = Color.YELLOW; strokeWidth = 2f; isAntiAlias = true
    }

    // BUG FIX #11: Path object allocated fresh every onDraw() call for every line.
    // Reuse a single Path instance, reset() before each use.
    private val reusablePath = Path()

    private fun makeBadgePaint(c: Int) = Paint().apply { style = Paint.Style.FILL; color = c; isAntiAlias = true }

    fun updateData(result: TrajectoryResult, balls: DetectionResult, detectMillis: Long, autoAim: Boolean) {
        trajectoryResult = result
        detectionResult  = balls
        detectMs         = detectMillis
        autoAimEnabled   = autoAim
        ballCount        = balls.targetBalls.size + (if (balls.cueBall != null) 1 else 0)

        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000L) {
            fps = frameCount * 1000f / (now - lastFpsTime)
            frameCount = 0
            lastFpsTime = now
        }
        invalidate()
    }

    fun setOverlayVisible(v: Boolean) { overlayVisible = v; invalidate() }
    fun setHudVisible(v: Boolean)     { hudVisible     = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!overlayVisible) return
        val result = trajectoryResult ?: return
        val balls  = detectionResult  ?: return

        drawPocketHighlights(canvas, balls.pockets)
        drawTrajectoryLines(canvas, result.lines)
        drawGhostBalls(canvas, result.allShots)
        drawBallMarkers(canvas, balls, result.bestShot)
        drawBestShotArrow(canvas, result.bestShot)
        drawDifficultyBadges(canvas, result.allShots)
        if (hudVisible) drawHUD(canvas, result, balls)
    }

    private fun drawPocketHighlights(canvas: Canvas, pockets: List<Pair<Float, Float>>) {
        pockets.forEach { canvas.drawCircle(it.first, it.second, 22f, pocketPaint) }
    }

    private fun drawTrajectoryLines(canvas: Canvas, lines: List<TrajectoryLine>) {
        lines.forEach { line ->
            val paint = if (line.isDashed) dashedLinePaint else solidLinePaint
            paint.color       = line.color
            paint.alpha       = line.alpha
            paint.strokeWidth = line.strokeWidth

            reusablePath.reset()
            line.points.firstOrNull()?.let { reusablePath.moveTo(it.first, it.second) }
            line.points.drop(1).forEach   { reusablePath.lineTo(it.first, it.second) }
            canvas.drawPath(reusablePath, paint)
        }
    }

    private fun drawGhostBalls(canvas: Canvas, shots: List<ShotCandidate>) {
        shots.forEachIndexed { rank, shot ->
            val r = shot.target.radius
            ghostFillPaint.color = Color.argb(if (rank == 0) 70 else 30, 255, 255, 255)
            ghostStrokePaint.alpha = if (rank == 0) 200 else 80
            canvas.drawCircle(shot.ghost.x, shot.ghost.y, r, ghostFillPaint)
            canvas.drawCircle(shot.ghost.x, shot.ghost.y, r, ghostStrokePaint)
        }
    }

    private fun drawBallMarkers(canvas: Canvas, balls: DetectionResult, best: ShotCandidate?) {
        balls.cueBall?.let { canvas.drawCircle(it.x, it.y, it.radius + 5f, cuePaint) }

        balls.targetBalls.forEach { ball ->
            val isBestTarget = best?.target == ball
            targetFillPaint.color = when (ball.colorType) {
                BallType.SOLID  -> Color.argb(110, 255, 200,   0)
                BallType.STRIPE -> Color.argb(110,   0, 200, 255)
                BallType.EIGHT  -> Color.argb(110, 180,   0, 255)
                else            -> Color.argb( 80, 255, 255, 255)
            }
            canvas.drawCircle(ball.x, ball.y, ball.radius + 3f, targetFillPaint)
            if (isBestTarget) {
                bestRingPaint.color = Color.YELLOW
                canvas.drawCircle(ball.x, ball.y, ball.radius + 8f, bestRingPaint)
            }
        }
    }

    private fun drawBestShotArrow(canvas: Canvas, best: ShotCandidate?) {
        best ?: return
        val dx   = best.pocket.first  - best.ghost.x
        val dy   = best.pocket.second - best.ghost.y
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist < 1f) return
        val nx = dx / dist; val ny = dy / dist

        val arrowLen  = 60f
        val arrowHead = 18f
        val ex = best.ghost.x + nx * arrowLen
        val ey = best.ghost.y + ny * arrowLen

        canvas.drawLine(best.ghost.x, best.ghost.y, ex, ey, arrowLinePaint)

        val angle = atan2(dy.toDouble(), dx.toDouble())
        val a1 = angle + Math.PI * 0.75
        val a2 = angle - Math.PI * 0.75
        reusablePath.reset()
        reusablePath.moveTo(ex, ey)
        reusablePath.lineTo(ex + cos(a1).toFloat() * arrowHead, ey + sin(a1).toFloat() * arrowHead)
        reusablePath.lineTo(ex + cos(a2).toFloat() * arrowHead, ey + sin(a2).toFloat() * arrowHead)
        reusablePath.close()
        canvas.drawPath(reusablePath, arrowFillPaint)
    }

    private fun drawDifficultyBadges(canvas: Canvas, shots: List<ShotCandidate>) {
        shots.forEach { shot ->
            val label = when (shot.difficulty) { ShotDifficulty.EASY -> "E"; ShotDifficulty.MEDIUM -> "M"; ShotDifficulty.HARD -> "H" }
            val paint = when (shot.difficulty) { ShotDifficulty.EASY -> easyPaint; ShotDifficulty.MEDIUM -> medPaint; ShotDifficulty.HARD -> hardPaint }
            val bx = shot.target.x + shot.target.radius + 4f
            val by = shot.target.y - shot.target.radius - 4f
            canvas.drawRoundRect(bx, by - 24f, bx + 26f, by + 4f, 6f, 6f, paint)
            canvas.drawText(label, bx + 6f, by, badgeTextP)
        }
    }

    private fun drawHUD(canvas: Canvas, result: TrajectoryResult, balls: DetectionResult) {
        val pad = 16f; val x = pad; val y = height * 0.12f; val w = 260f; val lh = 34f

        val lines = mutableListOf(
            "FPS  : ${fps.toInt()}",
            "BALLS: $ballCount",
            "DET  : ${detectMs}ms",
            "SHOTS: ${result.allShots.size}",
            "AAIM : ${if (autoAimEnabled) "ON" else "OFF"}"
        )
        result.bestShot?.let {
            lines.add("BEST : ${it.difficulty.name}")
            lines.add("SCR  : ${"%.0f".format(it.score)}")
        }

        val hudH = lines.size * lh + pad * 2
        canvas.drawRoundRect(x, y - pad, x + w, y + hudH, 12f, 12f, hudBgPaint)
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, x + pad, y + i * lh + lh * 0.6f, hudTextPaint)
        }
    }
}
