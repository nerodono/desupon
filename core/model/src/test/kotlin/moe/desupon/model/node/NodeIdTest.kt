package moe.desupon.model.node

import moe.desupon.model.Secret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NodeIdTest {
    private val vless = NodeKey(
        protocol = Protocol.VLESS,
        host = "example.com",
        port = 443,
        credential = Secret("00000000-0000-4000-8000-000000000001"),
        transport = "xhttp",
        path = "/xh",
        sni = "example.com",
    )

    // Pinned vectors: a change here re-keys every stored node and its probe history.
    @Test
    fun `hash is pinned`() {
        assertEquals("833be5c507e9bca0570f6ac14d5b34c7bec59312cc6cd611d645ab8962817ef1", vless.id.hash)
        val hy2 = NodeKey(
            protocol = Protocol.HYSTERIA2,
            host = "[2001:DB8::1]",
            port = 8443,
            credential = Secret("synthetic-password"),
            sni = "SNI.example.org",
            obfs = "salamander",
        )
        assertEquals("a2aee74979ef7c698114e712d6ae1a42649aa5c574eeb0d155c63664f12860d8", hy2.id.hash)
    }

    @Test
    fun `same identity gives same id`() {
        assertEquals(vless.id, vless.copy().id)
        assertEquals(vless.id, NodeId.of(vless))
    }

    @Test
    fun `host and sni case, host brackets and whitespace do not matter`() {
        assertEquals(vless.id, vless.copy(host = " EXAMPLE.com ", sni = "Example.COM").id)
        val v6 = NodeKey(Protocol.TROJAN, "2001:db8::2", 443)
        assertEquals(v6.id, v6.copy(host = "[2001:DB8::2]").id)
    }

    @Test
    fun `absent and empty fields are equivalent`() {
        val bare = NodeKey(Protocol.SHADOWSOCKS, "example.com", 8388)
        assertEquals(bare.id, bare.copy(credential = Secret(""), transport = "", path = "", sni = "", obfs = "").id)
    }

    @Test
    fun `each identity field changes the id`() {
        val variants = listOf(
            vless.copy(protocol = Protocol.VMESS),
            vless.copy(host = "example.org"),
            vless.copy(port = 8443),
            vless.copy(credential = Secret("00000000-0000-4000-8000-000000000002")),
            vless.copy(transport = "ws"),
            vless.copy(path = "/other"),
            vless.copy(sni = "example.org"),
            vless.copy(obfs = "x"),
        )
        val ids = variants.map { it.id } + vless.id
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `adjacent fields do not merge`() {
        assertNotEquals(vless.copy(path = "/a", sni = "b").id, vless.copy(path = "/ab", sni = null).id)
    }

    @Test
    fun `tag is short and derived from the hash`() {
        val tag = vless.id.tag
        assertTrue(tag.startsWith(NodeId.TAG_PREFIX))
        assertEquals("n-833be5c507e9bca0", tag)
    }
}
