package com.vortex.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Number of log-spaced frequency bins between 20 Hz and 20 kHz used everywhere (screen and network). */
const val BINS = 600

fun freqOfBin(i: Int): Double = 20.0 * 1000.0.pow(i / (BINS - 1).toDouble())

/**
 * Real audio input and FFT (operator phone only).
 * Default input is the wired 3.5 mm jack (or a USB audio device). The phone's own microphone is used only
 * when the user selects it. The mixer's line-level output must be reduced to microphone level
 * with a suitable cable or attenuator, otherwise the input clips (watch the level and CLIP indicator).
 */
object AudioEngine {
    /** dB values (re full scale) for the BINS log-spaced bins, or null when there is no signal source. */
    val spectrum = MutableStateFlow<FloatArray?>(null)
    val levelDb = MutableStateFlow(-100f)
    val clipping = MutableStateFlow(false)
    val status = MutableStateFlow("Stopped")
    val fftSize = MutableStateFlow(4096)
    val fps = MutableStateFlow(15)
    val usePhoneMic = MutableStateFlow(false)

    private const val SR = 44100
    private const val MAX_N = 16384
    private var job: Job? = null

    fun start(ctx: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            val app = ctx.applicationContext
            while (isActive) {
                capture(app)
                spectrum.value = null
                delay(1500)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        spectrum.value = null
        status.value = "Stopped"
    }

    private fun findWiredInput(am: AudioManager): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }

    private suspend fun capture(ctx: Context) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.value = "No microphone permission"
            return
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val phone = usePhoneMic.value
        val dev = if (phone) null else findWiredInput(am)
        if (!phone && dev == null) {
            status.value = "No wired input found. Plug the cable into the 3.5 mm jack, or choose Phone mic."
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = max(minBuf, SR / 2) * 2
        fun make(src: Int): AudioRecord? = try {
            AudioRecord(src, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        } catch (e: Exception) { null }
        var rec = make(MediaRecorder.AudioSource.UNPROCESSED)
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            rec = make(MediaRecorder.AudioSource.MIC)
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            status.value = "Could not open the audio input"
            return
        }
        if (dev != null) rec.setPreferredDevice(dev)
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            status.value = "Could not start recording"
            return
        }
        status.value = if (phone) "Listening: phone microphone" else "Listening: wired input"

        val ring = FloatArray(MAX_N)
        var pos = 0
        var filled = 0
        var clipUntil = 0L
        val windows = HashMap<Int, FloatArray>()
        val sums = HashMap<Int, Float>()
        var re = FloatArray(0)
        var im = FloatArray(0)
        try {
            while (currentCoroutineContext().isActive) {
                if (phone != usePhoneMic.value) break // input choice changed: restart
                val hop = max(256, SR / fps.value.coerceIn(5, 60))
                val buf = ShortArray(hop)
                var got = 0
                while (got < hop) {
                    val r = rec.read(buf, got, hop - got)
                    if (r <= 0) { status.value = "Audio input lost"; return }
                    got += r
                }
                var peak = 0f
                for (s in buf) {
                    val f = s / 32768f
                    ring[pos] = f
                    pos = (pos + 1) % MAX_N
                    peak = max(peak, abs(f))
                }
                filled = min(MAX_N, filled + hop)
                levelDb.value = 20f * log10(max(peak, 1e-5f))
                val now = System.currentTimeMillis()
                if (peak >= 0.99f) clipUntil = now + 1500
                clipping.value = now < clipUntil

                val n = fftSize.value
                if (filled < n) continue
                if (re.size != n) { re = FloatArray(n); im = FloatArray(n) }
                val w = windows.getOrPut(n) { FloatArray(n) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))).toFloat() } }
                val ws = sums.getOrPut(n) { w.sum() }
                val start = (pos - n + MAX_N) % MAX_N
                for (i in 0 until n) { re[i] = ring[(start + i) % MAX_N] * w[i]; im[i] = 0f }
                Fft.transform(re, im)
                val half = n / 2
                val db = FloatArray(half)
                for (k in 0 until half) {
                    val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                    val amp = 2f * mag / ws
                    db[k] = 20f * log10(max(amp, 1e-6f))
                }
                spectrum.value = toLogBins(db, n)
            }
        } finally {
            try { rec.stop() } catch (ignored: Exception) {}
            rec.release()
        }
    }

    /** Resample linear FFT magnitudes (dB) to BINS log-spaced bins (max inside a bin, interpolation at low frequencies). */
    private fun toLogBins(db: FloatArray, n: Int): FloatArray {
        val out = FloatArray(BINS)
        val ratio = 1000.0.pow(1.0 / (BINS - 1))
        val half = sqrt(ratio)
        val binHz = SR.toDouble() / n
        for (i in 0 until BINS) {
            val f = freqOfBin(i)
            val kLo = f / half / binHz
            val kHi = f * half / binHz
            if (kHi - kLo < 1.0) {
                val k = f / binHz
                val k0 = k.toInt().coerceIn(0, db.size - 2)
                val fr = (k - k0).toFloat().coerceIn(0f, 1f)
                out[i] = db[k0] * (1 - fr) + db[k0 + 1] * fr
            } else {
                var m = -200f
                val a = kotlin.math.ceil(kLo).toInt().coerceIn(0, db.size - 1)
                val b = kotlin.math.floor(kHi).toInt().coerceIn(0, db.size - 1)
                for (k in a..b) m = max(m, db[k])
                out[i] = m
            }
        }
        return out
    }
}
