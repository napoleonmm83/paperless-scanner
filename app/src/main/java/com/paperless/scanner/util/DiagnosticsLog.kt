package com.paperless.scanner.util

import java.util.ArrayDeque

/**
 * A small in-memory ring buffer of recent log lines, for the diagnostic report a user can
 * send from the app.
 *
 * **Why this exists at all, and why it is NOT behind the analytics consent gate.**
 * Every reporting path this app had went through `CrashlyticsHelper` or
 * `AnalyticsService`, and each of those begins with
 * `if (!analyticsService.isAnalyticsEnabled()) return` — including the `Log.d` inside
 * the helper. For a user who declined analytics, the app therefore learned nothing at
 * all: no non-fatal, no event, not even a logcat line. Those are exactly the users we
 * never hear from otherwise, and one of them is the reason this work exists.
 *
 * Nothing here leaves the device by itself. The buffer is memory-only, it is never
 * written to disk, and it reaches us only when a user opens the report, reads it, and
 * sends it. That is consent by action rather than by checkbox, and it is why the
 * consent gate would be the wrong mechanism here — it governs *automatic* transmission.
 *
 * **Sanitizing happens on the way IN, not on the way out** ([append]). A value that was
 * never stored cannot be leaked by a later path, by a future caller, or by a bug in the
 * report builder. The cost is that the buffer is slightly less useful for debugging the
 * app locally; the benefit is that the privacy property holds without anyone having to
 * remember it.
 *
 * Thread-safe because it is written from anywhere — IO threads, the main thread, worker
 * coroutines. Read only from a background dispatcher: the buffer feeds the FULL report
 * (which also spawns logcat), never the shareable one, so nothing here runs on main.
 */
object DiagnosticsLog {

    /** Lines kept. Older ones are dropped first. */
    const val MAX_LINES = 300

    /** Total characters kept. Whichever cap is reached first evicts. */
    const val MAX_CHARS = 32_000

    private val lines = ArrayDeque<String>()
    private var charCount = 0
    private val lock = Any()

    /**
     * Records one line, sanitized.
     *
     * @param tag short component name, e.g. a class or subsystem.
     * @param message the line. Passed through [LogSanitizer.sanitizeLogLine], which strips
     *   credentials and the server host while keeping URL paths and status codes.
     */
    fun append(tag: String, message: String) {
        val entry = "${System.currentTimeMillis()} $tag ${LogSanitizer.sanitizeLogLine(message)}"
        synchronized(lock) {
            lines.addLast(entry)
            charCount += entry.length
            // Both caps, because either one alone fails on a realistic input: 300 stack
            // frames blow the character budget, and 32k of one-word lines would keep
            // thousands of entries.
            while (lines.size > MAX_LINES || charCount > MAX_CHARS) {
                val removed = lines.removeFirst()
                charCount -= removed.length
            }
        }
    }

    /** Snapshot, oldest first. Empty when nothing has been recorded. */
    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    /** Drops everything. Used by tests and after a report has been handed over. */
    fun clear() {
        synchronized(lock) {
            lines.clear()
            charCount = 0
        }
    }

    /**
     * Reads this process's own logcat tail, sanitized. Empty list when unavailable.
     *
     * **Why this is the primary source and [append] is only the backbone.** Counted in
     * this codebase: 116 calls go through [AppLogger], and 577 call `android.util.Log`
     * directly — including the repository and network paths, which are the interesting
     * ones. A hand-built sink would therefore see roughly a sixth of what happens, and
     * rewriting 577 call sites is a refactor with nothing to do with diagnostics.
     * logcat sees all of them, plus OkHttp, Coil and the framework, without touching a
     * single line of existing code.
     *
     * An app may read its OWN process's log without a permission; `READ_LOGS` governs
     * other apps' output. Whether every OEM build honours that is not something this
     * code can assume, which is why the result is optional and the ring buffer stands on
     * its own — see the report builder, which merges whatever it gets.
     *
     * Blocking. Call from a background dispatcher.
     */
    fun readLogcatTail(maxLines: Int = LOGCAT_LINES): List<String> = try {
        val pid = android.os.Process.myPid()
        val process = ProcessBuilder(
            listOf("logcat", "-d", "-t", maxLines.toString(), "--pid=$pid")
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().use { it.readLines() }
        process.waitFor()
        output.map { LogSanitizer.sanitizeLogLine(it) }
    } catch (e: Exception) {
        // Not reportable and not worth a breadcrumb: the ring buffer carries the report
        // on its own, and a device that refuses this is a fact about the device.
        emptyList()
    }

    /** Lines requested from logcat. Enough for the minutes before a failure. */
    const val LOGCAT_LINES = 400
}
