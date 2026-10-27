package moe.desupon.data.split

import moe.desupon.data.ruleset.RuleSetStore
import moe.desupon.model.routing.SplitAction
import moe.desupon.model.routing.SplitMode
import moe.desupon.model.routing.SplitRuleKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuDefaultsTest {
    @Test
    fun appsAreExcludedFromTheTunnelAndNeverIncludeSelf() {
        assertEquals(SplitMode.ALL_EXCEPT_SELECTED, RuDefaults.appSplit.mode)
        assertTrue("ru.gosuslugi.app" in RuDefaults.bypassApps)
        assertFalse(RuDefaults.bypassApps.any { it.startsWith("moe.desupon") })
        assertTrue(RuDefaults.bypassApps.all { Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+").matches(it) })
    }

    @Test
    fun rulesSendBundledRuSetsDirectInOrder() {
        val rules = RuDefaults.rules
        assertEquals(listOf(0, 1, 2), rules.map { it.order })
        assertTrue(rules.all { it.kind == SplitRuleKind.RULE_SET && it.action == SplitAction.DIRECT && it.enabled })
        assertEquals(
            setOf(RuleSetStore.GEOSITE_CATEGORY_RU, RuleSetStore.GEOSITE_TLD_RU, RuleSetStore.GEOIP_RU),
            rules.map { it.value }.toSet(),
        )
    }
}
