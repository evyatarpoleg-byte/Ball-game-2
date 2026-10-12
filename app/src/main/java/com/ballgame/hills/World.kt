package com.ballgame.hills

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Units are metres, y points up. */
object Phys {
    const val G = 20.0
    const val HEAVY = 4.0          // gravity multiplier while the finger is down (after launch)
    const val RADIUS = 0.8
    const val FRICTION = 0.35      // constant rolling deceleration, m/s^2
    const val DRAG = 0.03          // air/linear drag, 1/s
    const val START_PUSH = 12.0    // m/s^2 forward push during the opening hold
    const val STEP = 1.0 / 240.0
    const val WEIGHT_SECONDS = 5.0 // how long a full meter lasts while heavy
    const val WEIGHT_REGEN = 0.5   // meter refill per second while the finger is up
}

/** The world changes every 1000 m: meadow -> snow (slippery) -> desert (sticky) -> night -> ... */
object Biomes {
    const val LEN = 1000.0
    val names = arrayOf("Meadow", "Snow", "Desert", "Night")
    fun index(x: Double): Int = ((floor(x / LEN).toInt() % 4) + 4) % 4
    fun friction(i: Int): Double = when (i) { 1 -> 0.2; 2 -> 4.5; else -> 1.0 }
}

/** How the hills look in one biome. [k] blends in pointed peaks (valleys always stay smooth). */
private class Shape(
    val wLo: Double, val wHi: Double,
    val riseLo: Double, val riseHi: Double,
    val fallLo: Double, val fallHi: Double,
    val k: Double, val cap: Double,
)

private val SHAPES = arrayOf(
    Shape(18.0, 28.0, 5.0, 9.0, 8.0, 14.0, 0.0, 40.0),   // Meadow: round, medium hills
    Shape(16.0, 24.0, 6.0, 10.0, 10.0, 15.0, 0.3, 60.0), // Snow: taller, steeper, pointier
    Shape(26.0, 40.0, 6.0, 10.0, 7.0, 11.0, 0.0, 25.0),  // Desert: long, low dunes
    Shape(15.0, 22.0, 5.0, 9.0, 8.0, 13.0, 0.4, 50.0),   // Night: narrow spiky peaks
)

/**
 * Endless hills: key points joined by smooth curves. Valleys are always smooth (slope 0);
 * with a pointed biome the peaks get sharper, which throws the ball off them.
 */
class Terrain(seed: Long) {
    private val rnd = Random(seed)
    private val xs = ArrayList<Double>()
    private val ys = ArrayList<Double>()
    private val ks = ArrayList<Double>()
    private var nextIsPeak = false

    init {
        // Flat start, then a gentle downhill so the ball always gets going.
        xs += -40.0; ys += 14.0; ks += 0.0
        xs += 0.0; ys += 14.0; ks += 0.0
        xs += 8.0; ys += 12.0; ks += 0.0
    }

    private fun uniform(a: Double, b: Double) = a + rnd.nextDouble() * (b - a)

    private fun extend(toX: Double) {
        while (xs.last() < toX) {
            val px = xs.last()
            val py = ys.last()
            val sh = SHAPES[Biomes.index(px)]
            val d = px / 400.0
            val w = uniform(sh.wLo, sh.wHi) + min(d * 6.0, 14.0)
            var ny = if (nextIsPeak) py + uniform(sh.riseLo, sh.riseHi) + min(d * 3.0, 6.0)
            else py - uniform(sh.fallLo, sh.fallHi) - min(d * 3.0, 6.0)
            if (ny > sh.cap) ny = sh.cap - uniform(0.0, 3.0)
            if (ny < 0.0) ny = uniform(0.0, 2.0)
            nextIsPeak = !nextIsPeak
            xs += px + w
            ys += ny
            ks += sh.k
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
        val k = ks[hi]
        val smooth = (1 - cos(PI * t)) / 2
        val c = if (k == 0.0) smooth
        else (1 - k) * smooth + k * (if (ys[hi] > ys[lo]) t * t else 1 - (1 - t) * (1 - t))
        return ys[lo] + (ys[hi] - ys[lo]) * c
    }

    fun slope(x: Double): Double {
        val e = 0.05
        return (height(x + e) - height(x - e)) / (2 * e)
    }

    /** First crest (slope turns from up to down) in [xa, xb], or null. */
    fun peakIn(xa: Double, xb: Double): Double? {
        var prev = slope(xa)
        var x = xa + 0.5
        while (x <= xb) {
            val s = slope(x)
            if (prev > 0 && s <= 0) return x
            prev = s
            x += 0.5
        }
        return null
    }

    /** First valley bottom (slope turns from down to up) in [xa, xb], or null. */
    fun valleyIn(xa: Double, xb: Double): Double? {
        var prev = slope(xa)
        var x = xa + 0.5
        while (x <= xb) {
            val s = slope(x)
            if (prev < 0 && s >= 0) return x
            prev = s
            x += 0.5
        }
        return null
    }
}

class Coin(val x: Double, val y: Double, val value: Int) {
    var taken = false
}

/** A big boulder in a valley bottom. Hitting it costs most of your speed; fly over it instead. */
class Boulder(val x: Double, val y: Double, val r: Double) {
    var broken = false
    var warned = false
}

/** Things the UI should react to (sounds, haptics, toasts). */
object Ev {
    const val COIN = 1
    const val PERFECT = 2
    const val HARD = 3
    const val SOFT = 4
    const val BOULDER = 5
}

/** Race length for the versus modes, in metres. */
const val RACE_LENGTH = 5000.0

object Loss {
    const val BACK = 1   // rolled backwards
    const val STUCK = 2  // stopped moving
}

/**
 * One ball in one world. Two Worlds built with the same seed have identical hills, boulders and
 * coins, so versus modes just run one World per ball. [race] switches on the finish line and the
 * "rolling backwards loses" rule and removes the coins.
 */
class World(private val seed: Long = System.nanoTime(), val race: Boolean = false) {
    val terrain = Terrain(seed)
    var boulderAvoid = 0.0   // bots: chance to fly over/dodge a boulder instead of hitting it
    var assist = 0.0         // bots: forward push (m/s^2) when stalled on an uphill
    private val rng = Random(seed + 17)
    var finished = false
    var lossReason = 0
    private var maxX = 3.0
    val finishX = 3.0 + RACE_LENGTH
    val events = ArrayList<Int>()
    var coinsCollected = 0
    var combo = 0          // consecutive perfect landings
    private var airTime = 0.0
    private val chunks = HashMap<Int, ArrayList<Coin>>()
    private val chunkSize = 50.0
    private val boulderChunks = HashMap<Int, ArrayList<Boulder>>()

