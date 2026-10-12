package com.ballgame.hills

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
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

/** Ball skins, unlocked by your longest distance. Colour 0 = rainbow. */
private class Skin(val name: String, val unlock: Int, val color: Int)

private val SKINS = arrayOf(
    Skin("Gold", 0, Color.rgb(250, 190, 40)),
    Skin("Blue", 1000, Color.rgb(60, 130, 255)),
    Skin("Green", 2500, Color.rgb(60, 200, 100)),
    Skin("Purple", 5000, Color.rgb(160, 80, 230)),
    Skin("Pink", 7500, Color.rgb(255, 110, 180)),
    Skin("Rainbow", 10000, 0),
)

private fun rgb(r: Int, g: Int, b: Int) = Color.rgb(r, g, b)

private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
    (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
    (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
    (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
)

private enum class Screen { HOME, LEVELS, PLAY }
private enum class Mode { STANDARD, BOT, FRIEND }

private const val BASE_METERS = 36.0 // visible height in metres at ground level
private val BOT_COLOR = Color.rgb(55, 55, 65)
private val BOT_RING = Color.rgb(255, 150, 30)
private val P1_COLOR = Color.rgb(50, 120, 255)
private val P2_COLOR = Color.rgb(235, 60, 60)
private val LEVEL_NAMES = arrayOf("Easy", "Medium", "Hard")

private class Toast(val text: String, val big: Boolean, val until: Long, val color: Int)

/** Camera of one viewport. */
private class Cam {
    var x = 0.0
    var y = 0.0
    var viewMeters = BASE_METERS
    var altSmooth = 0.0
    var init = false
}

/** One ball being played, with its own camera and messages. [fixedColor] 0 = use the chosen skin. */
private class Player(val world: World, val label: String, val fixedColor: Int) {
    val cam = Cam()
    val toasts = ArrayList<Toast>()
    var lastBiome = 0
}

class GameView(context: Context) : View(context) {
    private val prefs = context.getSharedPreferences("hills", Context.MODE_PRIVATE)
    private var best = prefs.getInt("best", 0)            // longest distance, m
    private var bestHeight = prefs.getInt("bestHeight", 0) // highest jump above the ground, m
    private var totalCoins = prefs.getInt("coins", 0)
    private var startBest = best
    private var startBestHeight = bestHeight
    private var shownDistRecord = false
    private var shownHeightRecord = false
    private var lastMilestone = 0
    private val announcedSkins = HashSet<Int>()
    private var skin = prefs.getInt("skin", 0).coerceIn(0, SKINS.size - 1)
    private val sfx = Sfx().apply { enabled = prefs.getBoolean("sound", true) }
    private val cheers = arrayOf("Keep going!", "Nice rolling!", "You're on fire!", "Unstoppable!", "Smooth ride!")

    private var screen = Screen.HOME
    private var mode = Mode.STANDARD
    private var level = 1
    private val players = ArrayList<Player>()
    private var bot: Bot? = null
    private var raceOver = false
    private var winner = -1
    private var resultReason = ""
    private var countdown = 0.0
    private var goUntil = 0L
    private var overAt = 0L
    private var menuTime = 0.0

    private var running = false
    private var lastNanos = 0L
    private var nightAmount = 0f

    private val uiPointers = HashSet<Int>()
    private val btn = Array(3) { RectF() }
    private val backRect = RectF()
    private val homeRect = RectF()
    private val restartRect = RectF()
    private val soundRect = RectF()
    private val skinRect = RectF()
    private val againRect = RectF()
    private val menuRect = RectF()

    private val sky = Paint()
    private val far = Paint().apply { color = Color.rgb(150, 170, 205); isAntiAlias = true }
    private val mid = Paint().apply { color = Color.rgb(110, 135, 175); isAntiAlias = true }
    private val ground = Paint().apply { color = Color.rgb(74, 124, 70); isAntiAlias = true }
    private val rock = Paint().apply { color = Color.rgb(92, 78, 66) }
    private val edge = Paint().apply {
        color = Color.rgb(140, 200, 100); style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true
    }
    private val ballPaint = Paint().apply { isAntiAlias = true }
    private val ringPaint = Paint().apply { style = Paint.Style.STROKE; isAntiAlias = true }
    private val mark = Paint().apply { color = Color.WHITE; strokeWidth = 6f; isAntiAlias = true }
    private val text = Paint().apply { color = Color.WHITE; isAntiAlias = true; setShadowLayer(4f, 0f, 2f, Color.argb(160, 0, 0, 0)) }
    private val path = Path()

    // ---------------------------------------------------------------- lifecycle

    fun resumeLoop() { running = true; lastNanos = 0L; postInvalidateOnAnimation() }

    fun pauseLoop() { running = false; saveRecords() }

    override fun onDetachedFromWindow() {
        sfx.release()
        super.onDetachedFromWindow()
    }

    /** Android back button. Returns true if it was handled. */
    fun onBack(): Boolean {
        when (screen) {
            Screen.PLAY -> goHome()
            Screen.LEVELS -> screen = Screen.HOME
            Screen.HOME -> return false
        }
        return true
    }

    private fun runCoins(): Int = if (mode == Mode.STANDARD && players.isNotEmpty()) players[0].world.coinsCollected else 0

    private fun saveRecords() {
        prefs.edit().putInt("best", best).putInt("bestHeight", bestHeight)
            .putInt("coins", totalCoins + runCoins()).apply()
    }

    private fun commitRun() {
        saveRecords()
        totalCoins += runCoins()
    }

    private fun goHome() {
        commitRun()
        players.clear()
        bot = null
        screen = Screen.HOME
    }

    private fun restart() {
        commitRun()
        startGame(mode, level)
    }

    private fun startGame(m: Mode, lvl: Int) {
        mode = m
        level = lvl
        screen = Screen.PLAY
        val seed = System.nanoTime()
        players.clear()
        bot = null
        when (m) {
            Mode.STANDARD -> players += Player(World(seed), "You", 0)
            Mode.BOT -> {
                players += Player(World(seed, race = true), "YOU", 0)
                val bw = World(seed, race = true)
                players += Player(bw, "BOT", BOT_COLOR)
                bot = Bot(bw, lvl, seed)
            }
            Mode.FRIEND -> {
                players += Player(World(seed, race = true), "P1", P1_COLOR)
                players += Player(World(seed, race = true), "P2", P2_COLOR)
            }
        }
        raceOver = false
        winner = -1
        resultReason = ""
        countdown = if (m == Mode.STANDARD) 0.0 else 3.0
        goUntil = 0L
        startBest = best
        startBestHeight = bestHeight
        shownDistRecord = false
        shownHeightRecord = false
        lastMilestone = 0
        announcedSkins.clear()
        uiPointers.clear()
    }

    // ---------------------------------------------------------------- skins, toasts, records

    private fun cycleSkin() {
        val p = players.firstOrNull() ?: return
        for (step in 1..SKINS.size) {
            val i = (skin + step) % SKINS.size
            if (best >= SKINS[i].unlock) {
                if (i == skin) {
                    val next = SKINS.firstOrNull { it.unlock > best }
                    toast(p, if (next != null) "Next skin (${next.name}) at ${next.unlock} m" else "All skins unlocked")
                    return
                }
                skin = i
                prefs.edit().putInt("skin", skin).apply()
                toast(p, "Skin: ${SKINS[i].name}")
                return
            }
        }
    }

    private fun skinColor(): Int {
        val sk = SKINS[skin]
        if (sk.color != 0) return sk.color
        return Color.HSVToColor(floatArrayOf(((System.currentTimeMillis() / 8) % 360).toFloat(), 0.8f, 1f))
    }

    private fun colorOf(p: Player): Int = if (p.fixedColor != 0) p.fixedColor else skinColor()

    private fun toast(p: Player, text: String, big: Boolean = false, fanfare: Boolean = false, color: Int = Color.WHITE) {
        if (fanfare) sfx.play(Sfx.FANFARE)
        p.toasts.add(Toast(text, big, System.currentTimeMillis() + if (big) 5000 else 3000, color))
        if (p.toasts.size > 3) p.toasts.removeAt(0)
    }

    private fun updateRecords(p: Player) {
        val world = p.world
        val d = world.distance.toInt()
        if (d > best) {
            best = d
            if (startBest > 0 && !shownDistRecord) { shownDistRecord = true; toast(p, "New distance record!", fanfare = true) }
        }
        if (!world.grounded) {
            val hgt = (world.y - Phys.RADIUS - world.terrain.height(world.x)).toInt()
            if (hgt > bestHeight) {
                bestHeight = hgt
                if (startBestHeight > 0 && !shownHeightRecord) { shownHeightRecord = true; toast(p, "New height record!", fanfare = true) }
            }
        }
        for ((i, sk) in SKINS.withIndex()) {
            if (sk.unlock > 0 && d >= sk.unlock && startBest < sk.unlock && announcedSkins.add(i)) {
                toast(p, "New skin unlocked: ${sk.name}!", fanfare = true)
            }
        }
        while (d >= (lastMilestone + 1) * 1000) {
            lastMilestone++
            if (lastMilestone % 10 == 0) toast(p, "CONGRATULATIONS! ${lastMilestone} km!", big = true, fanfare = true)
            else toast(p, "${lastMilestone * 1000} m - ${cheers[lastMilestone % cheers.size]}", fanfare = true)
        }
    }

    private fun handleEvents(p: Player, haptics: Boolean) {
        val world = p.world
        for (ev in world.events) {
            when (ev) {
                Ev.COIN -> sfx.play(Sfx.COIN)
                Ev.PERFECT -> {
                    toast(p, if (mode == Mode.STANDARD) "PERFECT landing!  x${min(1 + world.combo, 5)} coins" else "PERFECT landing!  speed boost")
                    sfx.play(Sfx.PERFECT)
                    if (haptics) performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                }
                Ev.SOFT -> sfx.play(Sfx.SOFT)
                Ev.HARD -> {
                    sfx.play(Sfx.HARD)
                    if (haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                Ev.BOULDER -> {
                    toast(p, "BOULDER! You lost most of your speed", color = Color.rgb(255, 120, 100))
                    sfx.play(Sfx.ROCK)
                    if (haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            }
        }
        world.events.clear()
        if (!world.over) {
            for (b in world.bouldersIn(world.x, world.x + 170.0)) {
                if (!b.warned && !b.broken && b.x - world.x < 160.0) {
                    b.warned = true
                    toast(p, "BOULDER AHEAD in the valley! Fly over it!", color = Color.rgb(255, 90, 70))
                }
            }
        }
    }

    // ---------------------------------------------------------------- race rules

    private fun nameOf(i: Int): String = when (mode) {
        Mode.BOT -> if (i == 0) "You" else "The bot"
        else -> "Player ${i + 1}"
    }

    private fun lossText(i: Int): String {
        val how = if (players[i].world.lossReason == Loss.BACK) "rolled backwards" else "got stuck"
        return "${nameOf(i)} $how"
    }

    private fun endRace(win: Int, reason: String) {
        raceOver = true
        winner = win
        resultReason = reason
        overAt = System.currentTimeMillis()
        for (p in players) p.world.holding = false
        sfx.play(if (mode == Mode.BOT && win == 1) Sfx.HARD else Sfx.FANFARE)
    }

    private fun checkRaceEnd() {
        val a = players[0].world
        val b = players[1].world
        when {
            a.finished && b.finished -> {
                val w = if (a.x >= b.x) 0 else 1
                endRace(w, "${nameOf(w)} reached the finish line first")
            }
            a.finished -> endRace(0, "${nameOf(0)} reached the finish line")
            b.finished -> endRace(1, "${nameOf(1)} reached the finish line")
            a.over && b.over -> {
                val w = if (a.x >= b.x) 0 else 1
                endRace(w, "Both stopped - ${nameOf(w)} got further")
            }
            a.over -> endRace(1, lossText(0))
            b.over -> endRace(0, lossText(1))
        }
    }

    private fun updatePlay(dt: Double) {
        if (players.isEmpty()) return
        if (mode == Mode.STANDARD) {
            val p = players[0]
            val world = p.world
            val wasOver = world.over
            world.update(dt)
            if (world.over && !wasOver) {
                overAt = System.currentTimeMillis()
                saveRecords()
            }
            if (!world.over) updateRecords(p)
            handleEvents(p, true)
            return
        }
        if (countdown > 0.0) {
            countdown -= dt
            if (countdown <= 0.0) goUntil = System.currentTimeMillis() + 900
        } else if (!raceOver) {
            bot?.update(dt)
            for (p in players) p.world.update(dt)
            checkRaceEnd()
        }
        for ((i, p) in players.withIndex()) {
            if (mode == Mode.BOT && i == 1) { p.world.events.clear(); continue }
            handleEvents(p, mode == Mode.FRIEND || i == 0)
        }
    }

    // ---------------------------------------------------------------- input

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                if (handleTap(e.getX(i), e.getY(i))) uiPointers.add(e.getPointerId(i))
            }
            MotionEvent.ACTION_POINTER_UP -> uiPointers.remove(e.getPointerId(e.actionIndex))
        }
        updateHolds(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) uiPointers.clear()
        return true
    }

    /** Which sides of the screen are being held. Left half = player 1, right half = player 2. */
    private fun updateHolds(e: MotionEvent) {
        if (screen != Screen.PLAY || players.isEmpty()) return
        var left = false
        var right = false
        val ending = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
        val lifting = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        if (!ending) {
            for (i in 0 until e.pointerCount) {
                if (i == lifting || uiPointers.contains(e.getPointerId(i))) continue
                if (e.getX(i) < width / 2f) left = true else right = true
            }
        }
        if (mode == Mode.FRIEND) {
            players[0].world.holding = left
            players[1].world.holding = right
        } else {
            players[0].world.holding = left || right
        }
    }

    /** Handles a finger going down. Returns true if it hit a button (so it must not count as "hold"). */
    private fun handleTap(x: Float, y: Float): Boolean {
        when (screen) {
            Screen.HOME -> {
                if (soundRect.contains(x, y)) { toggleSound(); return true }
                if (btn[0].contains(x, y)) { startGame(Mode.STANDARD, level); return true }
                if (btn[1].contains(x, y)) { screen = Screen.LEVELS; return true }
                if (btn[2].contains(x, y)) { startGame(Mode.FRIEND, level); return true }
                return true
            }
            Screen.LEVELS -> {
                for (i in 0 until 3) if (btn[i].contains(x, y)) { startGame(Mode.BOT, i); return true }
                if (backRect.contains(x, y)) screen = Screen.HOME
                return true
            }
            Screen.PLAY -> {
                if (homeRect.contains(x, y)) { goHome(); return true }
                if (restartRect.contains(x, y)) { restart(); return true }
                if (soundRect.contains(x, y)) { toggleSound(); return true }
                if (mode != Mode.FRIEND && skinRect.contains(x, y)) { cycleSkin(); return true }
                if (raceOver && System.currentTimeMillis() - overAt > 500) {
                    if (againRect.contains(x, y)) { restart(); return true }
                    if (menuRect.contains(x, y)) { goHome(); return true }
                    return true
                }
                if (mode == Mode.STANDARD && players.isNotEmpty() && players[0].world.over &&
                    System.currentTimeMillis() - overAt > 600
                ) {
                    restart()
                    return false // this same touch starts holding in the new run
                }
                return false
            }
        }
        return false
    }

    private fun toggleSound() {
        sfx.enabled = !sfx.enabled
        prefs.edit().putBoolean("sound", sfx.enabled).apply()
        players.firstOrNull()?.let { toast(it, if (sfx.enabled) "Sound on" else "Sound off") }
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.0 else (now - lastNanos) / 1e9
        lastNanos = now
        menuTime += dt
        val w = width.toFloat()
        val h = height.toFloat()

        when (screen) {
            Screen.HOME -> drawHome(c, w, h)
            Screen.LEVELS -> drawLevels(c, w, h)
            Screen.PLAY -> {
                updatePlay(dt)
                drawPlay(c, w, h, dt)
            }
        }
        if (running) postInvalidateOnAnimation()
    }

    private fun updateCam(p: Player, w: Float, h: Float, dt: Double) {
        val world = p.world
        val cam = p.cam
        val groundY = world.terrain.height(world.x)
        val altTotal = (world.y - groundY).coerceAtLeast(0.0)
        // Smoothed altitude (rises fast, falls slowly) so the zoom doesn't pump as the ground under the ball changes.
        val altRate = if (altTotal > cam.altSmooth) 5.0 else 0.7
        cam.altSmooth += (altTotal - cam.altSmooth) * (1 - exp(-altRate * dt))
        val need = max(BASE_METERS, (cam.altSmooth + 24.0) * 1.6)
        val zoomRate = if (need > cam.viewMeters) 4.0 else 0.8
        cam.viewMeters += (need - cam.viewMeters) * (1 - exp(-zoomRate * dt))
        // Safety net only: never let the ground leave the screen.
        cam.viewMeters = max(cam.viewMeters, (altTotal + 14.0) * 1.15)
        val scale = h / cam.viewMeters
        val halfW = w / scale / 2.0
        val tx = world.x + halfW * 0.35
        val ty = world.y + 3.0 - max(0.0, cam.altSmooth - 10.0) * 0.5
        if (!cam.init) { cam.x = tx; cam.y = ty; cam.init = true }
        val k = 1 - exp(-4.0 * dt)
        cam.x += (tx - cam.x) * min(1.0, k * 3)
        cam.y += (ty - cam.y) * k
        cam.y = cam.y.coerceIn(
            world.y - cam.viewMeters / 2 + 3,
            min(world.y + cam.viewMeters / 2 - 3, groundY + cam.viewMeters / 2 - 1),
        )
    }

    private fun drawPlay(c: Canvas, w: Float, h: Float, dt: Double) {
        if (players.isEmpty()) return
        if (mode == Mode.FRIEND) {
            val hw = w / 2f
            for (i in 0..1) {
                updateCam(players[i], hw, h, dt)
                c.save()
                c.clipRect(i * hw, 0f, (i + 1) * hw, h)
                c.translate(i * hw, 0f)
                drawScene(c, hw, h, players[i], listOf(players[1 - i]))
                drawPlayerHud(c, hw, h, players[i], i)
                c.restore()
            }
            mark.color = Color.WHITE
            mark.strokeWidth = 6f
            c.drawLine(hw, 0f, hw, h, mark)
        } else {
            updateCam(players[0], w, h, dt)
            val others = if (mode == Mode.BOT) listOf(players[1]) else emptyList()
            drawScene(c, w, h, players[0], others)
            drawPlayerHud(c, w, h, players[0], 0)
        }
        drawPlayButtons(c, w, h)
        if (mode != Mode.STANDARD) {
            drawRaceBar(c, w, h)
            drawCountdown(c, w, h)
            if (raceOver) drawResult(c, w, h)
        }
    }

    /** The world as seen by [p]: sky, hills, boulders, coins, flags, other balls and the player's own ball. */
    private fun drawScene(c: Canvas, w: Float, h: Float, p: Player, others: List<Player>) {
        val world = p.world
        val cam = p.cam
        val scale = (h / cam.viewMeters).toFloat()
        fun sx(wx: Double) = (w / 2 + (wx - cam.x) * scale).toFloat()
        fun sy(wy: Double) = (h / 2 - (wy - cam.y) * scale).toFloat()

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
        if (bi != p.lastBiome) { p.lastBiome = bi; toast(p, "Entering: ${Biomes.names[bi]}") }

        c.drawRect(0f, 0f, w, h, sky)
        drawStars(c, w, h)
        ridge(c, far, cam, 0.15, 55.0, 14.0, 0.011, h, w)
        ridge(c, mid, cam, 0.3, 38.0, 9.0, 0.019, h, w)

        // terrain
        val step = 6f
        path.reset()
        path.moveTo(0f, h + 10f)
        var px = 0f
        while (px <= w + step) {
            val wx = cam.x + (px - w / 2) / scale
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
            val wx = cam.x + (px - w / 2) / scale
            val yy = sy(world.terrain.height(wx)) + scale * 1.0f
            if (first) { path.moveTo(px, yy); first = false } else path.lineTo(px, yy)
            px += step
        }
        c.drawPath(path, ground)
        c.restore()
        edge.strokeWidth = scale * 0.25f
        path.reset(); px = 0f; first = true
        while (px <= w + step) {
            val wx = cam.x + (px - w / 2) / scale
            val yy = sy(world.terrain.height(wx))
            if (first) { path.moveTo(px, yy); first = false } else path.lineTo(px, yy)
            px += step
        }
        c.drawPath(path, edge)

        val halfView = w / 2 / scale
        // boulders
        for (b in world.bouldersIn(cam.x - halfView - 6, cam.x + halfView + 6)) {
            if (b.broken) continue
            val bxp = sx(b.x)
            val byp = sy(b.y)
            val br = (b.r * scale).toFloat()
            ballPaint.color = Color.rgb(85, 80, 88)
            c.drawCircle(bxp, byp, br, ballPaint)
            ballPaint.color = Color.rgb(120, 115, 125)
            c.drawCircle(bxp - br * 0.25f, byp - br * 0.25f, br * 0.6f, ballPaint)
            ballPaint.color = Color.rgb(150, 145, 155)
            c.drawCircle(bxp - br * 0.35f, byp - br * 0.4f, br * 0.25f, ballPaint)
            // warning sign above it
            val sy0 = byp - br - scale * 3.2f
            path.reset()
            path.moveTo(bxp, sy0)
            path.lineTo(bxp - scale * 1.4f, sy0 + scale * 2.4f)
            path.lineTo(bxp + scale * 1.4f, sy0 + scale * 2.4f)
            path.close()
            ballPaint.color = Color.rgb(230, 50, 40)
            c.drawPath(path, ballPaint)
            text.textAlign = Paint.Align.CENTER
            text.textSize = scale * 1.8f
            c.drawText("!", bxp, sy0 + scale * 2.2f, text)
        }
        // coins (standard mode only)
        if (!world.race) {
            for (coin in world.coinsIn(cam.x - halfView - 2, cam.x + halfView + 2)) {
                if (coin.taken) continue
                val star = coin.value > 1
                val cr = (if (star) 1.3 else 0.8) * scale
                ballPaint.color = if (star) Color.rgb(255, 140, 30) else Color.rgb(255, 205, 40)
                c.drawCircle(sx(coin.x), sy(coin.y), cr.toFloat(), ballPaint)
                ballPaint.color = if (star) Color.rgb(255, 230, 120) else Color.rgb(255, 238, 150)
                c.drawCircle(sx(coin.x), sy(coin.y), (cr * 0.6).toFloat(), ballPaint)
            }
        }
        // milestone flags every 1000 m
        var kFlag = max(1, ceil((cam.x - halfView - 3.0) / 1000.0).toInt())
        while (3.0 + kFlag * 1000.0 <= cam.x + halfView) {
            val fx = 3.0 + kFlag * 1000.0
            if (!(world.race && fx >= world.finishX - 1.0)) {
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
            }
            kFlag++
        }
        mark.strokeWidth = 6f
        // finish line (race modes)
        if (world.race && abs(world.finishX - cam.x) < halfView + 10) drawFinish(c, world, ::sx, ::sy, scale)

        // other balls first, so the player's own ball is drawn on top
        val r = (Phys.RADIUS * scale).toFloat()
        for (o in others) {
            val ow = o.world
            val ox = sx(ow.x)
            val oy = sy(ow.y)
            if (ox < -r || ox > w + r) {
                drawEdgeTag(c, w, h, o, ox > w, oy)
                continue
            }
            drawBall(c, ox, oy, r, o, ow.heavy, ow.angle)
            drawTag(c, o.label, ox, oy - r * 2.3f, h / 30f, if (o.fixedColor == BOT_COLOR) BOT_RING else o.fixedColor)
        }
        val bx = sx(world.x)
        val by = sy(world.y)
        drawBall(c, bx, by, r, p, world.heavy, world.angle)
        if (mode != Mode.STANDARD) drawTag(c, p.label, bx, by - r * 2.3f, h / 30f, if (p.fixedColor != 0) p.fixedColor else Color.rgb(250, 190, 40))
    }

    private fun drawStars(c: Canvas, w: Float, h: Float) {
        if (nightAmount <= 0.01f) return
        mark.color = Color.argb((220 * nightAmount).toInt(), 255, 255, 230)
        for (i in 0 until 50) {
            val sxp = ((i * 7919) % 1000) / 1000f * w
            val syp = ((i * 104729) % 1000) / 1000f * h * 0.6f
            c.drawCircle(sxp, syp, 2f + (i % 3), mark)
        }
        mark.color = Color.WHITE
    }

    private fun drawFinish(c: Canvas, world: World, sx: (Double) -> Float, sy: (Double) -> Float, scale: Float) {
        val fx = world.finishX
        val gy = world.terrain.height(fx)
        val poleH = 12.0
        mark.strokeWidth = scale * 0.35f
        mark.color = Color.WHITE
        c.drawLine(sx(fx), sy(gy), sx(fx), sy(gy + poleH), mark)
        c.drawLine(sx(fx + 14.0), sy(gy), sx(fx + 14.0), sy(gy + poleH), mark)
        // checkered banner between the two poles
        val cols = 14
        val rows = 3
        val cw = 14.0 / cols
        val rh = 2.0
        for (i in 0 until cols) for (j in 0 until rows) {
            ballPaint.color = if ((i + j) % 2 == 0) Color.BLACK else Color.WHITE
            c.drawRect(sx(fx + i * cw), sy(gy + poleH - j * rh), sx(fx + (i + 1) * cw), sy(gy + poleH - (j + 1) * rh), ballPaint)
        }
        mark.strokeWidth = 6f
    }

    private fun drawBall(c: Canvas, bx: Float, by: Float, r: Float, p: Player, heavy: Boolean, angle: Double) {
        if (heavy) {
            ballPaint.color = Color.argb(70, 255, 60, 40)
            c.drawCircle(bx, by, r * 1.6f, ballPaint)
        }
        val base = colorOf(p)
        // Standard mode tints a heavy ball red; in races the colour never changes (the halo shows heaviness).
        ballPaint.color = if (heavy && mode == Mode.STANDARD) mix(base, Color.rgb(200, 40, 30), 0.65f) else base
        val rr = r * if (heavy) 1.15f else 1f
        c.drawCircle(bx, by, rr, ballPaint)
        if (p.fixedColor == BOT_COLOR) {
            ringPaint.color = BOT_RING
            ringPaint.strokeWidth = r * 0.3f
            c.drawCircle(bx, by, rr - r * 0.15f, ringPaint)
        }
        mark.color = if (p.fixedColor == BOT_COLOR) BOT_RING else Color.WHITE
        c.drawLine(bx, by, bx + (cos(angle) * r * 0.85).toFloat(), by + (sin(angle) * r * 0.85).toFloat(), mark)
        mark.color = Color.WHITE
    }

    /** Small coloured label centred at ([cx], [cy]). */
    private fun drawTag(c: Canvas, label: String, cx: Float, cy: Float, size: Float, color: Int) {
        text.textSize = size
        text.textAlign = Paint.Align.CENTER
        val tw = text.measureText(label)
        ballPaint.color = color
        c.drawRoundRect(cx - tw / 2 - size * 0.5f, cy - size * 1.0f, cx + tw / 2 + size * 0.5f, cy + size * 0.4f, size * 0.6f, size * 0.6f, ballPaint)
        text.color = if (color == Color.WHITE) Color.BLACK else Color.WHITE
        c.drawText(label, cx, cy, text)
        text.color = Color.WHITE
    }

    /** Arrow tag at the screen edge when the other ball is out of view. */
    private fun drawEdgeTag(c: Canvas, w: Float, h: Float, o: Player, onRight: Boolean, oy: Float) {
        val size = h / 28f
        val label = if (onRight) "${o.label} >" else "< ${o.label}"
        text.textSize = size
        val tw = text.measureText(label)
        val cx = if (onRight) w - tw / 2 - size * 1.2f else tw / 2 + size * 1.2f
        val cy = oy.coerceIn(h * 0.2f, h * 0.8f)
        val color = if (o.fixedColor == BOT_COLOR) BOT_RING else o.fixedColor
        drawTag(c, label, cx, cy, size, color)
    }

    private fun ridge(c: Canvas, p: Paint, cam: Cam, parallax: Double, base: Double, amp: Double, freq: Double, h: Float, w: Float) {
        val scale = h / cam.viewMeters
        path.reset()
        path.moveTo(0f, h)
        var px = 0f
        while (px <= w + 10) {
            val wx = (cam.x * parallax) + (px - w / 2) / scale
            val v = base + amp * (sin(wx * freq * 6) * 0.6 + sin(wx * freq * 14 + 1.3) * 0.4)
            val yy = h / 2 - ((v - cam.y * parallax * 0.4 - 25) * scale).toFloat()
            path.lineTo(px, yy.coerceAtMost(h))
            px += 10f
        }
        path.lineTo(w + 10, h)
        path.close()
        c.drawPath(path, p)
    }

    // ---------------------------------------------------------------- HUD

    private fun drawPlayerHud(c: Canvas, w: Float, h: Float, p: Player, index: Int) {
        val world = p.world
        text.textAlign = Paint.Align.LEFT
        if (mode == Mode.STANDARD) {
            text.textSize = h / 16f
            c.drawText("${world.distance.toInt()} m" + if (world.combo > 0) "   combo x${world.combo}" else "", 24f, h / 12f, text)
            text.textSize = h / 24f
            c.drawText("best $best m   highest $bestHeight m", 24f, h / 12f + h / 18f, text)
            c.drawText("coins ${world.coinsCollected}   total ${totalCoins + world.coinsCollected}", 24f, h / 12f + h / 9f, text)
        } else {
            val me = players[index]
            val other = players[1 - index]
            val tagColor = if (me.fixedColor != 0) me.fixedColor else Color.rgb(250, 190, 40)
            drawTag(c, me.label, 24f + h / 14f, h / 11f - h / 40f, h / 26f, tagColor)
            text.textAlign = Paint.Align.LEFT
            text.textSize = h / 16f
            c.drawText("${world.distance.toInt()} m", 24f, h / 11f + h / 14f, text)
            val ahead = me.world.x >= other.world.x
            val gap = abs(me.world.x - other.world.x).toInt()
            text.textSize = h / 22f
            text.color = if (ahead) Color.rgb(255, 215, 60) else Color.rgb(255, 150, 130)
            c.drawText(if (ahead) "1st  +$gap m" else "2nd  -$gap m", 24f, h / 11f + h / 14f + h / 17f, text)
            text.color = Color.WHITE
        }

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

        // messages
        text.textAlign = Paint.Align.CENTER
        val nowMs = System.currentTimeMillis()
        p.toasts.removeAll { it.until < nowMs }
        var ty0 = h * 0.3f
        for (t in p.toasts) {
            text.textSize = if (t.big) h / 9f else h / 14f
            text.color = if (t.big) Color.rgb(255, 215, 60) else t.color
            c.drawText(t.text, w / 2, ty0, text)
            ty0 += text.textSize * 1.2f
        }
        text.color = Color.WHITE
        text.textSize = h / 24f
        if (!world.launched && !world.holding && countdown <= 0.0) {
            c.drawText(
                if (mode == Mode.FRIEND) "Hold this side to push" else "Hold to push the ball (or just let it roll)",
                w / 2, h * 0.2f, text,
            )
        } else if (mode == Mode.STANDARD && world.launched && world.distance < 60) {
            c.drawText("Hold on downhills = heavier ball = more speed", w / 2, h * 0.2f, text)
        }
        if (mode == Mode.STANDARD && world.over) {
            text.textSize = h / 10f
            c.drawText("Stuck at ${world.distance.toInt()} m", w / 2, h * 0.55f, text)
            text.textSize = h / 20f
            c.drawText("Tap to try again", w / 2, h * 0.65f, text)
        }
    }

    private fun iconButton(c: Canvas, r: RectF) {
        ballPaint.color = Color.argb(150, 0, 0, 0)
        c.drawRoundRect(r, r.height() / 4, r.height() / 4, ballPaint)
    }

    private fun drawPlayButtons(c: Canvas, w: Float, h: Float) {
        val bs = h / 9f
        val gap = 12f
        restartRect.set(w - bs - 16f, 16f, w - 16f, 16f + bs)
        soundRect.set(restartRect.left - bs - gap, 16f, restartRect.left - gap, 16f + bs)
        var left = soundRect.left
        if (mode != Mode.FRIEND) {
            skinRect.set(left - bs - gap, 16f, left - gap, 16f + bs)
            left = skinRect.left
        } else {
            skinRect.setEmpty()
        }
        homeRect.set(left - bs - gap, 16f, left - gap, 16f + bs)

        iconButton(c, restartRect)
        text.textAlign = Paint.Align.CENTER
        text.textSize = bs * 0.7f
        c.drawText("↻", restartRect.centerX(), restartRect.centerY() + bs * 0.25f, text)

        iconButton(c, soundRect)
        c.drawText("♪", soundRect.centerX(), soundRect.centerY() + bs * 0.25f, text)
        if (!sfx.enabled) {
            mark.strokeWidth = 6f
            c.drawLine(soundRect.left + bs * 0.2f, soundRect.bottom - bs * 0.2f, soundRect.right - bs * 0.2f, soundRect.top + bs * 0.2f, mark)
        }

        if (mode != Mode.FRIEND) {
            iconButton(c, skinRect)
            ballPaint.color = skinColor()
            c.drawCircle(skinRect.centerX(), skinRect.centerY(), bs * 0.3f, ballPaint)
        }

        // home: a little house
        iconButton(c, homeRect)
        val cx = homeRect.centerX()
        val cy = homeRect.centerY()
        val u = bs * 0.26f
        path.reset()
        path.moveTo(cx - u * 1.3f, cy - u * 0.1f)
        path.lineTo(cx, cy - u * 1.3f)
        path.lineTo(cx + u * 1.3f, cy - u * 0.1f)
        path.close()
        ballPaint.color = Color.WHITE
        c.drawPath(path, ballPaint)
        c.drawRect(cx - u * 0.8f, cy - u * 0.1f, cx + u * 0.8f, cy + u * 1.1f, ballPaint)
        ballPaint.color = Color.argb(200, 40, 40, 40)
        c.drawRect(cx - u * 0.25f, cy + u * 0.3f, cx + u * 0.25f, cy + u * 1.1f, ballPaint)
    }

    /** Progress of both balls towards the finish line. */
    private fun drawRaceBar(c: Canvas, w: Float, h: Float) {
        val barW = w * 0.34f
        val x0 = (w - barW) / 2
        val y = h * 0.05f
        val th = h / 90f
        ballPaint.color = Color.argb(150, 0, 0, 0)
        c.drawRoundRect(x0 - 6f, y - th - 4f, x0 + barW + 6f, y + th + 4f, th * 2, th * 2, ballPaint)
        ballPaint.color = Color.argb(200, 255, 255, 255)
        c.drawRoundRect(x0, y - th * 0.4f, x0 + barW, y + th * 0.4f, th, th, ballPaint)
        // finish flag
        ballPaint.color = Color.WHITE
        c.drawRect(x0 + barW - 4f, y - th * 2.2f, x0 + barW + th * 2f, y - th * 0.6f, ballPaint)
        ballPaint.color = Color.BLACK
        c.drawRect(x0 + barW - 4f, y - th * 2.2f, x0 + barW + th * 0.5f, y - th * 1.4f, ballPaint)
        c.drawRect(x0 + barW + th * 0.5f, y - th * 1.4f, x0 + barW + th * 2f, y - th * 0.6f, ballPaint)
        for ((i, p) in players.withIndex()) {
            val f = (p.world.distance / RACE_LENGTH).coerceIn(0.0, 1.0).toFloat()
            val col = if (p.fixedColor == BOT_COLOR) BOT_RING else if (p.fixedColor != 0) p.fixedColor else skinColor()
            val cy = y + if (i == 0) -th * 0.3f else th * 0.3f
            ballPaint.color = Color.WHITE
            c.drawCircle(x0 + barW * f, cy, th * 2.4f, ballPaint)
            ballPaint.color = col
            c.drawCircle(x0 + barW * f, cy, th * 1.9f, ballPaint)
        }
        text.textAlign = Paint.Align.CENTER
        text.textSize = h / 34f
        c.drawText("${RACE_LENGTH.toInt()} m", x0 + barW / 2, y + th * 6f, text)
    }

    private fun drawCountdown(c: Canvas, w: Float, h: Float) {
        text.textAlign = Paint.Align.CENTER
        if (countdown > 0.0) {
            text.textSize = h / 3f
            text.color = Color.rgb(255, 215, 60)
            c.drawText("${ceil(countdown).toInt()}", w / 2, h * 0.58f, text)
        } else if (System.currentTimeMillis() < goUntil) {
            text.textSize = h / 3.5f
            text.color = Color.rgb(120, 255, 120)
            c.drawText("GO!", w / 2, h * 0.58f, text)
        }
        text.color = Color.WHITE
    }

    private fun drawResult(c: Canvas, w: Float, h: Float) {
        ballPaint.color = Color.argb(150, 0, 0, 0)
        c.drawRect(0f, 0f, w, h, ballPaint)
        text.textAlign = Paint.Align.CENTER
        val title: String
        val color: Int
        if (mode == Mode.BOT) {
            title = if (winner == 0) "YOU WIN!" else "BOT WINS"
            color = if (winner == 0) Color.rgb(255, 215, 60) else BOT_RING
        } else {
            title = "PLAYER ${winner + 1} WINS!"
            color = if (winner == 0) P1_COLOR else P2_COLOR
        }
        text.textSize = h / 7f
        text.color = color
        c.drawText(title, w / 2, h * 0.30f, text)
        text.color = Color.WHITE
        text.textSize = h / 18f
        c.drawText(resultReason, w / 2, h * 0.40f, text)
        text.textSize = h / 22f
        val lvl = if (mode == Mode.BOT) "  (${LEVEL_NAMES[level]})" else ""
        c.drawText(
            "${nameOf(0)}: ${players[0].world.distance.toInt()} m      ${nameOf(1)}: ${players[1].world.distance.toInt()} m$lvl",
            w / 2, h * 0.48f, text,
        )
        val bw = w * 0.22f
        val bh = h * 0.16f
        againRect.set(w / 2 - bw - 20f, h * 0.58f, w / 2 - 20f, h * 0.58f + bh)
        menuRect.set(w / 2 + 20f, h * 0.58f, w / 2 + bw + 20f, h * 0.58f + bh)
        bigButton(c, againRect, "Play again", Color.rgb(60, 170, 90))
        bigButton(c, menuRect, "Menu", Color.rgb(70, 110, 200))
    }

    private fun bigButton(c: Canvas, r: RectF, label: String, color: Int, sub: String? = null) {
        ballPaint.color = Color.argb(90, 0, 0, 0)
        c.drawRoundRect(r.left, r.top + 8f, r.right, r.bottom + 8f, r.height() / 5, r.height() / 5, ballPaint)
        ballPaint.color = color
        c.drawRoundRect(r, r.height() / 5, r.height() / 5, ballPaint)
        text.textAlign = Paint.Align.CENTER
        text.textSize = r.height() * (if (sub == null) 0.34f else 0.26f)
        c.drawText(label, r.centerX(), r.centerY() + if (sub == null) r.height() * 0.12f else -r.height() * 0.02f, text)
        if (sub != null) {
            text.textSize = r.height() * 0.13f
            c.drawText(sub, r.centerX(), r.centerY() + r.height() * 0.28f, text)
        }
    }

    // ---------------------------------------------------------------- menus

    private fun drawMenuBackground(c: Canvas, w: Float, h: Float) {
        val pa = PALETTES[0]
        sky.shader = LinearGradient(0f, 0f, 0f, h, pa[0], pa[1], Shader.TileMode.CLAMP)
        far.color = pa[2]; mid.color = pa[3]; ground.color = pa[4]; rock.color = pa[5]
        c.drawRect(0f, 0f, w, h, sky)
        val cam = Cam()
        cam.x = menuTime * 14.0
        cam.y = 20.0
        cam.viewMeters = BASE_METERS
        ridge(c, far, cam, 0.15, 55.0, 14.0, 0.011, h, w)
        ridge(c, mid, cam, 0.3, 38.0, 9.0, 0.019, h, w)
        ballPaint.color = pa[4]
        c.drawRect(0f, h * 0.9f, w, h, ballPaint)
        ballPaint.color = pa[5]
        c.drawRect(0f, h * 0.95f, w, h, ballPaint)
    }

    private fun drawHome(c: Canvas, w: Float, h: Float) {
        drawMenuBackground(c, w, h)
        text.textAlign = Paint.Align.CENTER
        text.textSize = h / 6f
        c.drawText("HILL ROLLER", w / 2, h * 0.24f, text)
        text.textSize = h / 22f
        c.drawText("Hold to get heavy. Roll further.", w / 2, h * 0.32f, text)

        val bw = w * 0.27f
        val bh = h * 0.32f
        val gap = w * 0.03f
        val left = (w - 3 * bw - 2 * gap) / 2
        val top = h * 0.42f
        for (i in 0 until 3) btn[i].set(left + i * (bw + gap), top, left + i * (bw + gap) + bw, top + bh)
        bigButton(c, btn[0], "Standard", Color.rgb(60, 170, 90), "Roll as far as you can")
        bigButton(c, btn[1], "You vs Bot", Color.rgb(230, 130, 40), "Race a computer ball")
        bigButton(c, btn[2], "You vs Friend", Color.rgb(70, 110, 200), "Split screen, 2 players")

        text.textSize = h / 24f
        c.drawText("Best $best m     Highest $bestHeight m     Coins $totalCoins", w / 2, h * 0.86f, text)

        val bs = h / 9f
        soundRect.set(w - bs - 16f, 16f, w - 16f, 16f + bs)
        iconButton(c, soundRect)
        text.textSize = bs * 0.7f
        c.drawText("♪", soundRect.centerX(), soundRect.centerY() + bs * 0.25f, text)
        if (!sfx.enabled) {
            mark.strokeWidth = 6f
            c.drawLine(soundRect.left + bs * 0.2f, soundRect.bottom - bs * 0.2f, soundRect.right - bs * 0.2f, soundRect.top + bs * 0.2f, mark)
        }
    }

    private fun drawLevels(c: Canvas, w: Float, h: Float) {
        drawMenuBackground(c, w, h)
        text.textAlign = Paint.Align.CENTER
        text.textSize = h / 8f
        c.drawText("You vs Bot", w / 2, h * 0.22f, text)
        text.textSize = h / 22f
        c.drawText("First to ${RACE_LENGTH.toInt()} m wins. Rolling backwards or getting stuck loses.", w / 2, h * 0.30f, text)

        val bw = w * 0.27f
        val bh = h * 0.30f
        val gap = w * 0.03f
        val left = (w - 3 * bw - 2 * gap) / 2
        val top = h * 0.40f
        for (i in 0 until 3) btn[i].set(left + i * (bw + gap), top, left + i * (bw + gap) + bw, top + bh)
        bigButton(c, btn[0], "Easy", Color.rgb(60, 170, 90), "Slow and makes mistakes")
        bigButton(c, btn[1], "Medium", Color.rgb(230, 130, 40), "Solid, but beatable")
        bigButton(c, btn[2], "Hard", Color.rgb(200, 60, 60), "Fast and rarely fails")

        backRect.set(left, h * 0.78f, left + bw * 0.6f, h * 0.78f + h * 0.12f)
        bigButton(c, backRect, "Back", Color.rgb(90, 90, 110))
    }
}
