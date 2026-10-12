package com.ballgame.hills

import java.util.Random
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Plays a [World] like a person would: it only ever presses or releases "hold".
 * level 0 = easy, 1 = medium, 2 = hard. Harder bots react faster, make fewer mistakes,
 * look ahead to pick a good landing, dodge boulders more often and recover better from stalls.
 */
class Bot(private val world: World, val level: Int, seed: Long) {
    private val rnd = Random(seed xor 0x5DEECE66DL)
    private var t = 0.0
    private var nextDecision = 0.0

    private val delay = doubleArrayOf(0.35, 0.2, 0.05)[level]    // seconds between decisions
    private val miss = doubleArrayOf(0.15, 0.06, 0.01)[level]   // chance a decision is flipped
    private val look = level >= 1                                // plans its landings

    init {
        world.assist = doubleArrayOf(16.0, 12.0, 8.0)[level]
        world.boulderAvoid = doubleArrayOf(0.1, 0.5, 0.85)[level]
    }

    fun update(dt: Double) {
        if (world.over) {
            world.holding = false
            return
        }
        t += dt
        if (t < nextDecision) return
        nextDecision = t + delay
        var want = decide()
        if (rnd.nextDouble() < miss) want = !want
        world.holding = want
    }

    private fun decide(): Boolean {
        val w = world
        if (!w.launched) return true // opening push
        val near = w.y - w.terrain.height(w.x) < 2.5
        // On the ground: heavy on the way down, light on the way up.
        if (near) return w.terrain.slope(w.x) < -0.03 && w.weight > 0.03
        // In the air: simple bots stay light, others pick whichever gravity gives the better landing.
        if (!look) return false
        return w.weight > 0.1 && landingBadness(true) < landingBadness(false)
    }

    /** How bad the next touchdown is if we keep this gravity: impact on the slope, plus a penalty for landing uphill. */
    private fun landingBadness(heavy: Boolean): Double {
        val w = world
        var x = w.x
        var y = w.y
        var vx = w.vx
        var vy = w.vy
        val g = if (heavy) Phys.G * Phys.HEAVY else Phys.G
        val dt = 1.0 / 60.0
        val k = exp(-Phys.DRAG * dt)
        for (i in 0 until 300) {
            vy -= g * dt
            vx *= k
            vy *= k
            x += vx * dt
            y += vy * dt
            val m = w.terrain.slope(x)
            val inv = 1.0 / sqrt(1 + m * m)
            if ((y - w.terrain.height(x)) * inv < Phys.RADIUS) {
                val into = -(vx * (-m * inv) + vy * inv)
                val sp = max(hypot(vx, vy), 1e-6)
                return into / sp + (if (m < -0.1) 0.0 else 0.25)
            }
        }
        return 0.0
    }
}
