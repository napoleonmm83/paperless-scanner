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
            Regex("""\bLog\.\w+\(""").containsMatchIn(source)
        )
    }

    @Test
    fun `never logs raw OCR text or token values`() {
        // Safe metadata uses block interpolation (e.g. "${tokens.size}", "${text.length}").
        // The leak forms are the simple interpolations "$tokens" / "$text" and the
        // value accessors ".first()" / "visionText.text".
        val forbidden = listOf(
            // Simple and bare-braced interpolation of the raw values. Note that
            // safe metadata like "${text.length}" / "${tokens.size}" is allowed
            // because "${text}" / "${tokens}" (closing brace right after the
            // name) is not a substring of those.
            "\$text", "\$tokens", "\${text}", "\${tokens}", ".first()", "visionText.text",
            "tokens[", "tokens.get(", "tokens.toString()", "tokens.joinToString(",
            "text.take(", "text.substring(", "text.toString()",
        )
        loggingLines().forEach { (lineNo, line) ->
            forbidden.forEach { needle ->
                assertFalse(
                    "TokenScannerSheet.kt:$lineNo logs sensitive data ('$needle'): ${line.trim()}",
                    line.contains(needle)
                )
            }
        }
    }

    private fun loggingLines(): List<Pair<Int, String>> {
        // Capture each log call as one span by balancing brackets from the
        // opening one until it closes. A naive `.*?\)` would stop at the first
        // ')' — e.g. inside a "token(s)" message — and miss a sensitive value
        // after it; balancing also folds in multi-line argument lists.
        val callStart = Regex("""\b(AppLogger|Log)\.\w+\(""")
        return callStart.findAll(source).map { match ->
            var end = matchingClose(source.indexOf('(', match.range.first), '(', ')')
            // AppLogger exposes a lazy overload `d(tag) { ... }`; pull in the
            // trailing lambda body so a sensitive value inside it is scanned too.
            var next = end + 1
            while (next < source.length && source[next].isWhitespace()) next++
            if (next < source.length && source[next] == '{') {
                end = matchingClose(next, '{', '}')
            }
            val startLine = source.substring(0, match.range.first).count { it == '\n' } + 1
            startLine to source.substring(match.range.first, end + 1)
        }.toList()
    }

    /**
     * Index of the [close] bracket that balances the [open] at [openIdx],
     * ignoring brackets inside string and char literals. Returns the last index
     * if it never balances (so the whole tail still gets scanned).
     */
    private fun matchingClose(openIdx: Int, open: Char, close: Char): Int {
        var depth = 0
        var inString = false
        var inChar = false
        var escaped = false
        for (k in openIdx until source.length) {
            val ch = source[k]
            when {
                escaped -> escaped = false
                ch == '\\' && (inString || inChar) -> escaped = true
                ch == '"' && !inChar -> inString = !inString
                ch == '\'' && !inString -> inChar = !inChar
                !inString && !inChar -> when (ch) {
                    open -> depth++
                    close -> if (--depth == 0) return k
                }
            }
        }
        return source.length - 1
    }

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
