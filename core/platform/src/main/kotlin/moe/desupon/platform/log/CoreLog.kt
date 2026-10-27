package moe.desupon.platform.log

import android.util.Log

/** Core log levels; the extended core inserts NOTICE at 4, so INFO..TRACE sit one above upstream. */
enum class CoreLogLevel(val core: Int, val androidPriority: Int) {
    PANIC(0, Log.ASSERT),
    FATAL(1, Log.ASSERT),
    ERROR(2, Log.ERROR),
    WARN(3, Log.WARN),
    NOTICE(4, Log.INFO),
    INFO(5, Log.INFO),
    DEBUG(6, Log.DEBUG),
    TRACE(7, Log.VERBOSE),
    ;

    companion object {
        fun fromCore(level: Int): CoreLogLevel = when {
            level <= 0 -> PANIC
            level >= TRACE.core -> TRACE
            else -> entries[level]
        }
    }
}

/** [message] is already masked. */
data class CoreLogEntry(val level: CoreLogLevel, val message: String)
