package com.joegineered.ontheroad

import android.app.Application
import uniffi.ferrostar.createFerrostarLogger

class OtrApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    AppGraph.init(this)
    createFerrostarLogger()
  }
}
