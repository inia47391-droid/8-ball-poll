package com.cheat.eightball

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.*

data class BallPosition(val x: Float, val y: Float, val radius: Float, val colorType: BallType)
data class DetectionResult(
    val cueBall: BallPosition?,
    val targetBalls: List<BallPosition>,
    val pockets: List<Pair<Float, Float>>
)
enum class BallType { CUE, SOLID, STRIPE, EIGHT, UNKNOWN }

class BallDetector(private val screenW: Int, private val screenH: Int) {

    val BALL_RADIUS_APPROX = screenW * 0.028f

    // BUG FIX #1: CLUSTER_THRESHOLD was a `get()` property — recomputed every single
    // call inside the hot loop (tens of thousands of times per frame). Now a val,
    // computed once at construction time. Zero behavior change, massive GC pressure reduction.
    private val CLUSTER_THRESHOLD = BALL_RADIUS_APPROX * 2.5f

    val pocketPositions = listOf(
        Pair(0.075f * screenW, 0.115f * screenH),
        Pair(0.500f * screenW, 0.075f * screenH),
        Pair(0.925f * screenW, 0.115f * screenH),
        Pair(0.075f * screenW, 0.885f * screenH),
        Pair(0.500f * screenW, 0.925f * screenH),
        Pair(0.925f * screenW, 0.885f * screenH)
    )

    fun detect(bitmap: Bitmap): DetectionResult {
        val step   = 4
        val yStart = (screenH * 0.10).toInt()
        val yEnd   = (screenH * 0.90).toInt()
        val xStart = (screenW * 0.05).toInt()
        val xEnd   = (screenW * 0.95).toInt()

        // BUG FIX #2: bitmap.getPixel() inside a tight loop is extremely slow on Android —
        // it goes through JNI per pixel. Use getPixels() bulk read into an IntArray instead.
        // For a 1080×1920 table region that's ~2M JNI calls → 1 JNI call. ~10x faster detect.
        val bW = bitmap.width
        val bH = bitmap.height
        val pixels = IntArray(bW * bH)
        bitmap.getPixels(pixels, 0, bW, 0, 0, bW, bH)

        val candidates = mutableListOf<Pair<Int, Int>>()

        for (py in yStart..yEnd step step) {
            for (px in xStart..xEnd step step) {
                if (px >= bW || py >= bH) continue
                val pixel = pixels[py * bW + px]
                val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
                if (!isTableColor(r, g, b) && !isPocketOrBorder(r, g, b) && isBallCandidate(r, g, b)) {
                    candidates.add(Pair(px, py))
                }
            }
        }

        val clustered = clusterPoints(candidates)

        val detectedBalls = clustered.mapNotNull { center ->
            if (center.first >= bW || center.second >= bH) return@mapNotNull null
            classifyBall(center.first.toFloat(), center.second.toFloat(), pixels, bW, bH)
        }

        // BUG FIX #3: firstOrNull { CUE } is fine but if there are multiple
        // bright-white clusters (HUD elements, chat bubbles), the FIRST one wins
        // even if it's clearly outside the table. Pick the CUE candidate closest
        // to table center instead.
        val tableCX = screenW * 0.5f
        val tableCY = screenH * 0.5f
        val cue = detectedBalls
            .filter { it.colorType == BallType.CUE }
            .minByOrNull { hypot((it.x - tableCX).toDouble(), (it.y - tableCY).toDouble()) }

        val targets = detectedBalls.filter { it.colorType != BallType.CUE }
        return DetectionResult(cue, targets, pocketPositions)
    }

    // Expanded felt filter: classic green, dark green, teal, blue-green
    private fun isTableColor(r: Int, g: Int, b: Int): Boolean {
        val classicGreen  = r < 80  && g in 90..190  && b in 120..220
        val darkGreen     = r < 60  && g in 80..150  && b < 120
        val teal          = r < 70  && g in 100..180 && b in 150..240
        val blueGreenFelt = r < 50  && g in 70..160  && b in 80..180
        return classicGreen || darkGreen || teal || blueGreenFelt
    }

    private fun isPocketOrBorder(r: Int, g: Int, b: Int): Boolean {
        val brightness  = (r + g + b) / 3
        val pocketBlack = brightness < 35
        val borderBrown = r in 60..130 && g in 30..90 && b < 50
        return pocketBlack || borderBrown
    }

