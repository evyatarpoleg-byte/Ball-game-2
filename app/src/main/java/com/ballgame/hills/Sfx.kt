package com.ballgame.hills

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/** Tiny synthesized sound effects (no audio files needed). Every call is wrapped so audio problems never crash the game. */
class Sfx {
    var enabled = true
    private val rate = 22050
    private val tracks = HashMap<Int, AudioTrack?>()

    companion object {
        const val COIN = 1
        const val PERFECT = 2
        const val SOFT = 3
        const val HARD = 4
        const val ROCK = 5
        const val FANFARE = 6
    }

    init {
        tracks[COIN] = make(notes(988.0 to 60, 1319.0 to 120))
        tracks[PERFECT] = make(notes(523.0 to 70, 659.0 to 70, 784.0 to 70, 1047.0 to 150))
        tracks[SOFT] = make(thump(140.0, 70.0, 90, 0.0))
        tracks[HARD] = make(thump(100.0, 50.0, 180, 0.5))
        tracks[ROCK] = make(thump(120.0, 60.0, 150, 0.8))
        tracks[FANFARE] = make(notes(523.0 to 100, 659.0 to 100, 784.0 to 100, 1047.0 to 260))
    }

    private fun notes(vararg n: Pair<Double, Int>): ShortArray {
        val out = ArrayList<Short>()
        for ((f, ms) in n) {
            val len = rate * ms / 1000
            for (i in 0 until len) {
                val env = minOf(1.0, (len - i) / (len * 0.4)) * minOf(1.0, i / 80.0)
                val v = sin(2 * PI * f * i / rate) * 0.6 + sin(4 * PI * f * i / rate) * 0.15
                out += (v * env * 9000).toInt().toShort()
            }
        }
        return out.toShortArray()
    }

    /** A falling low tone, optionally mixed with noise (0..1), fading out. */
    private fun thump(f0: Double, f1: Double, ms: Int, noise: Double): ShortArray {
        val len = rate * ms / 1000
        val rnd = Random(1)
        var phase = 0.0
        return ShortArray(len) { i ->
            val t = i.toDouble() / len
            phase += 2 * PI * (f0 + (f1 - f0) * t) / rate
            val v = sin(phase) * (1 - noise * 0.5) + (rnd.nextDouble() * 2 - 1) * noise * 0.5
            (v * (1 - t) * 14000).toInt().toShort()
        }
    }

    private fun make(data: ShortArray): AudioTrack? = try {
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(data.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(data, 0, data.size)
        t
    } catch (e: Exception) {
        null
    }

    fun play(id: Int) {
        if (!enabled) return
        try {
            val t = tracks[id] ?: return
            t.stop()
            t.reloadStaticData()
            t.play()
        } catch (e: Exception) {
            // ignore: sound is optional
        }
    }

    fun release() {
        for (t in tracks.values) try { t?.release() } catch (e: Exception) {}
        tracks.clear()
    }
}
