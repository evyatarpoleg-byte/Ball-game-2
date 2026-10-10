package com.ballgame.hills

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Units are metres, y points up. */
object Phys {
    const val G = 20.0
    const val HEAVY = 2.6          // gravity multiplier while the finger is down (after launch)
    const val RADIUS = 0.8
    const val FRICTION = 0.5       // constant rolling deceleration, m/s^2
    const val DRAG = 0.04          // air/linear drag, 1/s
    const val START_PUSH = 12.0    // m/s^2 forward push during the opening hold
    const val STEP = 1.0 / 240.0
}

/** Endless hills: key points joined by cosine curves, so slope is 0 at every peak and valley. */
class Terrain(seed: Long) {
    private val rnd = Random(seed)
    private val xs = ArrayList<Double>()
    private val ys = ArrayList<Double>()
    private var nextIsPeak = false

    init {
        xs += -40.0; ys += 14.0
        xs += 0.0; ys += 14.0
        xs += 10.0; ys += 14.0
    }

    private fun uniform(a: Double, b: Double) = a + rnd.nextDouble() * (b - a)

    private fun extend(toX: Double) {
        while (xs.last() < toX) {
            val px = xs.last()
            val py = ys.last()
            val d = px / 400.0
            val w = uniform(18.0, 28.0) + min(d * 6.0, 14.0)
            var ny = if (nextIsPeak) py + uniform(5.0, 9.0) + min(d * 3.0, 6.0)
            else py - uniform(8.0, 14.0) - min(d * 3.0, 6.0)
            if (ny > 40.0) ny = 40.0 - uniform(0.0, 3.0)
            if (ny < 0.0) ny = uniform(0.0, 2.0)
            nextIsPeak = !nextIsPeak
            xs += px + w
            ys += ny
        }
    }

    fun height(x: Double): Double {
        extend(x + 80.0)
        var lo = 0
        var hi = xs.size - 1
        while (hi - lo > 1) {
            val m = (lo + hi) / 2
            if (xs[m] <= x) lo = m else hi = m
        }
        val t = ((x - xs[lo]) / (xs[hi] - xs[lo])).coerceIn(0.0, 1.0)
        val c = (1 - cos(PI * t)) / 2
        return ys[lo] + (ys[hi] - ys[lo]) * c
    }

    fun slope(x: Double): Double {
        val e = 0.05
        return (height(x + e) - height(x - e)) / (2 * e)
    }
}

class World(seed: Long = System.nanoTime()) {
    val terrain = Terrain(seed)
    var x = 3.0
    var y = terrain.height(3.0) + Phys.RADIUS
    var vx = 0.0
    var vy = 0.0
    var angle = 0.0
    var grounded = true
    var launched = false
    var holding = false
    var over = false
    var time = 0.0
    private var stillTime = 0.0
    private var accum = 0.0

    val heavy get() = holding && launched
    val distance get() = max(0.0, x - 3.0)
    val speed get() = hypot(vx, vy)

    fun update(frameDt: Double) {
        if (over) return
        accum += min(frameDt, 0.05)
        while (accum >= Phys.STEP) {
            accum -= Phys.STEP
            step(Phys.STEP)
            if (over) return
        }
    }

    private fun step(dt: Double) {
        time += dt
        if (!holding && time > 0.3) launched = true
        if (holding && !launched && x > 8.0) launched = true

        val g = if (heavy) Phys.G * Phys.HEAVY else Phys.G
        if (holding && !launched) vx += Phys.START_PUSH * dt
        vy -= g * dt
        val k = exp(-Phys.DRAG * dt)
        vx *= k
        vy *= k
        x += vx * dt
        y += vy * dt

        val m = terrain.slope(x)
        val inv = 1.0 / sqrt(1 + m * m)
        val nx = -m * inv
        val ny = inv
        val d = (y - terrain.height(x)) * inv
        grounded = false
        if (d < Phys.RADIUS) {
            grounded = true
            x += nx * (Phys.RADIUS - d)
            y += ny * (Phys.RADIUS - d)
            val vn = vx * nx + vy * ny
            if (vn < 0) {
                vx -= vn * nx
                vy -= vn * ny
            }
            val sp = hypot(vx, vy)
            if (sp > 0) {
                val dec = min(Phys.FRICTION * dt, sp)
                vx -= vx / sp * dec
                vy -= vy / sp * dec
            }
            // rolling: spin follows the speed along the surface (tangent = (ny, -nx))
            angle += (vx * ny - vy * nx) / Phys.RADIUS * dt
        } else {
            angle += (vx / Phys.RADIUS) * 0.2 * dt
        }

        stillTime = if (launched && speed < 0.4) stillTime + dt else 0.0
        if (stillTime > 2.0 || x < -5.0) over = true
    }
}
