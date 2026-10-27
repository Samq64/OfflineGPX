package dev.samuelq.gpx.data.record

import android.app.ApplicationExitInfo
import androidx.annotation.StringRes
import dev.samuelq.gpx.R

/**
 * Why a recording stopped unsaved, as told to the user. Names are persisted in claimed logs'
 * file names.
 */
enum class Interruption(@param:StringRes val messageRes: Int) {
    CRASH(R.string.record_interrupted_crash),
    ANR(R.string.record_interrupted_anr),
    LOW_MEMORY(R.string.record_interrupted_memory),

    /** By the user, or most often by a battery saver. */
    FORCE_STOPPED(R.string.record_interrupted_force_stopped),

    /** From the active apps list, so the user knows. */
    STOPPED(R.string.record_interrupted_stopped),
    PERMISSION(R.string.record_interrupted_permission),
    UPDATED(R.string.record_interrupted_updated),

    /** A shutdown leaves no exit record, only a log written before boot. */
    REBOOT(R.string.record_interrupted_reboot),

    /** Claimed with the app running: a save or a write failed. */
    UNSAVED(R.string.record_interrupted_unsaved),

    /** No record, or one that says nothing useful. */
    UNKNOWN(R.string.record_interrupted_unknown),
    ;

    companion object {
        fun of(reason: Int): Interruption = when (reason) {
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            -> CRASH
            ApplicationExitInfo.REASON_ANR -> ANR
            ApplicationExitInfo.REASON_LOW_MEMORY -> LOW_MEMORY
            ApplicationExitInfo.REASON_USER_REQUESTED -> FORCE_STOPPED
            ApplicationExitInfo.REASON_USER_STOPPED -> STOPPED
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> PERMISSION
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> UPDATED
            else -> UNKNOWN
        }
    }
}
