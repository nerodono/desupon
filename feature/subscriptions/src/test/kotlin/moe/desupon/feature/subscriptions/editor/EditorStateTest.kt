package moe.desupon.feature.subscriptions.editor

import moe.desupon.data.repo.EditResult
import moe.desupon.data.repo.SubscriptionDetails
import moe.desupon.feature.subscriptions.R
import moe.desupon.feature.subscriptions.UiText
import moe.desupon.model.Secret
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import moe.desupon.model.subscription.UpdateInterval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EditorStateTest {
    private val primary = "https://sub.example.invalid/s/0000synthetic0000"
    private val mirror = "https://mirror.example.invalid/s/0000synthetic0000"
    private val plain = "http://plain.example.invalid/s/0000synthetic0000"

    private fun details(
        urls: List<String> = listOf(primary),
        local: Boolean = false,
        name: String? = null,
        ua: String? = null,
        interval: UpdateInterval? = null,
        sets: Set<SetId> = emptySet(),
    ) = SubscriptionDetails(
        id = SubId(1),
        isLocal = local,
        nameOverride = name,
        providerTitle = "Провайдер",
        urls = if (local) emptyList() else urls.map(::Secret),
        maskedUrls = emptyList(),
        providerMirrorCount = 0,
        userAgentOverride = ua,
        globalUserAgent = "global/1.0",
        intervalOverride = interval,
        providerIntervalH = 6,
        effectiveInterval = interval ?: UpdateInterval.Hours(6),
        sets = sets,
        nodeCount = 0,
        skippedCount = null,
        lastOkAtMs = null,
        lastAttemptAtMs = null,
        lastError = null,
    )

    private fun draft(vararg urls: String = arrayOf(primary)) = EditorDraft.from(details(urls.toList()), null)

    private fun EditorDraft.raw() = form.urls.map { it.url.raw() }

    @Test
    fun `fresh draft is clean and mirrors stored values`() {
        val d = EditorDraft.from(details(name = "Моё", ua = "Happ/3.7.0", interval = UpdateInterval.Manual, sets = setOf(SetId(3))), null)
        assertFalse(d.dirty)
        assertEquals("Моё", d.form.name)
        assertEquals(UaSelection(UaPreset.HAPP), d.form.userAgent)
        assertEquals(UpdateInterval.Manual, d.form.interval)
        assertEquals(setOf(SetId(3)), d.form.sets)
        assertNull(d.form.localText)
    }

    @Test
    fun `name blank or equal to provider title stores none`() {
        val d = draft()
        assertNull(d.name("  ").form.toEdit(d.providerTitle).name)
        assertNull(d.name("Провайдер").form.toEdit(d.providerTitle).name)
        assertEquals("Своё", d.name(" Своё ").form.toEdit(d.providerTitle).name)
        assertFalse(d.name("Провайдер").dirty)
        assertEquals("", d.name("x").clearName().form.name)
    }

    @Test
    fun `editing and reverting is not dirty`() {
        val d = draft()
        val edited = d.interval(UpdateInterval.Hours(1))
        assertTrue(edited.dirty)
        assertFalse(edited.interval(null).dirty)
    }

    @Test
    fun `add mirror appends valid addresses with fresh keys`() {
        val d = draft().mirrorInput("  $mirror | $plain ").addMirror()
        assertEquals(listOf(primary, mirror, plain), d.raw())
        assertEquals(3, d.form.urls.map { it.key }.distinct().size)
        assertEquals("", d.mirrorInput)
        assertNull(d.mirrorError)
        assertTrue(d.dirty)
        assertTrue(d.form.urls[2].cleartext)
        assertFalse(d.form.urls[0].cleartext)
    }

    @Test
    fun `add mirror rejects invalid and duplicate input`() {
        val bad = draft().mirrorInput("ftp://x.example.invalid/a").addMirror()
        assertEquals(UiText.Res(R.string.subs_editor_url_invalid), bad.mirrorError)
        assertEquals(listOf(primary), bad.raw())

        val mixed = draft().mirrorInput("$mirror not-a-url").addMirror()
        assertEquals(listOf(primary), mixed.raw())

        val dup = draft().mirrorInput(primary).addMirror()
        assertEquals(UiText.Res(R.string.subs_editor_url_in_list), dup.mirrorError)

        val blank = draft().mirrorInput("   ").addMirror()
        assertNull(blank.mirrorError)
        assertEquals(listOf(primary), blank.raw())
    }

    @Test
    fun `keys stay unique after remove and add`() {
        var d = draft(primary, mirror)
        d = d.remove(d.form.urls[1].key).mirrorInput(plain).addMirror().mirrorInput(mirror).addMirror()
        assertEquals(listOf(primary, plain, mirror), d.raw())
        assertEquals(3, d.form.urls.map { it.key }.toSet().size)
    }

    @Test
    fun `move reorders and clamps`() {
        val d = draft(primary, mirror, plain)
        val keyOfPlain = d.form.urls[2].key
        assertEquals(listOf(plain, primary, mirror), d.move(keyOfPlain, -2).raw())
        assertEquals(listOf(primary, plain, mirror), d.move(keyOfPlain, -1).raw())
        assertSame(d, d.move(keyOfPlain, 1))
        assertEquals(listOf(plain, primary, mirror), d.move(keyOfPlain, -10).raw())
        assertSame(d, d.move(999, 1))
    }

    @Test
    fun `remove drops the row and its reveal`() {
        val d = draft(primary, mirror)
        val key = d.form.urls[1].key
        val removed = d.toggleReveal(key).remove(key)
        assertEquals(listOf(primary), removed.raw())
        assertTrue(removed.revealed.isEmpty())
    }

    @Test
    fun `reveal toggles per row and does not dirty`() {
        val d = draft(primary, mirror)
        val key = d.form.urls[0].key
        val shown = d.toggleReveal(key)
        assertEquals(setOf(key), shown.revealed)
        assertFalse(shown.dirty)
        assertTrue(shown.toggleReveal(key).revealed.isEmpty())
    }

    @Test
    fun `masked row hides the token`() {
        val row = draft().form.urls.single()
        assertFalse("0000synthetic0000" in row.masked)
    }

    @Test
    fun `save result errors map onto submitted rows and clear on list edits`() {
        val d = draft(primary, mirror)
        val submitted = d.form.urls
        val invalid = d.withResult(EditResult.InvalidUrl(1), submitted)
        assertEquals(mapOf(submitted[1].key to UiText.Res(R.string.subs_editor_url_invalid)), invalid.urlErrors)
        assertTrue(invalid.hasErrors)
        assertTrue(invalid.move(submitted[1].key, -1).urlErrors.isEmpty())

        val outOfRange = d.withResult(EditResult.InvalidUrl(9), submitted)
        assertEquals(UiText.Res(R.string.subs_editor_url_invalid), outOfRange.urlsError)

        assertEquals(UiText.Res(R.string.subs_editor_no_urls), d.withResult(EditResult.NoUrls, submitted).urlsError)
        val dup = d.withResult(EditResult.DuplicateUrl(SubId(2)), submitted)
        assertEquals(UiText.Res(R.string.subs_editor_url_duplicate), dup.urlsError)
        assertNull(dup.remove(submitted[1].key).urlsError)
        assertSame(d, d.withResult(EditResult.Saved(null), submitted))
    }

    @Test
    fun `validation catches empty url list and empty local text`() {
        val d = draft()
        val empty = d.remove(d.form.urls.single().key)
        assertEquals(UiText.Res(R.string.subs_editor_no_urls), empty.validate().urlsError)
        assertSame(d, d.validate())

        val local = EditorDraft.from(details(local = true), "vless://synthetic")
        assertSame(local, local.validate())
        assertEquals(UiText.Res(R.string.subs_editor_local_empty), local.localText("  ").validate().localTextError)
    }

    @Test
    fun `local text edits are dirty and ignored for remote`() {
        val local = EditorDraft.from(details(local = true), "a\n")
        assertTrue(local.form.isLocal)
        assertFalse(local.localText("a").dirty)
        assertTrue(local.localText("b").dirty)
        val remote = draft()
        assertSame(remote, remote.localText("x"))
    }

    @Test
    fun `sets toggle ignores the built-in set`() {
        val d = draft()
        assertEquals(setOf(SetId(5)), d.toggleSet(SetId(5)).form.sets)
        assertTrue(d.toggleSet(SetId(5)).toggleSet(SetId(5)).form.sets.isEmpty())
        assertSame(d, d.toggleSet(SetId.ALL))
    }

    @Test
    fun `user agent presets map to and from stored values`() {
        assertEquals(UaSelection(UaPreset.DEFAULT), UaSelection.of(null))
        assertEquals(UaSelection(UaPreset.DEFAULT), UaSelection.of("  "))
        for (p in UaPreset.entries.filter { it.value != null }) {
            assertEquals(UaSelection(p), UaSelection.of(p.value))
            assertEquals(p.value, UaSelection(p).value)
        }
        assertEquals(UaSelection(UaPreset.CUSTOM, "my-agent/1"), UaSelection.of(" my-agent/1 "))
        assertNull(UaSelection(UaPreset.DEFAULT).value)
        assertNull(UaSelection(UaPreset.CUSTOM, "  ").value)
        assertEquals("x/1", UaSelection(UaPreset.CUSTOM, " x/1 ").value)
    }

    @Test
    fun `presets carry distinct client strings`() {
        val values = UaPreset.entries.mapNotNull { it.value }
        assertEquals(values.size, values.toSet().size)
        assertTrue(values.all { it.isNotBlank() })
    }

    @Test
    fun `switching to custom starts from the current value`() {
        val happ = UaSelection(UaPreset.HAPP)
        assertEquals(UaSelection(UaPreset.CUSTOM, UaPreset.HAPP.value!!), happ.select(UaPreset.CUSTOM))
        assertEquals(UaSelection(UaPreset.CUSTOM, ""), UaSelection(UaPreset.DEFAULT).select(UaPreset.CUSTOM))
        val custom = UaSelection(UaPreset.CUSTOM, "mine")
        assertEquals("mine", custom.select(UaPreset.HIDDIFY).select(UaPreset.CUSTOM).custom)
    }

    @Test
    fun `user agent edits through the draft`() {
        val d = draft().userAgent(UaPreset.CLASH_META)
        assertEquals(UaPreset.CLASH_META.value, d.form.toEdit(null).userAgent)
        assertEquals("c/2", d.customUserAgent("c/2").form.toEdit(null).userAgent)
        assertTrue(d.dirty)
        assertFalse(d.userAgent(UaPreset.DEFAULT).dirty)
        assertFalse(draft().customUserAgent("").dirty)
    }

    @Test
    fun `interval options keep an unusual stored value`() {
        assertEquals(Intervals.presets, Intervals.options(null))
        assertEquals(Intervals.presets, Intervals.options(UpdateInterval.Hours(6)))
        val odd = Intervals.options(UpdateInterval.Hours(3))
        assertTrue(UpdateInterval.Hours(3) in odd)
        assertEquals(UpdateInterval.Manual, odd.last())
        assertEquals(Intervals.presets.size + 1, odd.size)
    }

    @Test
    fun `provider links need a known scheme`() {
        assertTrue(Links.openable("https://support.example.invalid"))
        assertTrue(Links.openable("tg://resolve?domain=example"))
        assertFalse(Links.openable("javascript:alert(1)"))
        assertFalse(Links.openable("intent://x#Intent;end"))
        assertFalse(Links.openable("https:"))
        assertFalse(Links.openable(""))
    }
}
