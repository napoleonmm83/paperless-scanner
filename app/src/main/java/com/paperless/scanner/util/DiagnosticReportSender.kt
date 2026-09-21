package com.paperless.scanner.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.paperless.scanner.BuildConfig
import com.paperless.scanner.R
import java.io.File

/**
 * Hands a diagnostic report to the user's mail app, with the report file attached.
 *
 * **Why ACTION_SEND with a mailto selector, and not the two obvious alternatives.**
 * `ACTION_SENDTO` with a `mailto:` URI prefills the recipient but cannot carry an
 * attachment — most mail clients ignore `EXTRA_STREAM` on it, and the attachment is the
 * half that matters here. A bare `ACTION_SEND` carries the attachment but lands in the
 * general share sheet between notes apps and messengers, which discard `EXTRA_EMAIL`.
 * Setting the selector combines them: the chooser offers mail apps only, the recipient
 * and subject are filled in, and the file goes along.
 *
 * Two fallbacks, because no device may end up in a dead end: without a mail app the same
 * intent goes to the ordinary share sheet, and if nothing resolves at all the caller is
 * told so and can fall back to the clipboard.
 */
object DiagnosticReportSender {

    /**
     * Outcome of an attempt, so the caller can say something true to the user.
     *
     * [COPIED_TO_CLIPBOARD] and [NO_TARGET] are deliberately separate. An earlier version
     * had only the latter and the message attached to it read "copied to the clipboard
     * instead" — while nothing was copied and the clipboard was empty. A message that
     * describes a fallback nobody performed is worse than no fallback: the user stops
     * looking for the report.
     */
    enum class Result { SENT_TO_MAIL, SENT_TO_CHOOSER, COPIED_TO_CLIPBOARD, NO_TARGET }

    /**
     * @param reportFile the written report, or null when it could not be written — the
     *   mail path is skipped then and the text goes to the clipboard, which is still a
     *   way for the user to get it to us.
     * @param reportText the same report as text, used only for the clipboard fallback.
     */
    fun send(context: Context, reportFile: File?, reportText: String, subjectTag: String): Result {
        val uri = reportFile?.let {
            try {
                FileProvider.getUriForFile(
                    context,
                    SharedFileCache.authority(context.packageName),
                    it
                )
            } catch (e: IllegalArgumentException) {
                // The file is outside the roots in file_paths.xml — our configuration
                // defect, not a missing app. Fall through to the clipboard rather than
                // telling the user to install something.
                null
            }
        } ?: return copyToClipboard(context, reportText)

        val base = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TEXT
            putExtra(Intent.EXTRA_EMAIL, arrayOf(context.getString(R.string.support_email)))
            putExtra(
                Intent.EXTRA_SUBJECT,
                context.getString(
                    R.string.diagnostic_report_subject,
                    BuildConfig.VERSION_NAME,
                    subjectTag
                )
            )
            // A short body so the mail says something even before the attachment is
            // opened, and so a reply has something to quote.
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.diagnostic_report_body))
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val mailOnly = Intent(base).apply {
            selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        }

        return try {
            context.startActivity(mailOnly.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Result.SENT_TO_MAIL
        } catch (e: ActivityNotFoundException) {
            // Asked BEFORE the chooser, not caught after it. `Intent.createChooser` targets
            // the system chooser activity, which always resolves — so the catch below could
            // never fire, the user saw "No apps can perform this action", and this method
            // reported SENT_TO_CHOOSER while the clipboard fallback the KDoc promises was
            // unreachable from the mail path. The `<queries>` entry for ACTION_SEND is what
            // makes this question answerable at all on Android 11+.
            if (context.packageManager.queryIntentActivities(base, 0).isEmpty()) {
                copyToClipboard(context, reportText)
            } else try {
                context.startActivity(
                    Intent.createChooser(base, context.getString(R.string.diagnostic_report_share_via))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                Result.SENT_TO_CHOOSER
            } catch (e2: ActivityNotFoundException) {
                copyToClipboard(context, reportText)
            }
        }
    }

    /**
     * Last resort: the report goes on the clipboard so the user can paste it anywhere.
     *
     * Returns [Result.NO_TARGET] only if even this fails — on some OEM builds the
     * clipboard service can be absent or refuse a write, and claiming a copy that did not
     * happen is the defect this branch exists to avoid.
     */
    // `internal`, not private: the settings screen offers copying as its own action and
    // had hand-rolled it with a hard `as` cast and no catch — the exact two failures the
    // KDoc above says this function exists to survive. One clipboard path, not two.
    internal fun copyToClipboard(context: Context, reportText: String): Result = try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Result.NO_TARGET
        } else {
            clipboard.setPrimaryClip(
                ClipData.newPlainText(context.getString(R.string.diagnostic_report_title), reportText)
            )
            Result.COPIED_TO_CLIPBOARD
        }
    } catch (e: Exception) {
        Result.NO_TARGET
    }

    private const val MIME_TEXT = "text/plain"
}
