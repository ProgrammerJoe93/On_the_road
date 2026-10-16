package com.joegineered.ontheroad.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.config.AppConfig
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.AndroidTtsStatusListener
import com.stadiamaps.ferrostar.core.SpokenInstructionObserver
import java.util.Locale
import java.util.UUID
import uniffi.ferrostar.SpokenInstruction

/**
 * The navigation voice: turn instructions (spoken by Ferrostar through [ttsObserver]) and the
 * app's own announcements such as speed camera warnings.
 *
 * Everything is spoken as navigation guidance, so Android ducks music and Android Auto mixes it
 * like other navigation prompts, and it all goes through one queue, so prompts never overlap.
 */
class NavigationVoice(private val context: Context, val ttsObserver: AndroidTtsObserver) {

  /** Whether Ferrostar's turn instructions are spoken; [announce] speaks either way. */
  @Volatile var turnInstructionsSpoken = true

  /** Multiplier of the engine's normal speech rate; applied when the engine is ready. */
  @Volatile
  var speechRate = 1.0f
    set(value) {
      field = value
      ttsObserver.tts?.setSpeechRate(value)
    }

  /**
   * What Ferrostar speaks turn instructions through: [ttsObserver], skipping them when they're
   * turned off. Muting is still the observer's own, so the mute button silences everything.
   */
  val instructionObserver: SpokenInstructionObserver =
      object : SpokenInstructionObserver by ttsObserver {
        override fun onSpokenInstructionTrigger(spokenInstruction: SpokenInstruction) {
          if (turnInstructionsSpoken) ttsObserver.onSpokenInstructionTrigger(spokenInstruction)
        }
      }

  private val statusListener =
      object : AndroidTtsStatusListener {
        override fun onTtsInitialized(tts: TextToSpeech?, status: Int) {
          if (tts == null) {
            Log.e(TAG, "TTS engine unavailable (status $status); voice guidance disabled")
            return
          }
          tts.setAudioAttributes(
              AudioAttributes.Builder()
                  .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                  .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                  .build()
          )
          val locale = chooseLocale(tts)
          tts.language = locale
          tts.setSpeechRate(speechRate)
          tts.addEarcon(CAMERA_EARCON, context.packageName, R.raw.camera_chime)
          Log.i(TAG, "Voice guidance ready: $locale")
        }

        override fun onTtsSpeakError(utteranceId: String, status: Int) {
          Log.e(TAG, "Could not speak utterance $utteranceId: $status")
        }

        override fun onTtsShutdownAndRelease() {
          Log.i(TAG, "TTS released")
        }
      }

  fun start() {
    ttsObserver.statusObserver = statusListener
    ttsObserver.start()
  }

  /** Speaks [text] after the navigation prompts already queued, optionally after a chime. */
  fun announce(text: String, withChime: Boolean = false) {
    if (ttsObserver.isMuted) return
    if (withChime) {
      ttsObserver.tts?.playEarcon(CAMERA_EARCON, TextToSpeech.QUEUE_ADD, null, "chime-${UUID.randomUUID()}")
    }
    // Going through the observer gives the same audio focus handling as turn instructions.
    ttsObserver.onSpokenInstructionTrigger(
        SpokenInstruction(
            text = text,
            ssml = null,
            triggerDistanceBeforeManeuver = 0.0,
            utteranceId = UUID.randomUUID(),
        )
    )
  }

  /**
   * The instruction language when a voice for it is installed; otherwise English, which every
   * engine supports, rather than staying silent.
   */
  private fun chooseLocale(tts: TextToSpeech): Locale {
    val preferred = Locale.forLanguageTag(AppConfig.instructionLanguage())
    val available = tts.isLanguageAvailable(preferred)
    if (available >= TextToSpeech.LANG_AVAILABLE) return preferred
    Log.w(TAG, "No TTS voice for $preferred (status $available); falling back to English")
    return Locale.US
  }

  private companion object {
    const val TAG = "NavigationVoice"
    const val CAMERA_EARCON = "[speed_camera]"
  }
}
