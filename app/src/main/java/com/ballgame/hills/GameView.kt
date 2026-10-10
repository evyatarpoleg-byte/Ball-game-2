package com.ballgame.hills

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

class GameView(context: Context) : View(context) {
    private var world = World()
    private val prefs = context.getSharedPreferences("hills", Context.MODE_PRIVATE)
    private var best = prefs.getInt("best", 0)
    private var running = false
    private var lastNanos = 0L
    private var camX = 0.0
    private var camY = 0.0
    private var camInit = false
    private var overAt = 0L

    private val viewMeters = 36.0   // visible height in metres
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
    fun pauseLoop() { running = false }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        sky.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.rgb(90, 170, 240), Color.rgb(220, 240, 255), Shader.TileMode.CLAMP)
        text.textSize = h / 16f
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (world.over && System.currentTimeMillis() - overAt > 600) {
                    world = World(); camInit = false
                }
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
            val d = world.distance.toInt()
            if (d > best) { best = d; prefs.edit().putInt("best", best).apply() }
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val scale = h / viewMeters
        val halfW = w / scale / 2.0
        val tx = world.x + halfW * 0.35
        val ty = world.y + 3.0
        if (!camInit) { camX = tx; camY = ty; camInit = true }
        val k = 1 - Math.exp(-4.0 * dt)
        camX += (tx - camX) * 1.0.coerceAtMost(k * 3)
        camY += (ty - camY) * k

        fun sx(wx: Double) = (w / 2 + (wx - camX) * scale).toFloat()
        fun sy(wy: Double) = (h / 2 - (wy - camY) * scale).toFloat()

        c.drawRect(0f, 0f, w, h, sky)
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

        // HUD
        text.textAlign = Paint.Align.LEFT
        text.textSize = h / 16f
        c.drawText("${world.distance.toInt()} m", 24f, h / 12f, text)
        text.textSize = h / 24f
        c.drawText("best $best m", 24f, h / 12f + h / 18f, text)
        text.textAlign = Paint.Align.CENTER
        if (!world.launched && !world.holding) {
            c.drawText("Hold to push the ball off the hill", w / 2, h * 0.3f, text)
        } else if (world.launched && world.distance < 60) {
            c.drawText("Hold on downhills = heavier ball = more speed", w / 2, h * 0.2f, text)
        }
        if (world.over) {
            text.textSize = h / 10f
            c.drawText("Stuck at ${world.distance.toInt()} m", w / 2, h * 0.4f, text)
            text.textSize = h / 20f
            c.drawText("Tap to try again", w / 2, h * 0.5f, text)
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
