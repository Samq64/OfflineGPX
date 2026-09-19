package dev.samuelq.gpx.core.model

/**
 * Which units the reader reads.
 *
 * A display preference and nothing more. Everything stored, parsed and analysed stays SI -
 * a GPX file is metres either way, and converting on the way in would mean a track meant
 * something different depending on who imported it.
 */
enum class UnitSystem {
    METRIC,
    IMPERIAL,
}
