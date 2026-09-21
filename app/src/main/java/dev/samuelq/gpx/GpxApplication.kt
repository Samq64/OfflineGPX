package dev.samuelq.gpx

import android.app.Application
import dev.samuelq.gpx.di.AppContainer
import org.maplibre.android.MapLibre

class GpxApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        // Before anything can ask for a map. MapLibre's ConnectivityReceiver calls
        // getActiveNetworkInfo() unguarded, which throws without ACCESS_NETWORK_STATE - a
        // permission this app strips. Also just true: with no INTERNET this process can't
        // open a socket anyway.
        MapLibre.getInstance(this)
        MapLibre.setConnected(false)

        container = AppContainer(this)
        container.recoverAbandonedRecording()
        container.loadOfflineMaps()
    }
}
