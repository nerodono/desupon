package moe.desupon.platform.log

import android.util.Log
import kotlin.test.Test
import kotlin.test.assertEquals

class CoreLogLevelTest {
    @Test
    fun extendedLevelsIncludeNotice() {
        assertEquals(CoreLogLevel.WARN, CoreLogLevel.fromCore(3))
        assertEquals(CoreLogLevel.NOTICE, CoreLogLevel.fromCore(4))
        assertEquals(CoreLogLevel.INFO, CoreLogLevel.fromCore(5))
        assertEquals(CoreLogLevel.DEBUG, CoreLogLevel.fromCore(6))
        assertEquals(CoreLogLevel.TRACE, CoreLogLevel.fromCore(7))
        CoreLogLevel.entries.forEach { assertEquals(it, CoreLogLevel.fromCore(it.core)) }
    }

    @Test
    fun outOfRangeClamps() {
        assertEquals(CoreLogLevel.PANIC, CoreLogLevel.fromCore(-1))
        assertEquals(CoreLogLevel.TRACE, CoreLogLevel.fromCore(42))
    }

    @Test
    fun androidPriorities() {
        assertEquals(Log.ERROR, CoreLogLevel.ERROR.androidPriority)
        assertEquals(Log.INFO, CoreLogLevel.NOTICE.androidPriority)
        assertEquals(Log.INFO, CoreLogLevel.INFO.androidPriority)
        assertEquals(Log.VERBOSE, CoreLogLevel.TRACE.androidPriority)
    }
}
