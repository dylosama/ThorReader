package dev.thorreader.bookloader.music

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.thorreader.bookloader.BookloaderApp
import dev.thorreader.bookloader.TAG
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Bookloader's background music (the original [Song], rendered once and cached on disk, then
 * looped by one AudioTrack fed by a small writer thread - no CPU work beyond copying samples).
 * Follows audio focus, pauses when Bookloader isn't on screen, releases everything after a
 * minute paused, stops when the app closes. All calls on the main thread. No wakelocks.
 */
object Music {
    private const val CACHE = "music-v1.pcm"
    private const val FADE_IN_MS = 1500L
    private const val FADE_OUT_MS = 600L
    private const val RELEASE_AFTER_MS = 60_000L
    private const val DUCK = 0.25f

    private val main = Handler(Looper.getMainLooper())
    private val app get() = BookloaderApp.instance
    private val audio get() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Bookloader is on screen. */
    var foreground = false
        private set

    private var pcm: ShortArray? = null
    private var loading = false
    private var track: AudioTrack? = null
    private var writer: Thread? = null
    @Volatile private var running = false
    private var playing = false
    private var fade = 0f
    private var duck = 1f
    private var hasFocus = false
    /** Paused for a call / a short sound: resume when focus comes back. */
    private var resumeOnGain = false
    /** Paused because another app took over the audio: no sound effects then either. */
    var focusLost = false
        private set

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    focusLost = false
                    if (duck != 1f) { duck = 1f; applyVolume(); Log.i(TAG, "Music back to full volume (notification over)") }
                    if (resumeOnGain) { resumeOnGain = false; start("audio focus came back") }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    duck = DUCK
                    applyVolume()
                    Log.i(TAG, "Music quieter (a notification is playing)")
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    focusLost = true
                    resumeOnGain = true
                    pause("a call or another app needs the audio for a moment", keepFocus = true)
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    focusLost = true
                    resumeOnGain = false
                    pause("another app is playing audio")
                }
            }
        }
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener(focusListener, main)
        .setWillPauseWhenDucked(false)
        .build()

    private val releaseLater = Runnable { release("paused for over a minute") }

    // ---- what the app tells us ------------------------------------------------------------------

    fun onForeground() {
        foreground = true
        if (app.musicOn) start("Bookloader on screen")
    }

    fun onBackground(reason: String) {
        foreground = false
        pause(reason)
    }

    fun onClosed() {
        foreground = false
        release("app closed")
    }

    fun setEnabled(on: Boolean) {
        app.updateMusicOn(on)
        if (on) start("music switched on") else pause("music switched off")
    }

    fun volumeChanged() = applyVolume()

    // ---- playing ------------------------------------------------------------------------------

    fun start(reason: String) {
        if (!app.musicOn || !foreground || playing) return
        main.removeCallbacks(releaseLater)
        if (!hasFocus) {
            if (audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.i(TAG, "Music not started: no audio focus (another app is playing)")
                return
            }
            hasFocus = true
            focusLost = false
        }
        val samples = pcm
        if (samples == null) {
            loadThen(reason)
            return
        }
        val t = track ?: create(samples)
        playing = true
        fade = 0f
        applyVolume()
        t.play()
        Log.i(TAG, "Music start ($reason)")
        rampTo(1f, FADE_IN_MS) {}
    }

    fun pause(reason: String, keepFocus: Boolean = false) {
        main.removeCallbacks(releaseLater)
        if (!keepFocus && hasFocus) {
            audio.abandonAudioFocusRequest(focusRequest)
            hasFocus = false
        }
        if (!playing) {
            if (track != null) main.postDelayed(releaseLater, RELEASE_AFTER_MS)
            return
        }
        playing = false
        Log.i(TAG, "Music pause ($reason)")
        rampTo(0f, FADE_OUT_MS) {
            if (!playing) track?.pause()
        }
        main.postDelayed(releaseLater, RELEASE_AFTER_MS)
    }

    /** Frees the player and the music in memory (the rendered file stays cached on disk). */
    private fun release(reason: String) {
        main.removeCallbacks(releaseLater)
        val hadSomething = track != null || pcm != null
        playing = false
        running = false
        track?.let { t -> runCatching { t.pause(); t.flush(); t.release() } }
        track = null
        writer = null
        pcm = null
        if (hasFocus) {
            audio.abandonAudioFocusRequest(focusRequest)
            hasFocus = false
        }
        if (hadSomething) Log.i(TAG, "Music stop ($reason)")
    }

    private fun loadThen(reason: String) {
        if (loading) return
        loading = true
        thread(name = "ThorBookloader-music", priority = Thread.MIN_PRIORITY) {
            val samples = loadOrRender()
            main.post {
                loading = false
                pcm = samples
                start(reason)
            }
        }
    }

    /** The rendered loop from the disk cache, or rendered now (once) and cached. */
    private fun loadOrRender(): ShortArray {
        val f = File(app.cacheDir, CACHE)
        if (f.length() == Song.loopSamples * 2L) {
            runCatching {
                val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                return ShortArray(bb.remaining()).also { bb.get(it) }
            }
        }
        val t0 = System.currentTimeMillis()
        val s = Song.render()
        Log.i(TAG, "Music rendered in ${System.currentTimeMillis() - t0} ms (${s.size / Song.RATE} s loop)")
        runCatching {
            val bb = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bb.asShortBuffer().put(s)
            f.writeBytes(bb.array())
        }
        return s
    }

    private fun create(samples: ShortArray): AudioTrack {
        val min = AudioTrack.getMinBufferSize(Song.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(Song.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 4, Song.RATE)) // ~0.5 s
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        running = true
        // The writer just copies the loop into the track, round and round; it waits (blocked
        // in write) while the track is paused, and ends when the track is released.
        writer = thread(name = "ThorBookloader-music-out", priority = Thread.NORM_PRIORITY) {
            var pos = 0
            val chunk = Song.RATE / 4
            while (running) {
                val n = minOf(chunk, samples.size - pos)
                val w = runCatching { t.write(samples, pos, n, AudioTrack.WRITE_BLOCKING) }.getOrDefault(-1)
                if (w < 0) break
                pos = (pos + w) % samples.size // seamless: straight from the last sample back to the first
            }
        }
        return t
    }

    private fun applyVolume() {
        track?.setVolume((app.musicVolume * fade * duck).coerceIn(0f, 1f))
    }

    private var rampToken = 0

    /** Fades [fade] to [target] in small steps on the main thread. */
    private fun rampTo(target: Float, ms: Long, done: () -> Unit) {
        val token = ++rampToken
        val steps = (ms / 30).coerceAtLeast(1)
        val from = fade
        var i = 0
        fun step() {
            if (token != rampToken) return
            i++
            fade = from + (target - from) * (i.toFloat() / steps)
            applyVolume()
            if (i < steps) main.postDelayed({ step() }, 30) else done()
        }
        step()
    }
}

/** The short sound effects ("bloop" per book sent, chime when the queue is done). */
object Sounds {
    private val main = Handler(Looper.getMainLooper())
    private val bloop by lazy { Song.bloop() }
    private val chime by lazy { Song.chime() }

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun bookSent() = main.post { play(bloop, "bloop") }
    fun batchDone() = main.postDelayed({ play(chime, "chime") }, 450)

    /** Only while Bookloader is on screen, effects are on, and no other app has the audio. */
    private fun play(s: ShortArray, what: String) {
        val app = BookloaderApp.instance
        if (!app.sfxOn || !Music.foreground || Music.focusLost) return
        runCatching {
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(Song.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(s.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(s, 0, s.size)
            t.setVolume(0.7f)
            t.play()
            main.postDelayed({ runCatching { t.release() } }, s.size * 1000L / Song.RATE + 300)
        }.onFailure { Log.w(TAG, "Sound effect '$what' failed: ${it.message}") }
    }
}
