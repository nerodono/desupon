package moe.desupon.feature.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Last generated config with secrets already masked; bound by `:app`, null until a config exists. */
fun interface LastConfigSource {
    fun maskedConfig(): Flow<String?>

    companion object {
        val None = LastConfigSource { flowOf(null) }
    }
}
