package com.vortex.model

import java.util.Locale
import kotlin.math.round

/** One adjustable control (fader, knob, slider). The same key is used in the database and in messages. */
data class Spec(
    val key: String,
    val label: String,
    val min: Double,
    val max: Double,
    val unit: String,
    val step: Double,
    val section: String,
    val prefix: String = "",
) {
    val def: Double get() = if (min <= 0.0 && max >= 0.0) 0.0 else min

    /** Name used in messages, e.g. "Vocal 2 Hi". */
    val title: String get() = if (prefix.isEmpty()) label else "$prefix $label"

    fun snap(v: Double): Double {
        val c = v.coerceIn(min, max)
        return (round(c / step) * step).let { (round(it * 100.0) / 100.0).coerceIn(min, max) }
    }

    fun fmt(v: Double?): String {
        if (v == null) return "not set"
        if (unit == "dB" && min == -60.0 && v <= -60.0) return "-∞"
        val n = if (step < 1.0) String.format(Locale.US, "%.1f", v) else v.toInt().toString()
        val sign = if (unit == "dB" && v > 0) "+" else ""
        val u = if (unit.isNotEmpty() && unit != "pos") " $unit" else ""
        return "$sign$n$u"
    }
}

object Marks {
    val channel = listOf(10.0 to "10", 5.0 to "5", 0.0 to "0", -5.0 to "5", -10.0 to "10", -15.0 to "15",
        -20.0 to "20", -30.0 to "30", -50.0 to "50", -60.0 to "∞")
    val band = listOf(10.0 to "10", 5.0 to "5", 0.0 to "0", -5.0 to "5", -10.0 to "10")
    val geq = listOf(10.0 to "+10", 3.0 to "+3", 0.0 to "0", -3.0 to "-3", -10.0 to "-10")
}

object Specs {
    /** Hi, kHz, Mid, Lo knobs of one vocal line (each line has its own EQ). Scale of the kHz knob is not verified. */
    fun eq(id: Long, name: String) = listOf(
        Spec("eq.$id.hi", "Hi", -15.0, 15.0, "dB", 0.1, "Vocal EQ", name),
        Spec("eq.$id.khz", "kHz", 0.0, 10.0, "pos", 0.05, "Vocal EQ", name),
        Spec("eq.$id.mid", "Mid", -15.0, 15.0, "dB", 0.1, "Vocal EQ", name),
        Spec("eq.$id.lo", "Lo", -15.0, 15.0, "dB", 0.1, "Vocal EQ", name),
    )
    val eqSuffixes = listOf("hi", "khz", "mid", "lo")

    /** The real Master A fader is a red pair (L and R). */
    val masterL = Spec("ma.l", "Master A L", -60.0, 10.0, "dB", 0.1, "Faders")
    val masterR = Spec("ma.r", "Master A R", -60.0, 10.0, "dB", 0.1, "Faders")
    val fx1 = Spec("fx1", "FX1", -60.0, 10.0, "dB", 0.1, "Faders")
    val fx2 = Spec("fx2", "FX2", -60.0, 10.0, "dB", 0.1, "Faders")
    val masterB = Spec("mb", "Master B", 0.0, 10.0, "pos", 0.05, "Master B")
    // Scale of the band volume faders is not verified yet.
    val bands = listOf(
        Spec("bv.mon", "Monitor", -10.0, 10.0, "", 0.1, "Band volume"),
        Spec("bv.thx", "ThoorX", -10.0, 10.0, "", 0.1, "Band volume"),
        Spec("bv.verb", "Verb", -10.0, 10.0, "", 0.1, "Band volume"),
        Spec("bv.act", "Active", -10.0, 10.0, "", 0.1, "Band volume"),
    )
    val geq = listOf("63Hz", "125Hz", "250Hz", "400Hz", "630Hz", "1kHz", "1.6kHz", "2.5kHz", "4kHz", "6.3kHz", "12kHz")
        .mapIndexed { i, n -> Spec("geq$i", n, -10.0, 10.0, "dB", 0.5, "Graphic EQ") }

    fun vocal(id: Long, name: String) = Spec("voc.$id", name, -60.0, 10.0, "dB", 0.1, "Vocal fader")

    /** Mono Delay (effect 60) edit parameters, ranges given by the user. */
    fun fx(n: Int) = listOf(
        Spec("fx$n.delay", "Delay time", 0.0, 1500.0, "ms", 5.0, "Effects", "FX$n"),
        Spec("fx$n.feedback", "Feedback", 0.0, 90.0, "%", 1.0, "Effects", "FX$n"),
        Spec("fx$n.hicut", "High cut", 5.0, 15.0, "kHz", 0.5, "Effects", "FX$n"),
        Spec("fx$n.lowcut", "Low cut", 50.0, 400.0, "Hz", 10.0, "Effects", "FX$n"),
    )
}
