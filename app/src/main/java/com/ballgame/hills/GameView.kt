package com.ballgame.hills

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Per biome: sky top, sky bottom, far ridge, near ridge, grass band, rock, grass edge. */
private val PALETTES = arrayOf(
    intArrayOf(rgb(90, 170, 240), rgb(220, 240, 255), rgb(150, 170, 205), rgb(110, 135, 175), rgb(74, 124, 70), rgb(92, 78, 66), rgb(140, 200, 100)),
    intArrayOf(rgb(150, 190, 230), rgb(235, 245, 255), rgb(200, 215, 235), rgb(170, 190, 215), rgb(235, 240, 248), rgb(120, 130, 150), rgb(255, 255, 255)),
    intArrayOf(rgb(240, 170, 90), rgb(255, 230, 170), rgb(225, 170, 120), rgb(200, 140, 90), rgb(225, 190, 110), rgb(170, 120, 70), rgb(245, 215, 140)),
    intArrayOf(rgb(10, 15, 50), rgb(50, 60, 110), rgb(40, 50, 95), rgb(30, 40, 80), rgb(40, 80, 70), rgb(45, 40, 55), rgb(90, 150, 120)),
)

private fun rgb(r: Int, g: Int, b: Int) = Color.rgb(r, g, b)

private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
    (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
    (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
    (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
)

class GameView(context: Context) : View(context) {
    private var world = World()
    private val prefs = context.getSharedPreferences("hills", Context.MODE_PRIVATE)
    private var best = prefs.getInt("best", 0)            // longest distance, m
    private var bestHeight = prefs.getInt("bestHeight", 0) // highest jump above the ground, m
    private var totalCoins = prefs.getInt("coins", 0)
    private var startBest = best
    private var startBestHeight = bestHeight
    private var shownDistRecord = false
    private var shownHeightRecord = false
    private var lastMilestone = 0
    private val toasts = ArrayList<Toast>()
    private var lastBiome = 0
    private var nightAmount = 0f
    private class Toast(val text: String, val big: Boolean, val until: Long)
    private val cheers = arrayOf("Keep going!", "Nice rolling!", "You're on fire!", "Unstoppable!", "Smooth ride!")
    private var running = false
    private var lastNanos = 0L
    private var camX = 0.0
    private var camY = 0.0
    private var camInit = false
    private var overAt = 0L

    private val baseMeters = 36.0   // visible height in metres at ground level
    private var viewMeters = baseMeters
    private var altSmooth = 0.0
    private var restartRect = RectF()
    private val sky = Paint()
    private val far = Paint().apply { color = Color.rgb(150, 170, 205); isAntiAlias = true }
    private val mid = Paint().apply { color = Color.rgb(110, 135, 175); isAntiAlias = true }
    private val ground = Paint().apply { color = Color.rgb(74, 124, 70); isAntiAlias = true }
    private val rock = Paint().apply { color = Color.rgb(92, 78, 66) }
    private val edge = Paint().apply {
        color = Color.rgb(140, 200, 100); style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true
    }
    private val ballPaint = Paint().apply { isAntiAlias = true }
    private val mark = Paint().apply { color = Color.WHITE; strokeWidth = 6f; isAntiAlias = true }
    private val text = Paint().apply { color = Color.WHITE; isAntiAlias = true; setShadowLayer(4f, 0f, 2f, Color.argb(160, 0, 0, 0)) }
    private val path = Path()

    fun resumeLoop() { running = true; lastNanos = 0L; postInvalidateOnAnimation() }
    fun pauseLoop() { running = false; saveRecords() }

    private fun saveRecords() {
        prefs.edit().putInt("best", best).putInt("bestHeight", bestHeight)
            .putInt("coins", totalCoins + world.coinsCollected).apply()
    }

    private fun newRun() {
        saveRecords()
        totalCoins += world.coinsCollected
        world = World(); camInit = false
        startBest = best; startBestHeight = bestHeight
        shownDistRecord = false; shownHeightRecord = false
        lastMilestone = 0
        lastBiome = 0
        toasts.clear()
        viewMeters = baseMeters
        altSmooth = 0.0
    }

    private fun toast(text: String, big: Boolean = false) {
        toasts.add(Toast(text, big, System.currentTimeMillis() + if (big) 5000 else 3000))
        if (toasts.size > 3) toasts.removeAt(0)
    }

    private fun updateRecords() {
        val d = world.distance.toInt()
        if (d > best) {
            best = d
            if (startBest > 0 && !shownDistRecord) { shownDistRecord = true; toast("New distance record!") }
        }
        if (!world.grounded) {
            val hgt = (world.y - Phys.RADIUS - world.terrain.height(world.x)).toInt()
            if (hgt > bestHeight) {
                bestHeight = hgt
                if (startBestHeight > 0 && !shownHeightRecord) { shownHeightRecord = true; toast("New height record!") }
            }
        }
        while (d >= (lastMilestone + 1) * 1000) {
            lastMilestone++
            if (lastMilestone % 10 == 0) toast("CONGRATULATIONS! ${lastMilestone} km!", big = true)
            else toast("${lastMilestone * 1000} m - ${cheers[lastMilestone % cheers.size]}")
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        sky.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.rgb(90, 170, 240), Color.rgb(220, 240, 255), Shader.TileMode.CLAMP)
        text.textSize = h / 16f
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (restartRect.contains(e.x, e.y)) {
                    newRun()
                    return true
                }
                if (world.over && System.currentTimeMillis() - overAt > 600) newRun()
                world.holding = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> world.holding = false
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.0 else (now - lastNanos) / 1e9
        lastNanos = now
        val wasOver = world.over
        world.update(dt)
        if (world.over && !wasOver) {
            overAt = System.currentTimeMillis()
            saveRecords()
        }
        if (!world.over) updateRecords()
        for (ev in world.events) {
            if (ev == Ev.PERFECT) toast("PERFECT landing!  x${min(1 + world.combo, 5)} coins")
            if (ev == Ev.ROCK) toast("Rock! Slowed down - heavy mode hurts less")
        }
        world.events.clear()

        val w = width.toFloat()
        val h = height.toFloat()
        // Zoom out so that both the ball and the ground below it stay on screen.
        val groundY = world.terrain.height(world.x)
        val altTotal = (world.y - groundY).coerceAtLeast(0.0)
        // Smoothed altitude (rises fast, falls slowly) so the zoom doesn't pump as the ground under the ball changes.
        val altRate = if (altTotal > altSmooth) 5.0 else 0.7
        altSmooth += (altTotal - altSmooth) * (1 - Math.exp(-altRate * dt))
        val need = max(baseMeters, (altSmooth + 24.0) * 1.6)
        val zoomRate = if (need > viewMeters) 4.0 else 0.8
        viewMeters += (need - viewMeters) * (1 - Math.exp(-zoomRate * dt))
        // Safety net only: never let the ground leave the screen.
        viewMeters = max(viewMeters, (altTotal + 14.0) * 1.15)
        val scale = (h / viewMeters).toFloat()
        val halfW = w / scale / 2.0
        val tx = world.x + halfW * 0.35
        val ty = world.y + 3.0 - max(0.0, altSmooth - 10.0) * 0.5
        if (!camInit) { camX = tx; camY = ty; camInit = true }
        val k = 1 - Math.exp(-4.0 * dt)
        camX += (tx - camX) * 1.0.coerceAtMost(k * 3)
        camY += (ty - camY) * k
        camY = camY.coerceIn(world.y - viewMeters / 2 + 3, minOf(world.y + viewMeters / 2 - 3, groundY + viewMeters / 2 - 1))

        fun sx(wx: Double) = (w / 2 + (wx - camX) * scale).toFloat()
        fun sy(wy: Double) = (h / 2 - (wy - camY) * scale).toFloat()

        // biome palette, blended over the last 150 m of each biome
        val bi = Biomes.index(world.x)
        val inBiome = world.x - floor(world.x / Biomes.LEN) * Biomes.LEN
        val bt = ((inBiome - (Biomes.LEN - 150.0)) / 150.0).coerceIn(0.0, 1.0).toFloat()
        val pa = PALETTES[bi]
        val pb = PALETTES[(bi + 1) % 4]
        fun pc(i: Int) = mix(pa[i], pb[i], bt)
        sky.shader = LinearGradient(0f, 0f, 0f, h, pc(0), pc(1), Shader.TileMode.CLAMP)
        far.color = pc(2); mid.color = pc(3); ground.color = pc(4); rock.color = pc(5); edge.color = pc(6)
        nightAmount = (if (bi == 3) 1f - bt else 0f) + (if ((bi + 1) % 4 == 3) bt else 0f)
        if (bi != lastBiome) { lastBiome = bi; toast("Entering: ${Biomes.names[bi]}") }

        c.drawRect(0f, 0f, w, h, sky)
        if (nightAmount > 0.01f) {
            mark.color = Color.argb((220 * nightAmount).toInt(), 255, 255, 230)
            for (i in 0 until 50) {
                val sxp = ((i * 7919) % 1000) / 1000f * w
                val syp = ((i * 104729) % 1000) / 1000f * h * 0.6f
                c.drawCircle(sxp, syp, 2f + (i % 3), mark)
            }
            mark.color = Color.WHITE
        }
        ridge(c, far, 0.15, 55.0, 14.0, 0.011, h, w)
        ridge(c, mid, 0.3, 38.0, 9.0, 0.019, h, w)

        // terrain
        val step = 6f
        path.reset()
        path.moveTo(0f, h + 10f)
        var px = 0f
        while (px <= w + step) {
            val wx = camX + (px - w / 2) / scale
            path.lineTo(px, sy(world.terrain.height(wx)))
            px += step
        }
        path.lineTo(w + step, h + 10f)
        path.close()
        c.drawPath(path, rock)
        c.save()
        c.clipPath(path)
        // grass band: same outline drawn thick, clipped to the terrain
        ground.style = Paint.Style.STROKE; ground.strokeWidth = scale * 2.5f
        path.reset()
        px = 0f
        var first = true
        while (px <= w + step) {
            val wx = camX + (px - w / 2) / scale
            val yy = sy(world.terrain.height(wx)) + scale * 1.0f
            if (first) { path.moveTo(px, yy); first = false } else path.lineTo(px, yy)
            px += step
        }
        c.drawPath(path, ground)
        c.restore()
        edge.strokeWidth = scale * 0.25f
        path.reset(); px = 0f; first = true
        while (px <= w + step) {
            val wx = camX + (px - w / 2) / scale
            val yy = sy(world.terrain.height(wx))
            if (first) { path.moveTo(px, yy); first = false } else path.lineTo(px, yy)
            px += step
        }
        c.drawPath(path, edge)

        // milestone flags every 1000 m
        val halfView = w / 2 / scale
        // rocks
        for (rock in world.rocksIn(camX - halfView - 3, camX + halfView + 3)) {
            if (rock.broken) continue
            ballPaint.color = Color.rgb(110, 110, 120)
            c.drawCircle(sx(rock.x), sy(rock.y), (rock.r * scale).toFloat(), ballPaint)
            ballPaint.color = Color.rgb(150, 150, 160)
            c.drawCircle(sx(rock.x) - (rock.r * scale * 0.25).toFloat(), sy(rock.y) - (rock.r * scale * 0.25).toFloat(), (rock.r * scale * 0.45).toFloat(), ballPaint)
        }
        // coins
        for (coin in world.coinsIn(camX - halfView - 2, camX + halfView + 2)) {
            if (coin.taken) continue
            val star = coin.value > 1
            val cr = (if (star) 1.3 else 0.8) * scale
            ballPaint.color = if (star) Color.rgb(255, 140, 30) else Color.rgb(255, 205, 40)
            c.drawCircle(sx(coin.x), sy(coin.y), cr.toFloat(), ballPaint)
            ballPaint.color = if (star) Color.rgb(255, 230, 120) else Color.rgb(255, 238, 150)
            c.drawCircle(sx(coin.x), sy(coin.y), (cr * 0.6).toFloat(), ballPaint)
        }
        var kFlag = max(1, ceil((camX - halfView - 3.0) / 1000.0).toInt())
        while (3.0 + kFlag * 1000.0 <= camX + halfView) {
            val fx = 3.0 + kFlag * 1000.0
            val big = kFlag % 10 == 0
            val gy = world.terrain.height(fx)
            val poleH = if (big) 10.0 else 6.0
            mark.strokeWidth = scale * 0.25f
            c.drawLine(sx(fx), sy(gy), sx(fx), sy(gy + poleH), mark)
            ballPaint.color = if (big) Color.rgb(220, 40, 40) else Color.rgb(250, 200, 40)
            path.reset()
            path.moveTo(sx(fx), sy(gy + poleH))
            path.lineTo(sx(fx + poleH * 0.6), sy(gy + poleH * 0.8))
            path.lineTo(sx(fx), sy(gy + poleH * 0.6))
            path.close()
            c.drawPath(path, ballPaint)
            kFlag++
        }
        mark.strokeWidth = 6f

        // ball
        val r = (Phys.RADIUS * scale).toFloat()
        val bx = sx(world.x)
        val by = sy(world.y)
        val heavy = world.heavy
        if (heavy) {
            ballPaint.color = Color.argb(70, 255, 60, 40)
            c.drawCircle(bx, by, r * 1.6f, ballPaint)
        }
        ballPaint.color = if (heavy) Color.rgb(200, 40, 30) else Color.rgb(250, 190, 40)
        c.drawCircle(bx, by, r * if (heavy) 1.15f else 1f, ballPaint)
        val a = world.angle
        c.drawLine(bx, by, bx + (cos(a) * r * 0.85).toFloat(), by + (sin(a) * r * 0.85).toFloat(), mark)

        // weight meter
        val mw = w * 0.3f
        val mx = (w - mw) / 2
        val my = h - h / 14f
        val mh = h / 40f
        ballPaint.color = Color.argb(120, 0, 0, 0)
        c.drawRoundRect(mx - 3f, my - 3f, mx + mw + 3f, my + mh + 3f, mh, mh, ballPaint)
        val wt = world.weight.toFloat()
        ballPaint.color = Color.rgb((255 * (1 - wt)).toInt().coerceIn(60, 255), (200 * wt).toInt().coerceIn(40, 200), 50)
        if (wt > 0f) c.drawRoundRect(mx, my, mx + mw * wt, my + mh, mh / 2, mh / 2, ballPaint)

        // restart button
        val bs = h / 9f
        restartRect.set(w - bs - 16f, 16f, w - 16f, 16f + bs)
        ballPaint.color = Color.argb(150, 0, 0, 0)
        c.drawRoundRect(restartRect, bs / 4, bs / 4, ballPaint)
        text.textAlign = Paint.Align.CENTER
        text.textSize = bs * 0.7f
        c.drawText("\u21BB", restartRect.centerX(), restartRect.centerY() + bs * 0.25f, text)

        // HUD
        text.textAlign = Paint.Align.LEFT
        text.textSize = h / 16f
        c.drawText("${world.distance.toInt()} m" + if (world.combo > 0) "   combo x${world.combo}" else "", 24f, h / 12f, text)
        text.textSize = h / 24f
        c.drawText("best $best m   highest $bestHeight m", 24f, h / 12f + h / 18f, text)
        c.drawText("coins ${world.coinsCollected}   total ${totalCoins + world.coinsCollected}", 24f, h / 12f + h / 9f, text)
        text.textAlign = Paint.Align.CENTER
        val nowMs = System.currentTimeMillis()
        toasts.removeAll { it.until < nowMs }
        var ty0 = h * 0.3f
        for (t in toasts) {
            text.textSize = if (t.big) h / 9f else h / 14f
            text.color = if (t.big) Color.rgb(255, 215, 60) else Color.WHITE
            c.drawText(t.text, w / 2, ty0, text)
            ty0 += text.textSize * 1.2f
        }
        text.color = Color.WHITE
        text.textSize = h / 24f
        if (!world.launched && !world.holding) {
            c.drawText("Hold to push the ball off the hill", w / 2, h * 0.2f, text)
        } else if (world.launched && world.distance < 60) {
            c.drawText("Hold on downhills = heavier ball = more speed", w / 2, h * 0.2f, text)
        }
        if (world.over) {
            text.textSize = h / 10f
            c.drawText("Stuck at ${world.distance.toInt()} m", w / 2, h * 0.55f, text)
            text.textSize = h / 20f
            c.drawText("Tap to try again", w / 2, h * 0.65f, text)
        }

        if (running) postInvalidateOnAnimation()
    }

    private fun ridge(c: Canvas, p: Paint, parallax: Double, base: Double, amp: Double, freq: Double, h: Float, w: Float) {
        val scale = h / viewMeters
        path.reset()
        path.moveTo(0f, h)
        var px = 0f
        while (px <= w + 10) {
            val wx = (camX * parallax) + (px - w / 2) / scale
            val v = base + amp * (sin(wx * freq * 6) * 0.6 + sin(wx * freq * 14 + 1.3) * 0.4)
            val yy = h / 2 - ((v - camY * parallax * 0.4 - 25) * scale).toFloat() + h * 0.0f
            path.lineTo(px, yy.coerceAtMost(h))
            px += 10f
        }
        path.lineTo(w + 10, h)
        path.close()
        c.drawPath(path, p)
    }
}
