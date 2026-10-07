package dev.samuelq.gpx

import android.app.Application
import dev.samuelq.gpx.di.AppContainer

class GpxApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        container = AppContainer(this)
        container.warmSettings()
        container.claimAbandonedRecording()
        container.loadOfflineMaps()
        container.trackRepository.purgeEdits()
    }
}
