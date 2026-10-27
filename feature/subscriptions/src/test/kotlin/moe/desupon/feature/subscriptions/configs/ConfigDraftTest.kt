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
    fun `validation and text edits clear errors`() {
        val blank = ConfigDraft.new().validate()
        assertEquals(UiText.Res(R.string.subs_config_error_empty), blank.error)
        assertNull(blank.text(link).error)
        val formless = ConfigDraft.new().copy(mode = ConfigMode.FORM)
        assertEquals(UiText.Res(R.string.subs_config_form_pick_protocol), formless.validate().error)
        val ok = ConfigDraft.new().text(link)
        assertSame(ok, ok.validate())
    }

    @Test
    fun `text the form cannot show stays in text mode with a note`() {
        val d = ConfigDraft.new().text("wireguard://cHJpdmF0ZQ@w.example.com:51820")
        val tried = d.toForm()
        assertEquals(ConfigMode.TEXT, tried.mode)
        assertEquals(UiText.Res(R.string.subs_config_form_unavailable), tried.formNote)
        assertEquals(UiText.Res(R.string.subs_config_form_pick_protocol), ConfigDraft.new().toForm().formNote)
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
