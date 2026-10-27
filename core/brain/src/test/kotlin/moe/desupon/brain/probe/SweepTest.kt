@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package moe.desupon.brain.probe

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.desupon.brain.FakeClock
import moe.desupon.brain.Nodes
import moe.desupon.model.node.NodeId
import moe.desupon.model.probe.ProbeResult
import moe.desupon.model.probe.ProbeStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SweepTest {
    @Test
    fun `batches start in order, at most parallel at once, show only what is in flight and land one by one`() = runTest {
        val nodes = (1..7).map { Nodes.ss("s$it") }
        val activity = ProbeActivity(FakeClock(testScheduler))
        val gates = List(4) { CompletableDeferred<Unit>() }
        val started = mutableListOf<List<String>>()
        val out = Channel<Map<NodeId, ProbeResult>>(Channel.UNLIMITED)
        val job = launch {
            runSweep(nodes, batch = 2, parallel = 2, activity = activity, out = out) { batch ->
                started += batch.map { it.displayName }
                gates[started.size - 1].await()
                batch.associate { it.id to ProbeResult.Ok(ProbeStage.GET204, 50) }
            }
        }
        runCurrent()
        assertEquals(listOf(listOf("s1", "s2"), listOf("s3", "s4")), started)
        assertEquals(nodes.take(4).map { it.id }.toSet(), activity.probing.value)

        gates[1].complete(Unit)
        runCurrent()
        assertEquals(setOf(nodes[2].id, nodes[3].id), out.receive().keys, "a finished batch lands before earlier ones")
        assertEquals(listOf("s5", "s6"), started.last())
        assertEquals(setOf(nodes[0].id, nodes[1].id, nodes[4].id, nodes[5].id), activity.probing.value)

        gates[0].complete(Unit)
        gates[2].complete(Unit)
        runCurrent()
        gates[3].complete(Unit)
        runCurrent()
        job.join()
        val rest = buildList { for (r in out) add(r) }
        assertEquals(3, rest.size)
        assertTrue(activity.probing.value.isEmpty())
        assertTrue(out.isClosedForReceive)
    }
}
