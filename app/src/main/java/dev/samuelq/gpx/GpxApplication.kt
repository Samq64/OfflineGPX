package dev.samuelq.gpx

import android.app.Application
import dev.samuelq.gpx.di.AppContainer
import org.mapsforge.map.android.graphics.AndroidGraphicFactory

class GpxApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        // Mapsforge draws through a single static graphics factory, and every map object
        // built before this exists throws. Nothing here touches the network: the renderer
        // reads the files the user imported and has no other source.
        AndroidGraphicFactory.createInstance(this)

        container = AppContainer(this)
        container.recoverAbandonedRecording()
        container.loadOfflineMaps()
    }
}
