package dev.samuelq.gpx.ui

import androidx.compose.material3.SnackbarHostState
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test

class UndoSnackbarTest {

    private val host = SnackbarHostState()
    private var outcome: String? = null

    private fun CoroutineScope.offer(message: String = "Deleted") = launch {
        host.showUndo(
            message,
            "Undo",
            indefinite = false,
            onUndo = { outcome = "undo $message" },
            onCommit = { outcome = "commit $message" },
        )
    }

    private suspend fun shown(): String? {
        repeat(10) { yield() }
        return host.currentSnackbarData?.visuals?.message
    }

    @Test
    fun `undo restores`() = runBlocking {
        val undo = offer()
        assertEquals("Deleted", shown())
        host.currentSnackbarData!!.performAction()
        undo.join()
        assertEquals("undo Deleted", outcome)
    }

    @Test
    fun `a timeout commits`() = runBlocking {
        val undo = offer()
        assertEquals("Deleted", shown())
        host.currentSnackbarData!!.dismiss()
        undo.join()
        assertEquals("commit Deleted", outcome)
    }

    @Test
    fun `a newer message defers rather than commits`() = runBlocking {
        val undo = offer()
        assertEquals("Deleted", shown())
        val other = launch {
            host.makeWay()
            host.showSnackbar("Location is off")
        }
        assertEquals("Location is off", shown())
        assertNull(outcome)

        host.currentSnackbarData!!.dismiss()
        other.join()
        assertEquals("Deleted", shown())
        host.currentSnackbarData!!.performAction()
        undo.join()
        assertEquals("undo Deleted", outcome)
    }

    @Test
    fun `a newer undo commits the older`() = runBlocking {
        val first = offer("First")
        assertEquals("First", shown())
        val second = offer("Second")
        first.join()
        assertEquals("commit First", outcome)
        assertEquals("Second", shown())
        host.currentSnackbarData!!.performAction()
        second.join()
        assertEquals("undo Second", outcome)
    }

    @Test
    fun `cancelling commits`() = runBlocking {
        val undo = offer()
        assertEquals("Deleted", shown())
        undo.cancel()
        undo.join()
        assertEquals("commit Deleted", outcome)
    }
}
