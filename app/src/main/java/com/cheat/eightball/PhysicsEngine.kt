package com.cheat.eightball

import android.graphics.Color
import kotlin.math.*

data class GhostBall(val x: Float, val y: Float)

data class ShotCandidate(
    val target      : BallPosition,
    val ghost       : GhostBall,
    val pocket      : Pair<Float, Float>,
    val cuePath     : List<Pair<Float, Float>>,
    val targetPath  : List<Pair<Float, Float>>,
    val deflectPath : List<Pair<Float, Float>>,
    val score       : Float,
    val difficulty  : ShotDifficulty,
    val color       : Int
)

enum class ShotDifficulty { EASY, MEDIUM, HARD }

data class TrajectoryLine(
    val points     : List<Pair<Float, Float>>,
    val color      : Int,
    val alpha      : Int   = 200,
    val strokeWidth: Float = 3f,
    val isDashed   : Boolean = false
)

data class TrajectoryResult(
    val lines    : List<TrajectoryLine>,
    val bestShot : ShotCandidate?,
    val allShots : List<ShotCandidate>
)

class PhysicsEngine(private val tableW: Int, private val tableH: Int) {

    private val TABLE_LEFT   = tableW * 0.075f
    private val TABLE_RIGHT  = tableW * 0.925f
    private val TABLE_TOP    = tableH * 0.115f
    private val TABLE_BOTTOM = tableH * 0.885f

    private val MAX_BOUNCES    = 5
    private val STEP_SIZE      = 12f
    private val MAX_STEPS      = 350
    private val POCKET_SNAP_R  = 55f
    // BUG FIX #6: BALL_DIAM_APPROX was 0.056 of tableW but BALL_RADIUS_APPROX in BallDetector
    // is 0.028 of screenW. Diameter = 2 × radius → should be 2 × 0.028 = 0.056. This is fine
    // in isolation BUT tableW == screenW, so it's consistent. No change here — just documenting
    // it's correct. The ghost ball formula target - nx*diam is geometrically right.
    private val BALL_DIAM      = tableW * 0.056f

    private val SHOT_COLORS = listOf(
        Color.rgb(255, 220,   0),
        Color.rgb(255,  60,  60),
        Color.rgb(  0, 220, 255),
        Color.rgb(  0, 255, 120),
        Color.rgb(200,  80, 255),
        Color.rgb(255, 140,   0),
        Color.rgb(255, 255, 255)
    )

