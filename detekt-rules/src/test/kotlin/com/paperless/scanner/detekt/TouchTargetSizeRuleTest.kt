package com.paperless.scanner.detekt

import io.gitlab.arturbosch.detekt.test.lint
import org.junit.Assert.assertEquals
import org.junit.Test

class TouchTargetSizeRuleTest {

    @Test
    fun `flags a bare clickable Modifier without a touch-target guard`() {
        val findings = TouchTargetSizeRule().lint(
            "fun c() { val m = Modifier.clickable { } }",
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `does not flag clickable guarded by minimumInteractiveComponentSize (PageThumbnail pattern)`() {
        val findings = TouchTargetSizeRule().lint(
            "fun c() { val m = Modifier.minimumInteractiveComponentSize().clickable { } }",
        )
        assertEquals(0, findings.size)
    }

    @Test
    fun `does not flag a full-bleed clickable (fillMaxSize)`() {
        val findings = TouchTargetSizeRule().lint(
            "fun c() { val m = Modifier.fillMaxSize().clickable { } }",
        )
        assertEquals(0, findings.size)
    }

    @Test
    fun `does not flag a large explicit size (160dp card)`() {
        val findings = TouchTargetSizeRule().lint(
            "fun c() { val m = Modifier.size(160.dp).clickable { } }",
        )
        assertEquals(0, findings.size)
    }

    @Test
    fun `flags an explicit size below the 48dp minimum`() {
        val findings = TouchTargetSizeRule().lint(
            "fun c() { val m = Modifier.size(28.dp).clickable { } }",
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags clickable with only padding (no touch-target guarantee)`() {
        val findings = TouchTargetSizeRule().lint(
            "fun c() { val m = Modifier.padding(8.dp).clickable { } }",
        )
        assertEquals(1, findings.size)
    }
}

