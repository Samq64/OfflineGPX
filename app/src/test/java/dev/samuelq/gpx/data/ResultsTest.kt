package dev.samuelq.gpx.data

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ResultsTest {

    @Test
    fun `a value or a failure is caught as runCatching would`() {
        assertEquals(1, runCancellable { 1 }.getOrNull())
        assertIs<IOException>(runCancellable { throw IOException() }.exceptionOrNull())
    }

    @Test
    fun `cancellation is rethrown`() {
        assertFailsWith<CancellationException> { runCancellable { throw CancellationException() } }
    }
}
