package moe.desupon.model

/** Sensitive text (credentials, subscription URLs, keys) that never renders through [toString]. */
@JvmInline
value class Secret(private val value: String) {
    fun raw(): String = value

    fun isEmpty(): Boolean = value.isEmpty()

    override fun toString(): String = MASK

    companion object {
        const val MASK = "***"
        val EMPTY = Secret("")
    }
}