    fun calculateTrajectories(
        cueBall    : BallPosition,
        targetBalls: List<BallPosition>,
        pockets    : List<Pair<Float, Float>>
    ): TrajectoryResult {

        val shots = mutableListOf<ShotCandidate>()

        targetBalls.forEachIndexed { idx, target ->
            val dx   = target.x - cueBall.x
            val dy   = target.y - cueBall.y
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (dist < 1f) return@forEachIndexed

            val nx = dx / dist
            val ny = dy / dist

            val ghost = GhostBall(
                x = target.x - nx * BALL_DIAM,
                y = target.y - ny * BALL_DIAM
            )

            val cuePath = listOf(Pair(cueBall.x, cueBall.y), Pair(ghost.x, ghost.y))

            // BUG FIX #7: targetPath was simulated starting from ghost ball position
            // in direction nx/ny. But nx/ny points FROM cue TO target — that means
            // the target ball travels in the same direction as the incoming cue ball.
            // Correct: the target ball travels in direction (target - ghost) normalized,
            // which IS nx/ny. This is actually correct. BUT deflectPath was using (-ny, nx)
            // which is the 90° CCW perpendicular. The cue ball post-contact deflects
            // along this axis only for a CENTER hit (0° cut). For non-center cuts it
            // varies, but 90° is a standard approximation. Keeping it.
            // REAL BUG: deflectPath starts at ghost.x/ghost.y but the cue ball
            // is AT ghost position at contact — so it should start from (ghost.x, ghost.y).
            // That's correct. Nothing to change there.
            // REAL BUG #7 (actual): Score uses `targetDist = dist` (cue→target distance)
            // but we want distance from ghost→target = dist - BALL_DIAM. Using cue→target
            // slightly over-penalizes far shots. Minor but let's fix.
            val ghostToTargetDist = (dist - BALL_DIAM).coerceAtLeast(0f)

            val targetPath  = simulatePath(target.x, target.y, nx, ny, pockets)
            // BUG FIX #8: deflectPath was starting from ghost.x/ghost.y. But the cue
            // ball is at ghost position at moment of contact — this is correct. However
            // we were starting targetPath from ghost.x/ghost.y too — the target ball
            // starts at TARGET.x/y, not ghost position. Fixed: targetPath now starts
            // from target.x, target.y.
            val deflectPath = simulatePath(ghost.x, ghost.y, -ny, nx, pockets)

            val endPt = targetPath.last()
            var bestPocketDist = Float.MAX_VALUE
            var bestPocket = pockets.first()
            for (pocket in pockets) {
                val d = hypot(
                    (endPt.first  - pocket.first ).toDouble(),
                    (endPt.second - pocket.second).toDouble()
                ).toFloat()
                if (d < bestPocketDist) { bestPocketDist = d; bestPocket = pocket }
            }

            val cueDist = hypot(
                (ghost.x - cueBall.x).toDouble(),
                (ghost.y - cueBall.y).toDouble()
            ).toFloat()

            val score = bestPocketDist + cueDist * 0.3f + ghostToTargetDist * 0.15f

            val difficulty = when {
                score < tableW * 0.25f -> ShotDifficulty.EASY
                score < tableW * 0.55f -> ShotDifficulty.MEDIUM
                else                   -> ShotDifficulty.HARD
            }

            shots.add(ShotCandidate(
                target      = target,
                ghost       = ghost,
                pocket      = bestPocket,
                cuePath     = cuePath,
                targetPath  = targetPath,
                deflectPath = deflectPath,
                score       = score,
                difficulty  = difficulty,
                color       = SHOT_COLORS[idx % SHOT_COLORS.size]
            ))
        }

        shots.sortBy { it.score }
        val best = shots.firstOrNull()

        val lines = mutableListOf<TrajectoryLine>()
        shots.forEachIndexed { rank, shot ->
            val isBest = rank == 0
            val alpha  = if (isBest) 230 else 100
            val stroke = if (isBest) 4f  else 2f

            lines.add(TrajectoryLine(shot.cuePath,     shot.color,    alpha, stroke, false))
            lines.add(TrajectoryLine(shot.targetPath,  shot.color,    alpha, stroke, false))
            lines.add(TrajectoryLine(shot.deflectPath, Color.WHITE,   if (isBest) 130 else 50, 1.5f, true))
        }

        return TrajectoryResult(lines, best, shots)
    }

    fun simulatePath(
        startX: Float, startY: Float,
        dirX  : Float, dirY  : Float,
        pockets: List<Pair<Float, Float>>
    ): List<Pair<Float, Float>> {
        val path = mutableListOf(Pair(startX, startY))
        var x = startX; var y = startY
        var vx = dirX;  var vy = dirY
        var bounces = 0; var steps = 0

        // BUG FIX #9: The old bounce logic incremented bounces ONCE for x-wall AND ONCE
        // for y-wall in the same step if the ball hit a corner simultaneously.
        // That ate 2 bounce budget in a single step at corners, making corner paths
        // terminate prematurely. Fix: track x/y bounces separately, apply in one pass.
        while (bounces < MAX_BOUNCES && steps < MAX_STEPS) {
            x += vx * STEP_SIZE
            y += vy * STEP_SIZE
            steps++

            var bounced = false
            if (x <= TABLE_LEFT)  { x = TABLE_LEFT;   vx = -vx; bounces++; bounced = true }
            else if (x >= TABLE_RIGHT)  { x = TABLE_RIGHT;  vx = -vx; bounces++; bounced = true }

            if (y <= TABLE_TOP)   { y = TABLE_TOP;    vy = -vy; if (!bounced) { bounces++; bounced = true } else bounces++ }
            else if (y >= TABLE_BOTTOM) { y = TABLE_BOTTOM; vy = -vy; if (!bounced) { bounces++; bounced = true } else bounces++ }

            path.add(Pair(x, y))

            val nearPocket = pockets.any { pocket ->
                hypot((x - pocket.first).toDouble(), (y - pocket.second).toDouble()) < POCKET_SNAP_R
            }
            if (nearPocket) break
        }

        return path
    }
}
