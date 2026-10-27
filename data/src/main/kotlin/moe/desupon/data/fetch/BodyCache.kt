package moe.desupon.data.fetch

import moe.desupon.model.subscription.SubId
import java.io.File
import java.io.IOException

/** Last accepted body of each subscription, one file per subscription. */
class BodyCache(private val dir: File) {
    fun file(id: SubId): File = File(dir, "${id.value}.cache")

    fun read(id: SubId): ByteArray? = file(id).takeIf { it.isFile }?.readBytes()

    /** Atomic: readers see the old body or the new one, never a partial write. */
    @Throws(IOException::class)
    fun write(id: SubId, body: ByteArray) {
        dir.mkdirs()
        val target = file(id)
        val tmp = File(dir, "${id.value}.cache.tmp")
        tmp.writeBytes(body)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("cannot replace subscription cache")
        }
    }

    fun delete(id: SubId) {
        file(id).delete()
    }
}
