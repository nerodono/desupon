package moe.desupon.platform.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RedactorTest {
    @Test
    fun masksEverySecretShape() {
        val cases = listOf(
            "IpcSet: private_key=6f1c0e2a9b3d4c5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6" to "6f1c0e2a",
            "uuid 0f8e1a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b" to "0f8e1a2b",
            "dial ss://user:pw@host" to "user:pw",
            "password: hunter2" to "hunter2",
            "pub YAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=" to "YAnz5TF",
            """{"private_key": "YAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="}""" to "YAnz5TF",
            "dial failed: https://vk.ru/call/join/Zx9QwErTyUiOp1234567890AbCdEfGhIjKlMnOpQrStUv" to "Zx9QwErT",
            "link=https://vk.com/call/join/abcDEF_123-xyz" to "abcDEF_123",
            """links: ["https://example.org/call/join/shortCode1?x=1"]""" to "shortCode1",
            "anon token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJlLXN5bnRoZXRpYw" to "eyJzdWIi",
            "clientId=0123456789abcdef" to "0123456789abcdef",
            "vless://0f8e1a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b@192.0.2.7:443?pbk=synthPublicKey&sid=ab12" to "synthPublicKey",
            "reality: sid=ab12cd34 rejected" to "ab12cd34",
            "obfs-password=synthetic-obfs" to "synthetic-obfs",
        )
        for ((input, leak) in cases) {
            val out = Redactor.mask(input)
            assertFalse(leak in out, "mask($input) leaks $leak: $out")
        }
    }

    @Test
    fun keepsPlainText() {
        val plain = "probe transport: dial tcp 192.0.2.1:443: i/o timeout (n-ab12)"
        assertEquals(plain, Redactor.mask(plain))
        assertEquals("", Redactor.mask(null))
    }

    @Test
    fun coreExceptionDropsCause() {
        val e = CoreException.from(IllegalStateException("start: password=hunter2"))
        assertFalse("hunter2" in e.message.orEmpty())
        assertNull(e.cause)
    }
}
