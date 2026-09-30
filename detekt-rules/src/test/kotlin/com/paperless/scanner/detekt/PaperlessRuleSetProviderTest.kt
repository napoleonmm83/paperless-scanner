package com.paperless.scanner.detekt

import dev.detekt.api.Config
import dev.detekt.api.RuleSetProvider
import java.util.ServiceLoader
import org.junit.Assert.assertEquals
import org.junit.Test

class PaperlessRuleSetProviderTest {

    @Test
    fun `exposes the three paperless rules under the paperless-compose ruleset`() {
        val provider = PaperlessRuleSetProvider()
        assertEquals("paperless-compose", provider.ruleSetId.value)

        val ruleSet = provider.instance()
        val ids = ruleSet.rules.keys.map { it.value }.toSet()
        assertEquals(
            setOf("RawRouteString", "TouchTargetSize", "LabelLetterSpacingOverride"),
            ids,
        )
        assertEquals(
            ids,
            ruleSet.rules.values.map { factory -> factory(Config.empty).ruleName.value }.toSet(),
        )
    }

    @Test
    fun `loads the paperless ruleset through ServiceLoader`() {
        val provider = ServiceLoader.load(RuleSetProvider::class.java)
            .filterIsInstance<PaperlessRuleSetProvider>()
            .single()

        assertEquals("paperless-compose", provider.ruleSetId.value)
        assertEquals(
            setOf("RawRouteString", "TouchTargetSize", "LabelLetterSpacingOverride"),
            provider.instance().rules.keys.map { it.value }.toSet(),
        )
    }
}
