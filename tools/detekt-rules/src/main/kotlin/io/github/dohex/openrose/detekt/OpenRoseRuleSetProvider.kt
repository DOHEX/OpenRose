package io.github.dohex.openrose.detekt

import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

class OpenRoseRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("openrose")

    override fun instance() = RuleSet(ruleSetId, mapOf(RuleName("NoComments") to ::NoComments))
}
