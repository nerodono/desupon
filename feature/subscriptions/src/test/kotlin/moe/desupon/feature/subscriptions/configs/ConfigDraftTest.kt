package moe.desupon.feature.subscriptions.configs

import moe.desupon.feature.subscriptions.R
import moe.desupon.feature.subscriptions.SubscriptionImportRequests
import moe.desupon.feature.subscriptions.AddTarget
import moe.desupon.feature.subscriptions.UiText
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConfigDraftTest {
    private val link = "trojan://pw@a.example.com:443?sni=a.example.com#A"

    @Test
    fun `a new draft is dirty only once something is entered`() {
        val d = ConfigDraft.new()
        assertTrue(d.isNew)
        assertFalse(d.dirty)
        assertTrue(d.text(link).dirty)
        assertTrue(d.toggleSet(SetId(3)).dirty)
    }

    @Test
    fun `an opened config is dirty when its text or sets change`() {
        val d = ConfigDraft.of(SubId(1), link, setOf(SetId.ALL, SetId(2))).toText()
        assertEquals(setOf(SetId(2)), d.sets)
        assertFalse(d.dirty)
        assertFalse(d.text("  $link\n").dirty)
        assertTrue(d.text(link.replace("#A", "#B")).let { it.dirty && it.contentChanged })
        val sets = d.toggleSet(SetId(2))
        assertTrue(sets.dirty)
        assertFalse(sets.contentChanged)
        assertSame(d, d.toggleSet(SetId.ALL))
    }

    @Test
    fun `switching modes back and forth leaves an untouched config unchanged`() {
        val uuid = "11111111-2222-4333-8444-555555555555"
        val links = listOf(
            "vless://$uuid@x.example.com:443?type=xhttp&security=tls&sni=x.example.com&path=%2Fx#X",
            "vmess://$uuid@m.example.com:443?type=ws&security=tls&sni=m.example.com&path=%2Fv#V",
            link,
        )
        for (l in links) {
            val opened = ConfigDraft.of(SubId(1), l, emptySet())
            assertEquals(ConfigMode.FORM, opened.mode, l)
            val back = opened.toText().toForm()
            assertEquals(ConfigMode.FORM, back.mode, l)
            assertFalse(back.contentChanged, l)
            assertFalse(back.dirty, l)
            assertFalse(opened.toText().contentChanged, l)
        }
    }

    @Test
    fun `changing only the sets of a config the form would flag still saves`() {
        val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val noSni = "vless://11111111-2222-4333-8444-555555555555@r.example.com:443?security=reality&pbk=$key&sid=ab#R"
        val opened = ConfigDraft.of(SubId(1), noSni, emptySet())
        assertEquals(ConfigMode.FORM, opened.mode)
        val edited = opened.toggleSet(SetId(2))
        assertNull(edited.validate().error)
        assertTrue(opened.form { it.set(Field.SERVER, "s.example.com") }.validate().error != null)
    }

    @Test
    fun `a new config can start from pasted text`() {
        val d = ConfigDraft.fromText(link)
        assertTrue(d.isNew)
        assertEquals(ConfigMode.TEXT, d.mode)
        assertEquals(link, d.output)
        assertTrue(d.dirty)
        assertNull(d.validate().error)
    }

    @Test
    fun `validation and text edits clear errors`() {
        val formless = ConfigDraft.new()
        assertEquals(ConfigMode.FORM, formless.mode)
        assertEquals(UiText.Res(R.string.subs_config_form_pick_protocol), formless.validate().error)
        val blank = ConfigDraft.new().toText().validate()
        assertEquals(UiText.Res(R.string.subs_config_error_empty), blank.error)
        assertNull(blank.text(link).error)
        val ok = ConfigDraft.new().toText().text(link)
        assertEquals(ok, ok.validate())
    }

    @Test
    fun `text the form cannot show stays in text mode with a note`() {
        val d = ConfigDraft.new().toText().text("wireguard://cHJpdmF0ZQ@w.example.com:51820")
        val tried = d.toForm()
        assertEquals(ConfigMode.TEXT, tried.mode)
        assertEquals(UiText.Res(R.string.subs_config_form_unavailable), tried.formNote)
        assertEquals(UiText.Res(R.string.subs_config_form_pick_protocol), ConfigDraft.new().toText().toForm().formNote)
    }

    @Test
    fun `text the form can show switches to it and back without losing anything`() {
        val d = ConfigDraft.new().toText().text(link).toForm()
        assertEquals(ConfigMode.FORM, d.mode)
        assertEquals("A", d.form!!.name)
        assertEquals("a.example.com", d.form[Field.SERVER])
        val back = d.toText()
        assertEquals(ConfigMode.TEXT, back.mode)
        assertTrue(back.text.trimStart().startsWith("{"))
    }

    @Test
    fun `switching protocol keeps the name and server`() {
        val d = ConfigDraft.new().newForm(FormProtocol.TROJAN).form { it.name("Мой").set(Field.SERVER, "s.example.com").set(Field.PASSWORD, "pw") }
        val vless = d.newForm(FormProtocol.VLESS).form!!
        assertEquals(FormProtocol.VLESS, vless.protocol)
        assertEquals("Мой", vless.name)
        assertEquals("s.example.com", vless[Field.SERVER])
        assertEquals("", vless[Field.PASSWORD])
    }

    @Test
    fun `a form that does not read back as a runnable node is refused`() {
        val form = ConfigForm.blank(FormProtocol.TROJAN).set(Field.SERVER, "t.example.com").set(Field.PASSWORD, "pw")
        val draft = ConfigDraft.new().copy(form = form)
        assertNull(draft.validate().error)
        val missing = ConfigDraft.new().copy(form = ConfigForm.blank(FormProtocol.TROJAN))
        val checked = missing.validate()
        assertEquals(UiText.Res(R.string.subs_form_fix), checked.error)
        assertTrue(Field.SERVER in checked.form!!.errors)
    }

    @Test
    fun `shared text goes to the dialog it belongs to`() {
        assertEquals(AddTarget.SUBSCRIPTION, SubscriptionImportRequests.targetOf("https://a.example.com/s/t\n http://b.example.com/x | https://c.example.com/x"))
        assertEquals(AddTarget.CONFIG, SubscriptionImportRequests.targetOf(link))
        assertEquals(AddTarget.CONFIG, SubscriptionImportRequests.targetOf("https://a.example.com/s/t\n$link"))
    }

    @Test
    fun `endpoint reads server and port or the first wireguard peer`() {
        assertEquals("a.example.com:443", Endpoint.of("""{"type":"trojan","server":"a.example.com","server_port":443}"""))
        assertEquals("[2001:db8::1]:8388", Endpoint.of("""{"type":"shadowsocks","server":"2001:db8::1","server_port":8388}"""))
        assertEquals("w.example.com:51820", Endpoint.of("""{"type":"wireguard","peers":[{"address":"w.example.com","port":51820}]}"""))
        assertNull(Endpoint.of("""{"type":"trojan"}"""))
        assertNull(Endpoint.of("not json"))
    }
}
