package moe.desupon.vpn.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import moe.desupon.brain.SelectorBrain
import moe.desupon.brain.port.SocketProtector
import moe.desupon.config.LocalRuleSet

/** One brain per service run; it lives on [scope], protects its sockets through [protector] and follows the usable [ruleSets]. */
fun interface BrainFactory {
    fun create(scope: CoroutineScope, protector: SocketProtector, ruleSets: Flow<Map<String, LocalRuleSet>>): SelectorBrain
}
