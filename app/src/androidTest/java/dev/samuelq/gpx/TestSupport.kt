package dev.samuelq.gpx

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

val targetContext: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

val container get() = (targetContext.applicationContext as GpxApplication).container

/** A straight track of [points] points ~11 m and 2 s apart, with a waypoint by point [waypointAt]. */
fun sampleGpx(name: String? = "Test ride", points: Int = 20, waypointAt: Int = 15): String = buildString {
    val start = Instant.parse("2024-05-04T09:00:00Z")
    fun lat(i: Int) = 51.5 + i * 0.0001
    appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    appendLine("""<gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">""")
    if (waypointAt in 0 until points) {
        appendLine("""<wpt lat="${lat(waypointAt)}" lon="-0.1"><name>Cafe</name></wpt>""")
    }
    appendLine("<trk>")
    name?.let { appendLine("<name>$it</name>") }
    appendLine("<trkseg>")
    repeat(points) { i ->
        appendLine(
            """<trkpt lat="${lat(
                i,
            )}" lon="-0.1"><ele>${10 + i}</ele><time>${start.plusSeconds(2L * i)}</time></trkpt>""",
        )
    }
    appendLine("</trkseg></trk></gpx>")
}

/** Writes [content] under the cache, out of the app's own track directories. */
fun fixture(name: String, content: String): File =
    File(targetContext.cacheDir, "androidTest").apply { mkdirs() }.resolve(name).apply { writeText(content) }

/** Polls [condition] until true or [timeoutMillis] lapses. */
fun waitFor(timeoutMillis: Long = 10_000, message: String = "condition", condition: suspend () -> Boolean) =
    runBlocking {
        try {
            withTimeout(timeoutMillis) { while (!condition()) delay(50) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $message", e)
        }
    }

/** Runs a shell command as the shell user, waiting for it to finish. */
fun shell(command: String): String {
    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
}
