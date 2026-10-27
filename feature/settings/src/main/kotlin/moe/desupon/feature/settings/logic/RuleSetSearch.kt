package moe.desupon.feature.settings.logic

import moe.desupon.data.ruleset.RuleSetInfo

enum class RuleSetGroup { BUNDLED, GEOIP, GEOSITE }

/** The rule-set picker's list. */
object RuleSetSearch {
    fun groupOf(info: RuleSetInfo): RuleSetGroup = when {
        info.source == RuleSetInfo.Source.BUNDLED -> RuleSetGroup.BUNDLED
        info.domainBased -> RuleSetGroup.GEOSITE
        else -> RuleSetGroup.GEOIP
    }

    /** Sets whose label, code or name contains [query], by group in [RuleSetGroup] order; empty groups are left out. */
    fun groups(sets: List<RuleSetInfo>, query: String, labels: Map<String, String>): List<Pair<RuleSetGroup, List<RuleSetInfo>>> {
        val q = query.trim()
        val matching = if (q.isEmpty()) {
            sets
        } else {
            sets.filter { s -> listOfNotNull(labels[s.name], s.code, s.name).any { it.contains(q, ignoreCase = true) } }
        }
        val byGroup = matching.groupBy(::groupOf)
        return RuleSetGroup.entries.mapNotNull { g -> byGroup[g]?.let { g to it } }
    }
}
