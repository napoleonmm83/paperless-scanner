package com.paperless.scanner.detekt

import io.gitlab.arturbosch.detekt.test.lint
import org.junit.Assert.assertEquals
import org.junit.Test

class LabelLetterSpacingOverrideRuleTest {

    @Test
    fun `flags a hardcoded sp letterSpacing override`() {
        val findings = LabelLetterSpacingOverrideRule().lint(
            "fun c() { val s = TextStyle(letterSpacing = 0.1.sp) }",
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `does not flag a scalable em token value`() {
        val findings = LabelLetterSpacingOverrideRule().lint(
            "fun c() { val s = TextStyle(letterSpacing = 0.1.em) }",
        )
        assertEquals(0, findings.size)
    }

    @Test
    fun `does not flag a typography token reference`() {
        val findings = LabelLetterSpacingOverrideRule().lint(
            "fun c() { val s = TextStyle(letterSpacing = MaterialTheme.typography.labelLarge.letterSpacing) }",
        )
        assertEquals(0, findings.size)
    }
}

