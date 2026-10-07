package app.syncwake.ring

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import app.syncwake.R

/**
 * Plays the alarm on the alarm stream with a guaranteed fallback chain:
 *
 *   custom sound -> bundled fallback (inside the APK) -> system default alarm
 *
 * A source that fails to open, prepare or decode — including mid-playback errors — moves on to
 * the next one. The bundled sound is read from APK resources, so it is available offline and
 * during Direct Boot, when content URIs often are not.
 */
class AlarmAudioPlayer(private val context: Context) {
    private sealed interface Source {
        data class Custom(val uri: Uri) : Source
        data object Bundled : Source
        data object SystemDefault : Source
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { /* alarms keep playing regardless of focus changes */ }
        .build()

    private var player: MediaPlayer? = null
    private var currentSource: Source? = null
    private var remaining: ArrayDeque<Source> = ArrayDeque()
    private var volume = 1f

    /** Name of the source currently playing, for diagnostics and tests. */
    var playing: String? = null
        private set

    /** True while the custom (or voice) sound is the one actually playing. */
    val playingCustom: Boolean get() = currentSource is Source.Custom

    fun start(customUri: Uri?) {
        stop()
        remaining = ArrayDeque(listOfNotNull(customUri?.let { Source.Custom(it) }, Source.Bundled, Source.SystemDefault))
        audioManager.requestAudioFocus(focusRequest)
        playNext()
    }

    /** Switch to the built-in alarm tone (system default as its backup), keeping the current volume. */
    fun playDefault() {
        stop()
        remaining = ArrayDeque(listOf(Source.Bundled, Source.SystemDefault))
        audioManager.requestAudioFocus(focusRequest)
        playNext()
    }

    /** 1.0 while ringing; lowered (not muted) while the user works on the challenge. */
    fun setVolume(level: Float) {
        volume = level.coerceIn(0f, 1f)
        player?.setVolume(volume, volume)
    }

    fun stop() {
        player?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        player = null
        currentSource = null
        playing = null
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun playNext() {
        while (remaining.isNotEmpty()) {
            val source = remaining.removeFirst()
            if (tryPlay(source)) return
        }
        // Every source failed. The ringing service keeps vibrating and showing the full-screen alarm.
        Log.e(TAG, "All alarm audio sources failed")
    }

    private fun tryPlay(source: Source): Boolean {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(attributes)
            when (source) {
                is Source.Custom -> mp.setDataSource(context, source.uri)
                Source.Bundled -> context.resources.openRawResourceFd(R.raw.fallback_alarm).use { mp.setDataSource(it) }
                Source.SystemDefault -> {
                    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: return false.also { mp.release() }
                    mp.setDataSource(context, uri)
                }
            }
            mp.isLooping = true
            mp.setOnErrorListener { failed, what, extra ->
                Log.w(TAG, "Playback error $what/$extra on $source; falling back")
                failed.release()
                if (player === failed) {
                    player = null
                    currentSource = null
                    playNext()
                }
                true
            }
            mp.prepare()
            mp.setVolume(volume, volume)
            mp.start()
            player = mp
            currentSource = source
            playing = source.toString()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cannot play $source", e)
            mp.release()
            false
        }
    }

    private companion object {
        const val TAG = "AlarmAudioPlayer"
    }
}
