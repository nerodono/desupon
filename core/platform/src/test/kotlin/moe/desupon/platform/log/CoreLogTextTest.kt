package moe.desupon.platform.log

import kotlin.test.Test
import kotlin.test.assertEquals

class CoreLogTextTest {
    @Test
    fun coloursAndTheLevelPrefixGo() {
        // As the core's platform formatter writes them.
        assertEquals("outbound/juicity[n-1]: dbg line", CoreLogText.clean("\u001B[37mDEBUG\u001B[0m[0000] outbound/juicity[n-1]: dbg line"))
        assertEquals(
            "[3180307707 0ms] outbound/juicity[n-1]: err with id",
            CoreLogText.clean("\u001B[31mERROR\u001B[0m[0012] [\u001B[38;5;195m3180307707\u001B[0m 0ms] outbound/juicity[n-1]: err with id"),
        )
    }

    @Test
    fun linesWithoutPrefixStay() {
        assertEquals("wake: slept 30s", CoreLogText.clean("wake: slept 30s"))
        assertEquals("", CoreLogText.clean(null))
    }

    @Test
    fun secretsAreMasked() {
        assertEquals("dial: password=*** failed", CoreLogText.clean("\u001B[33mWARN\u001B[0m[0001] dial: password=hunter2 failed"))
    }
}