    /** Rare, big boulders in valley bottoms (after 300 m). */
    private fun boulderChunk(ci: Int): ArrayList<Boulder> = boulderChunks.getOrPut(ci) {
        val list = ArrayList<Boulder>()
        if (ci >= 6) {
            val r = Random(seed xor (ci * 104729L))
            if (r.nextDouble() < 0.09) {
                val bx = terrain.valleyIn(ci * chunkSize, (ci + 1) * chunkSize)
                if (bx != null) {
                    val rr = 2.2 + r.nextDouble() * 1.0
                    list += Boulder(bx, terrain.height(bx) + rr * 0.6, rr)
                }
            }
        }
        boulderChunks.remove(ci - 8)
        list
    }

    fun bouldersIn(xa: Double, xb: Double): List<Boulder> {
        val out = ArrayList<Boulder>()
        for (ci in floor(xa / chunkSize).toInt()..floor(xb / chunkSize).toInt()) out += boulderChunk(ci)
        return out
    }

    /** Coins that trace paths the ball could really take: along the ground or through the air. */
    private fun chunk(ci: Int): ArrayList<Coin> = chunks.getOrPut(ci) {
        val list = ArrayList<Coin>()
        if (ci >= 2 && !race) {
            val r = Random(seed xor (ci * 7919L))
            val x0 = ci * chunkSize
            val roll = r.nextDouble()
            when {
                roll < 0.35 -> groundTrail(list, x0, r)
                roll < 0.70 -> flightArc(list, x0, r, high = false)
                roll < 0.82 -> flightArc(list, x0, r, high = true)
            }
        }
        chunks.remove(ci - 8)
        list
    }

    /** A row of coins right on the surface of a downhill or an uphill, where the ball rolls. */
    private fun groundTrail(list: ArrayList<Coin>, x0: Double, r: Random) {
        val wantDown = r.nextBoolean()
        var sx = x0 + 3 + r.nextDouble() * 10
        var cx = x0 + 3.0
        while (cx < x0 + 45.0) {
            val m = terrain.slope(cx)
            if (kotlin.math.abs(m) > 0.12 && (m < 0) == wantDown) { sx = cx; break }
            cx += 2.0
        }
        for (i in 0 until 7) {
            val px = sx + i * 3.0
            val m = terrain.slope(px)
            val inv = 1.0 / sqrt(1 + m * m)
            list += Coin(px - m * inv * Phys.RADIUS, terrain.height(px) + inv * Phys.RADIUS, 1)
        }
    }

