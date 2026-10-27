/*
 * The default per-app bypass list is adapted from DefaultBypassApps.kt of turn-proxy-android (FreeTurn),
 * https://github.com/hackdiaz-dev/turn-proxy-android, by samosvalishe, licensed under GPL-3.0.
 * Desupon is distributed under GPL-3.0 as well.
 */
package moe.desupon.data.split

import moe.desupon.data.ruleset.RuleSetStore
import moe.desupon.model.routing.AppSplit
import moe.desupon.model.routing.SplitAction
import moe.desupon.model.routing.SplitMode
import moe.desupon.model.routing.SplitRule
import moe.desupon.model.routing.SplitRuleKind

/** Russian split-tunnel defaults: RU services bypass the tunnel by app and by destination. */
object RuDefaults {
    val bypassApps: Set<String> = linkedSetOf(
        // Marketplaces and delivery
        "ru.wildberries.buyer",
        "com.wildberries.ru",
        "ru.ozon.app.android",
        "ru.sbermegamarket.app",
        "ru.megamarket.marketplace",
        "ru.beru.android",
        "ru.samokat.app",
        "ru.yandex.market",
        "ru.yandex.eda",
        "ru.foodfox.client",
        "ru.yandex.taxi",
        // Maps and Yandex services
        "ru.dublgis.dgismobile",
        "com.yandex.browser",
        "ru.yandex.yandexmaps",
        "ru.yandex.music",
        "ru.kinopoisk",
        // Social and media
        "ru.oneme.app",
        "ru.vk.store",
        "ru.ok.android",
        "com.vkontakte.android",
        "ru.vk.video",
        "com.vk.vkvideo",
        "com.uma.musicvk",
        "ru.rutube.app",
        "com.zen.android",
        "ru.zen.android",
        // Banks and payments
        "ru.sberbankmobile",
        "com.tinkoff.itinkoff",
        "com.idamob.tinkoff.android",
        "ru.tbank.mobile",
        "ru.alfabank.mobile.android",
        "ru.vtb24.mobileandroid",
        "ru.vtb24.mobilebanking.android",
        "ru.nspk.mirpay",
        "ru.sbcs.store",
        // Government services
        "ru.gosuslugi.app",
        "ru.rzd.pass",
        "ru.mos.parking",
        // Carriers
        "ru.megafon.mlk",
        "ru.beeline.services",
        "ru.tele2.mytele2",
        "ru.mts.mymts",
        "ru.rostel",
        // Classifieds, jobs, mail
        "com.avito.android",
        "ru.hh.android",
        "ru.cian.main",
        "ru.mail.mailapp",
        // VPN checkers and vendor security suites
        "com.notcvnt.rknhardering",
        "com.miui.securitycenter",
        "com.miui.securityadd",
        "com.huawei.systemmanager",
        "com.samsung.android.securitymanager",
    )

    /** Installed packages starting with these bypass too, on top of [bypassApps]. */
    val bypassPrefixes: Set<String> = linkedSetOf("ru.", "su.")

    val appSplit: AppSplit = AppSplit(SplitMode.ALL_EXCEPT_SELECTED, bypassApps, bypassPrefixes)

    /** RU domains and addresses go direct; everything else follows the proxy. */
    val rules: List<SplitRule> = listOf(
        SplitRule(order = 0, kind = SplitRuleKind.RULE_SET, value = RuleSetStore.GEOSITE_CATEGORY_RU, action = SplitAction.DIRECT),
        SplitRule(order = 1, kind = SplitRuleKind.RULE_SET, value = RuleSetStore.GEOSITE_TLD_RU, action = SplitAction.DIRECT),
        SplitRule(order = 2, kind = SplitRuleKind.RULE_SET, value = RuleSetStore.GEOIP_RU, action = SplitAction.DIRECT),
    )
}
