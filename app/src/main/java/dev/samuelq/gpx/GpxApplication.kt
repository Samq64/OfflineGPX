package dev.samuelq.gpx

import android.app.Application
import dev.samuelq.gpx.di.AppContainer
import org.maplibre.android.MapLibre

class GpxApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        // Before anything can ask for a map. MapLibre expects to live in an app that has
        // ACCESS_NETWORK_STATE, and its ConnectivityReceiver calls getActiveNetworkInfo()
        // with no guard - which throws SecurityException here, because the merged manifest
        // has that permission stripped. Overriding the connectivity state makes the
        // override the answer and the ConnectivityManager call unreachable, both from
        // isConnected() and from the CONNECTIVITY_CHANGE broadcast it registers for.
        //
        // It is also true: with no INTERNET permission this process cannot open a socket,
        // so "not connected" is a statement of fact rather than a workaround.
        MapLibre.getInstance(this)
        MapLibre.setConnected(false)

        container = AppContainer(this)
        container.recoverAbandonedRecording()
        container.loadOfflineMaps()
    }
}
