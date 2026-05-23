package com.paperless.scanner.ui.screens.login

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Regression guard for issue #240: the token scanner must never log raw OCR
 * text or Paperless API token values. These are live auth credentials — a
 * captured log would disclose the user's token until it is revoked.
 *
 * This scans the TokenScannerSheet source so any future edit that re-introduces
 * a sensitive log fails the build instead of silently leaking into logcat.
 */
class TokenScannerLoggingTest {

    private val source: String by lazy { tokenScannerSource() }

    @Test
    fun `routes all logging through AppLogger so release builds strip debug logs`() {
        // android.util.Log.d/.v are unguarded and survive release builds.
        // AppLogger wraps every debug/verbose call in a BuildConfig.DEBUG check (AC #3).
        assertFalse(
            "TokenScannerSheet must not import android.util.Log — route logging through AppLogger",
            source.contains("import android.util.Log")
        )
        assertFalse(
            "TokenScannerSheet must not call android.util.Log directly — use AppLogger",
            Regex("""\bLog\.[dewiv]\(""").containsMatchIn(source)
        )
    }

    @Test
    fun `never logs raw OCR text or token values`() {
        // Safe metadata uses block interpolation (e.g. "${tokens.size}", "${text.length}").
        // The leak forms are the simple interpolations "$tokens" / "$text" and the
        // value accessors ".first()" / "visionText.text".
        val forbidden = listOf("\$text", "\$tokens", ".first()", "visionText.text")
        loggingLines().forEach { (lineNo, line) ->
            forbidden.forEach { needle ->
                assertFalse(
                    "TokenScannerSheet.kt:$lineNo logs sensitive data ('$needle'): ${line.trim()}",
                    line.contains(needle)
                )
            }
        }
    }

    private fun loggingLines(): List<Pair<Int, String>> =
        source.lines()
            .mapIndexed { index, line -> (index + 1) to line }
            .filter { (_, line) -> Regex("""(AppLogger|Log)\.[dewiv]\(""").containsMatchIn(line) }

    private fun tokenScannerSource(): String {
        val relative =
            "src/main/java/com/paperless/scanner/ui/screens/login/TokenScannerSheet.kt"
        // Unit tests run with the module dir as cwd; fall back to repo-root invocation.
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("TokenScannerSheet.kt not found; checked: ${candidates.map { it.absolutePath }}")
        return file.readText()
    }
}
