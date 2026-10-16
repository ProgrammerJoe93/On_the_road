package com.joegineered.ontheroad.cameras

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import com.joegineered.ontheroad.R
import com.stadiamaps.ferrostar.core.AndroidTtsObserver

/** Plays a chime and speaks a camera warning through the navigation voice. */
class CameraWarningAnnouncer(
    private val context: Context,
    private val ttsObserver: AndroidTtsObserver,
) {
  private val toneGenerator by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }

  fun announce(warning: CameraWarning) {
    if (ttsObserver.isMuted) return

    toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)

    val limit = warning.camera.maxSpeedKmh
    val text =
        if (limit != null) context.getString(R.string.speed_camera_ahead_limit, limit)
        else context.getString(R.string.speed_camera_ahead)
    ttsObserver.tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "camera-${warning.camera.id}")
  }
}
