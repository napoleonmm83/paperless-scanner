package com.paperless.scanner.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

/**
 * detekt ruleset for Paperless Scanner Compose conventions.
 *
 * Registered via META-INF/services so `detektPlugins(project(":detekt-rules"))`
 * loads it. Enable rules under the `paperless-compose:` block in detekt.yml.
 */
class PaperlessRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("paperless-compose")

    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            ::RawRouteStringRule,
            ::TouchTargetSizeRule,
            ::LabelLetterSpacingOverrideRule,
        ),
    )
}
