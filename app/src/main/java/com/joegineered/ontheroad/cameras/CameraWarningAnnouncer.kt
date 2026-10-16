package com.joegineered.ontheroad.cameras

import android.content.Context
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.voice.NavigationVoice

/** Speaks camera warnings, with a chime, through the navigation voice. */
class CameraWarningAnnouncer(
    private val context: Context,
    private val voice: NavigationVoice,
) {
  fun announce(warning: CameraWarning) {
    val limit = warning.camera.maxSpeedKmh
    val text =
        if (limit != null) context.getString(R.string.speed_camera_ahead_limit, limit)
        else context.getString(R.string.speed_camera_ahead)
    voice.announce(text, withChime = true)
  }
}
