package com.paperless.scanner.util

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

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
    const val MAX_LINES = 1500

    /**
     * Total characters kept. Whichever cap is reached first evicts.
     *
     * This is resident heap for the life of the process, so the number is a real cost —
     * 128k characters is roughly 256 KB, which is noise next to a single decoded page
     * bitmap and buys the report a log that spans more than a few minutes.
     */
    const val MAX_CHARS = 128_000

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
    fun readLogcatTail(maxLines: Int = LOGCAT_LINES): List<String> {
        // By UID first, by PID only as a fallback — and NEVER unfiltered.
        //
        // Measured on a real device (2026-09-21, uid 10588): `--pid` returned 33 lines
        // covering 3 minutes, `--uid` returned 246 lines across FOUR process ids
        // covering 14 hours. The oldest of them was `FATAL EXCEPTION: main` from the
        // previous night — a crash the PID filter can never show, because the process
        // that crashed does not exist any more when the report is written. That is also
        // why raising the line cap alone changed nothing: we were asking for 400 and
        // getting 33.
        //
        // An unfiltered `logcat -d` is deliberately NOT the fallback. logd usually
        // restricts an unprivileged reader to its own uid anyway, but "usually" is the
        // wrong word for a path that would otherwise put OTHER apps' log lines into a
        // report the user mails to us.
        return leseLogcat(maxLines, "--uid=${android.os.Process.myUid()}")
            ?: leseLogcat(maxLines, "--pid=${android.os.Process.myPid()}")
            ?: emptyList()
    }

    /**
     * One logcat run, or null when it did not RUN — which is not the same as an empty
     * log and must not be confused with it.
     *
     * Older logcat builds reject `--uid`, and because stderr is merged into stdout the
     * rejection arrives as a line of text (measured: `logcat: Unable to parse UID`).
     * Read as output, that error would count as a successful read of one line and the
     * fallback would never fire. The exit status is what separates the two, so it is
     * what decides here.
     */
    private fun leseLogcat(maxLines: Int, filter: String): List<String>? = try {
        val process = ProcessBuilder(
            listOf("logcat", "-d", "-t", maxLines.toString(), filter)
        ).redirectErrorStream(true).start()

        // The deadline is armed BEFORE the read, and that ordering is the whole point.
        // The KDoc above grants that an OEM build may refuse logcat; the case that hurts
        // is one which neither serves nor exits — stdout stays open with no data, so
        // `readLines()` never returns and a timeout placed after it is never reached.
        // The caller then sees the dialog close and nothing else, ever: no report, no
        // toast, no error. Killing the process closes stdout, which is what releases the
        // read with whatever had arrived.
        //
        // `destroyForcibly`, not `destroy`: whether a hung logcat honours SIGTERM is
        // exactly the thing we cannot assume here.
        var abgewuergt = false
        val waechter = Thread {
            if (!process.waitFor(LOGCAT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                abgewuergt = true
                process.destroyForcibly()
            }
        }.apply { isDaemon = true; start() }

        val output = process.inputStream.bufferedReader().use { it.readLines() }
        waechter.join(LOGCAT_TIMEOUT_SECONDS * 1000)

        // A killed run counts as a run: whatever arrived before the deadline is real
        // output, and retrying the same thing under the fallback filter would only
        // spend the deadline twice.
        when {
            abgewuergt -> output.map { LogSanitizer.sanitizeLogLine(it) }
            process.exitValue() == 0 -> output.map { LogSanitizer.sanitizeLogLine(it) }
            else -> null
        }
    } catch (e: Exception) {
        // Not reportable and not worth a breadcrumb: the ring buffer carries the report
        // on its own, and a device that refuses this is a fact about the device.
        null
    }

    /**
     * Lines requested from logcat.
     *
     * Measured against a real device buffer: filtered by uid it held 246 lines over 14
     * hours at ~137 bytes each, so 2000 is headroom for a busy day rather than a wish —
     * logcat cannot return lines the device no longer holds.
     */
    const val LOGCAT_LINES = 2000

    /** How long the logcat process may take to exit before it is killed. */
    const val LOGCAT_TIMEOUT_SECONDS = 3L
}
