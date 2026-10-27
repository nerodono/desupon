package moe.desupon.vpn.service

import kotlinx.coroutines.CoroutineScope
import moe.desupon.brain.SelectorBrain
import moe.desupon.brain.port.SocketProtector
import moe.desupon.config.LocalRuleSet

/** One brain per service run; it lives on [scope] and protects its sockets through [protector]. */
fun interface BrainFactory {
    fun create(scope: CoroutineScope, protector: SocketProtector, ruleSets: Map<String, LocalRuleSet>): SelectorBrain
}
