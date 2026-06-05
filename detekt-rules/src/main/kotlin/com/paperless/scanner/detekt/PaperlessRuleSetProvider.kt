package com.paperless.scanner.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/**
 * detekt ruleset for Paperless Scanner Compose conventions.
 *
 * Registered via META-INF/services so `detektPlugins(project(":detekt-rules"))`
 * loads it. Enable rules under the `paperless-compose:` block in detekt.yml.
 */
class PaperlessRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "paperless-compose"

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            RawRouteStringRule(config),
            TouchTargetSizeRule(config),
            LabelLetterSpacingOverrideRule(config),
        ),
    )
}
