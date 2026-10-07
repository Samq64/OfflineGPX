package dev.samuelq.gpx.data

import kotlin.coroutines.cancellation.CancellationException

/** [runCatching] for suspend work: cancellation propagates rather than becoming a failure. */
internal inline fun <T> runCancellable(block: () -> T): Result<T> =
    runCatching(block).onFailure { if (it is CancellationException) throw it }
