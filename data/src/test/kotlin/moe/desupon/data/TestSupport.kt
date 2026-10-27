package moe.desupon.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import moe.desupon.brain.port.Clock
import moe.desupon.data.db.DesuponDatabase
import moe.desupon.data.db.SeedDefaults

internal class FakeClock(var now: Long = 1_700_000_000_000L, var elapsed: Long = 1_000_000L) : Clock {
    override fun nowMs(): Long = now

    override fun elapsedMs(): Long = elapsed

    fun advance(ms: Long) {
        now += ms
        elapsed += ms
    }
}

internal fun inMemoryDb(seed: Boolean = false): DesuponDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DesuponDatabase::class.java)
        .allowMainThreadQueries()
        .apply { if (seed) addCallback(SeedDefaults) }
        .build()

/** Synthetic share links; hosts are reserved example domains. */
internal object Links {
    fun trojan(name: String, host: String = "${name.lowercase()}.example.com") =
        "trojan://pw-${name.lowercase()}@$host:443?sni=$host#$name"

    fun body(vararg names: String) = names.joinToString("\n") { trojan(it) }
}
