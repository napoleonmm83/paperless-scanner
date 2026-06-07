package com.paperless.scanner.detekt

import io.gitlab.arturbosch.detekt.test.lint
import org.junit.Assert.assertEquals
import org.junit.Test

class RawRouteStringRuleTest {

    @Test
    fun `flags a raw route-shaped string literal passed to navigate`() {
        val findings = RawRouteStringRule().lint(
            """
            fun go() {
                navigate("scan?pageUris={uris}")
            }
            fun navigate(route: String) {}
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags an interpolated route template (the concatenation case)`() {
        val findings = RawRouteStringRule().lint(
            """
            fun go(id: Int) {
                navigate("upload/${'$'}id")
            }
            fun navigate(route: String) {}
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `does not flag a typed createRoute factory call`() {
        val findings = RawRouteStringRule().lint(
            """
            object Screen { object Upload { fun createRoute(id: Int) = "" } }
            fun go(id: Int) { navigate(Screen.Upload.createRoute(id)) }
            fun navigate(route: String) {}
            """.trimIndent(),
        )
        assertEquals(0, findings.size)
    }

    @Test
    fun `does not flag a bare non-route literal (known gap, single-segment)`() {
        val findings = RawRouteStringRule().lint(
            """
            fun go() { navigate("home") }
            fun navigate(route: String) {}
            """.trimIndent(),
        )
        assertEquals(0, findings.size)
    }
}
