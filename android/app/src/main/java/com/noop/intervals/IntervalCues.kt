package com.noop.intervals

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Fork addition (Rick): phone-side audio and vibration cues for the interval timer, so it works
 * with no strap and with the phone in a pocket or armband. Voice uses the system TextToSpeech
 * engine (offline on most phones); beeps use ToneGenerator. Both request a transient, "may duck"
 * audio focus, so music or a podcast dips under the cue instead of stopping.
 *
 * Every call is best-effort: a phone with no TTS engine, or a vibrator that refuses, just stays
 * quiet rather than crashing the session.
 */
class IntervalCues(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var tone: ToneGenerator? = null
    private var focusRequest: AudioFocusRequest? = null

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun open() {
        if (tts == null) {
            tts = runCatching {
                TextToSpeech(app) { status ->
                    ttsReady = status == TextToSpeech.SUCCESS
                    if (ttsReady) {
                        tts?.setAudioAttributes(attrs)
                        runCatching { tts?.setLanguage(Locale.getDefault()) }
                        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) { abandonFocus() }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) { abandonFocus() }
                        })
                    }
                }
            }.getOrNull()
        }
        if (tone == null) {
            tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()
        }
    }

    fun close() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        ttsReady = false
        runCatching { tone?.release() }
        tone = null
        abandonFocus()
    }

    fun speak(text: String) {
        val engine = tts ?: return
        if (!ttsReady) return
        requestFocus()
        runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "interval-" + System.nanoTime())
        }
    }

    /** Short tick for the 3-2-1 countdown. */
    fun tick() = beep(ToneGenerator.TONE_PROP_BEEP, 120)

    /** Longer, higher tone at a step change. */
    fun go() = beep(ToneGenerator.TONE_PROP_BEEP2, 350)

    /** Finish fanfare. */
    fun finish() = beep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900)

    private fun beep(type: Int, ms: Int) {
        runCatching { tone?.startTone(type, ms) }
    }

    fun vibrate(pattern: LongArray) {
        runCatching {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            v?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    private fun requestFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .build()
                focusRequest = req
                audio.requestAudioFocus(req)
            }
        }
    }

    private fun abandonFocus() {
        runCatching {
            focusRequest?.let { audio.abandonAudioFocusRequest(it) }
            focusRequest = null
        }
    }
}
