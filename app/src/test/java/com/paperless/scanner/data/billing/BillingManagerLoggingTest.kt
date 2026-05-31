package com.paperless.scanner.data.billing

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Regression guard for issue #39: BillingManager must not leak product IDs,
 * offer/purchase tokens, or billing response codes into release logcat.
 *
 * Two guarantees, both enforced at source level so a future edit fails the
 * build instead of silently re-introducing a leak:
 *
 *  1. All logging routes through [com.paperless.scanner.util.AppLogger] — raw
 *     `android.util.Log.d/.v` survive release builds, while AppLogger wraps
 *     every debug/verbose call in a `BuildConfig.DEBUG` check (so the noisy
 *     product/response-code dumps are compiled out of release).
 *  2. The always-on logging surface (AppLogger.w/.e/.i, which is NOT stripped
 *     in release) never carries product/purchase identifiers.
 *
 * Mirrors the proven scanner from TokenScannerLoggingTest (issue #240).
 */
class BillingManagerLoggingTest {

    private val source: String by lazy { billingManagerSource() }

    @Test
    fun `routes all logging through AppLogger so release builds strip debug logs`() {
        // android.util.Log.d/.v are unguarded and survive release builds.
        // AppLogger wraps every debug/verbose call in a BuildConfig.DEBUG check.
        assertFalse(
            "BillingManager must not import android.util.Log — route logging through AppLogger",
            source.contains("import android.util.Log")
        )
        assertFalse(
            "BillingManager must not call android.util.Log directly — use AppLogger",
            Regex("""\bLog\.\w+\(""").containsMatchIn(source)
        )
    }

    @Test
    fun `never logs product or purchase identifiers in always-on release logs`() {
        // AppLogger.d/.v are stripped in release (BuildConfig.DEBUG guard), so a
        // product dump there is acceptable. AppLogger.w/.e/.i are always-on and
        // CAN reach release logcat — those must never carry these identifiers.
        val sensitive = listOf(
            ".products", ".purchaseToken", ".offerToken",
            "productDetailsCache.keys", "subscriptionOfferDetails",
            // Caught by codex review on this PR: billing response codes and the raw
            // product id must not reach release logcat via always-on logs (#39).
            "responseCode", "\$productId",
        )
        loggingLines()
            .filter { (_, _, method) -> method in ALWAYS_ON_METHODS }
            .forEach { (lineNo, span, method) ->
                sensitive.forEach { needle ->
                    assertFalse(
                        "BillingManager.kt:$lineNo AppLogger.$method (always-on, release-visible) " +
                            "logs sensitive identifier '$needle': ${span.trim()}",
                        span.contains(needle)
                    )
                }
            }
    }

    /**
     * Each AppLogger/Log call as a single (lineNo, span, method) triple. The span
     * is balanced from the opening '(' until it closes — a naive `.*?\)` would
     * stop at the first ')' inside a message and miss a trailing sensitive value;
     * balancing also folds in multi-line argument lists and the lazy `{ ... }`
     * trailing-lambda overload.
     */
    private fun loggingLines(): List<Triple<Int, String, String>> {
        val callStart = Regex("""\b(?:AppLogger|Log)\.(\w+)\(""")
        return callStart.findAll(source).map { match ->
            val method = match.groupValues[1]
            var end = matchingClose(source.indexOf('(', match.range.first), '(', ')')
            var next = end + 1
            while (next < source.length && source[next].isWhitespace()) next++
            if (next < source.length && source[next] == '{') {
                end = matchingClose(next, '{', '}')
            }
            val startLine = source.substring(0, match.range.first).count { it == '\n' } + 1
            Triple(startLine, source.substring(match.range.first, end + 1), method)
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

    private fun billingManagerSource(): String {
        val relative =
            "src/main/java/com/paperless/scanner/data/billing/BillingManager.kt"
        // Unit tests run with the module dir as cwd; fall back to repo-root invocation.
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("BillingManager.kt not found; checked: ${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    private companion object {
        // AppLogger methods that are NOT stripped in release builds.
        val ALWAYS_ON_METHODS = setOf("w", "e", "i")
    }
}
