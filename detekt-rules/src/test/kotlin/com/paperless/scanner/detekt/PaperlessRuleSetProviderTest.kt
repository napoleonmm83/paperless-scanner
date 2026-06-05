package com.paperless.scanner.detekt

import io.gitlab.arturbosch.detekt.api.Config
import org.junit.Assert.assertEquals
import org.junit.Test

class PaperlessRuleSetProviderTest {

    @Test
    fun `exposes the three paperless rules under the paperless-compose ruleset`() {
        val provider = PaperlessRuleSetProvider()
        assertEquals("paperless-compose", provider.ruleSetId)

        val ids = provider.instance(Config.empty).rules.map { it.ruleId }.toSet()
        assertEquals(
            setOf("RawRouteString", "TouchTargetSize", "LabelLetterSpacingOverride"),
            ids,
        )
    }
}