    private fun isBallCandidate(r: Int, g: Int, b: Int): Boolean = (r + g + b) / 3 > 70

    // BUG FIX #4: clusterPoints() was recomputing centroid() on the growing cluster
    // for every single incoming point — O(n²) total. With 5000+ candidate pixels per
    // frame this stalls the capture thread visibly. Use a running-sum centroid instead.
    private fun clusterPoints(points: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        data class RunningCluster(var sumX: Long, var sumY: Long, var count: Int) {
            fun cx() = (sumX / count).toInt()
            fun cy() = (sumY / count).toInt()
            fun add(p: Pair<Int,Int>) { sumX += p.first; sumY += p.second; count++ }
        }
        val clusters = mutableListOf<RunningCluster>()

        for (point in points) {
            var added = false
            for (cluster in clusters) {
                val dx = (point.first  - cluster.cx()).toDouble()
                val dy = (point.second - cluster.cy()).toDouble()
                if (hypot(dx, dy) < CLUSTER_THRESHOLD) {
                    cluster.add(point)
                    added = true
                    break
                }
            }
            if (!added) clusters.add(RunningCluster(point.first.toLong(), point.second.toLong(), 1))
        }

        return clusters
            .filter { it.count in 4..120 }
            .map { Pair(it.cx(), it.cy()) }
    }

    // 5-point cross classification using pre-fetched pixel array (no JNI per pixel)
    private fun classifyBall(cx: Float, cy: Float, pixels: IntArray, bW: Int, bH: Int): BallPosition {
        val off    = (BALL_RADIUS_APPROX * 0.4f).toInt()
        val cPixel = safePixelArray(pixels, cx.toInt(), cy.toInt(), bW, bH)

        val ringPixels = listOf(
            safePixelArray(pixels, cx.toInt() + off, cy.toInt(), bW, bH),
            safePixelArray(pixels, cx.toInt() - off, cy.toInt(), bW, bH),
            safePixelArray(pixels, cx.toInt(), cy.toInt() + off, bW, bH),
            safePixelArray(pixels, cx.toInt(), cy.toInt() - off, bW, bH)
        )

        val cr = Color.red(cPixel); val cg = Color.green(cPixel); val cb = Color.blue(cPixel)
        val cBright = (cr + cg + cb) / 3

        val coloredRingCount = ringPixels.count { px ->
            val rr = Color.red(px); val gg = Color.green(px); val bb = Color.blue(px)
            val br = (rr + gg + bb) / 3
            val maxC = maxOf(rr, gg, bb); val minC = minOf(rr, gg, bb)
            val sat = if (maxC > 0) (maxC - minC).toFloat() / maxC else 0f
            br in 50..220 && sat > 0.30f
        }

        // BUG FIX #5: CUE check was only on center pixel — a stripe ball with white center
        // could pass as CUE. Now require ring pixels also bright to confirm true cue ball.
        val ringBrightCount = ringPixels.count { px ->
            val rr = Color.red(px); val gg = Color.green(px); val bb = Color.blue(px)
            rr > 190 && gg > 190 && bb > 190
        }

        val type = when {
            cr > 210 && cg > 210 && cb > 210 && ringBrightCount >= 3 -> BallType.CUE
            cr < 40  && cg < 40  && cb < 40  -> BallType.EIGHT
            cBright > 180 && coloredRingCount >= 2                    -> BallType.STRIPE
            cr > 170 && cg < 90  && cb < 90                          -> BallType.SOLID  // red
            cr > 200 && cg > 120 && cb < 60                          -> BallType.SOLID  // orange/yellow
            cr < 80  && cg < 80  && cb > 160                         -> BallType.SOLID  // blue
            cr > 100 && cg < 60  && cb > 100                         -> BallType.SOLID  // purple
            cr < 60  && cg > 100 && cb < 60                          -> BallType.SOLID  // green
            cr > 130 && cg in 60..140 && cb < 60                     -> BallType.SOLID  // maroon
            else -> BallType.UNKNOWN
        }

        return BallPosition(cx, cy, BALL_RADIUS_APPROX, type)
    }

    private fun safePixelArray(pixels: IntArray, x: Int, y: Int, bW: Int, bH: Int): Int {
        val cx = x.coerceIn(0, bW - 1)
        val cy = y.coerceIn(0, bH - 1)
        return pixels[cy * bW + cx]
    }
}