    /**
     * Coins along the path of a ball leaving the next crest: a flat flight, or (high = true)
     * a big jump with a star at its highest point.
     */
    private fun flightArc(list: ArrayList<Coin>, x0: Double, r: Random, high: Boolean) {
        val xp = terrain.peakIn(x0, x0 + chunkSize) ?: return
        var px = xp
        var py = terrain.height(xp) + Phys.RADIUS
        val vh = 18.0 + r.nextDouble() * 14.0
        var vy = if (high) 18.0 + r.nextDouble() * 12.0 else 0.0
        val dt = 0.04
        var acc = 0.0
        var t = 0.0
        var apex = !high
        while (t < 8.0 && list.size < 22) {
            val prevVy = vy
            vy -= Phys.G * dt
            px += vh * dt
            py += vy * dt
            t += dt
            if (py - Phys.RADIUS < terrain.height(px)) break
            acc += hypot(vh, vy) * dt
            if (!apex && prevVy > 0 && vy <= 0) {
                apex = true
                list += Coin(px, py, 5)
                acc = 0.0
            } else if (acc >= 4.0) {
                acc = 0.0
                list += Coin(px, py, 1)
            }
        }
    }

    fun coinsIn(xa: Double, xb: Double): List<Coin> {
        val out = ArrayList<Coin>()
        // arcs can reach ~100 m past the chunk that owns them
        for (ci in floor(xa / chunkSize).toInt() - 2..floor(xb / chunkSize).toInt()) out += chunk(ci)
        return out
    }

    var x = 3.0
    var y = terrain.height(3.0) + Phys.RADIUS
    var vx = 0.0
    var vy = 0.0
    var angle = 0.0
    var grounded = true
    var launched = false
    private var everHeld = false
    var holding = false
    var over = false
    var time = 0.0
    private var stillTime = 0.0
    private var accum = 0.0

    /** Weight energy 0..1: drains while heavy, refills while the finger is up. */
    var weight = 1.0
    val heavy get() = holding && launched && weight > 0.0
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

    /** Called on touchdown. [vn] is the speed into the ground (negative), [slope] the ground slope. */
    private fun landed(vn: Double, slope: Double) {
        val impact = -vn
        val sp = speed
        if (slope < -0.1 && impact < 0.3 * sp) {
            // Shallow touchdown on a downhill: keep the speed and add a boost.
            combo++
            val boost = 1.0 + 0.04 * min(combo, 5)
            vx *= boost
            vy *= boost
            events += Ev.PERFECT
        } else if (impact > 12.0) {
            combo = 0
            events += Ev.HARD
        } else {
            events += Ev.SOFT
        }
    }

    private fun step(dt: Double) {
        time += dt
        // The opening push lasts until the first release (or until the ball has rolled off the start).
        if (holding) everHeld = true
        if (everHeld && !holding) launched = true
        if (x > 8.0) launched = true

        val g = if (heavy) Phys.G * Phys.HEAVY else Phys.G
        if (heavy) weight = max(0.0, weight - dt / Phys.WEIGHT_SECONDS)
        else if (!holding) weight = min(1.0, weight + dt * Phys.WEIGHT_REGEN)
        if (holding && !launched) vx += Phys.START_PUSH * dt
        if (assist > 0.0 && launched && speed < 5.0 && terrain.slope(x) > 0.02) vx += assist * dt
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
            if (airTime > 0.35) landed(vx * nx + vy * ny, m)
            x += nx * (Phys.RADIUS - d)
            y += ny * (Phys.RADIUS - d)
            val vn = vx * nx + vy * ny
            if (vn < 0) {
                vx -= vn * nx
                vy -= vn * ny
            }
            val sp = hypot(vx, vy)
            if (sp > 0) {
                val dec = min(Phys.FRICTION * Biomes.friction(Biomes.index(x)) * dt, sp)
                vx -= vx / sp * dec
                vy -= vy / sp * dec
            }
            // rolling: spin follows the speed along the surface (tangent = (ny, -nx))
            angle += (vx * ny - vy * nx) / Phys.RADIUS * dt
        } else {
            angle += (vx / Phys.RADIUS) * 0.2 * dt
        }

        airTime = if (grounded) 0.0 else airTime + dt

        for (coin in coinsIn(x - 3.0, x + 3.0)) {
            if (!coin.taken && hypot(coin.x - x, coin.y - y) < Phys.RADIUS + 0.9) {
                coin.taken = true
                coinsCollected += coin.value * min(1 + combo, 5)
                events += Ev.COIN
            }
        }

        for (b in bouldersIn(x - 6.0, x + 6.0)) {
            if (!b.broken && hypot(b.x - x, b.y - y) < Phys.RADIUS + b.r) {
                b.broken = true
                if (rng.nextDouble() < boulderAvoid) continue
                val f = if (heavy) 0.55 else 0.25
                vx *= f
                vy *= f
                combo = 0
                events += Ev.BOULDER
            }
        }

        stillTime = if (launched && speed < 0.4) stillTime + dt else 0.0
        if (race) {
            maxX = max(maxX, x)
            if (x >= finishX) { finished = true; over = true }
            else if (x < maxX - 6.0) { lossReason = Loss.BACK; over = true }
            else if (stillTime > 2.0) { lossReason = Loss.STUCK; over = true }
        } else if (stillTime > 2.0 || x < -5.0) over = true
    }
}
