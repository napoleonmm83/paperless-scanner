package com.paperless.scanner.util

import android.content.Context
import android.content.SharedPreferences

/**
 * App-readable mirror of the in-progress scan draft's `shared_images` file names.
 *
 * #307: `PaperlessApp.onCreate`'s cache sweep needs to know which
 * `shared_images/cropped_*.jpg` files are still referenced by a persisted scan
 * draft so it never deletes one on a delayed restore (the #241 follow-up
 * landmine). The authoritative draft state lives in `ScanViewModel`'s
 * `SavedStateHandle` (KEY_PAGE_URIS), but that registry is owned by the
 * Activity/NavBackStackEntry and is NOT readable from `Application.onCreate` at
 * process start — no ViewModel exists yet.
 *
 * This holder mirrors the draft's backing file names into SharedPreferences so
 * the App can read them synchronously at boot. SharedPreferences (not DataStore)
 * is chosen for the same reasons as [com.paperless.scanner.widget.WidgetPreferences]:
 * synchronous reads/writes that are reliable in every Android context, including
 * `Application.onCreate`, with no coroutine scope required.
 *
 * `ScanViewModel` updates this in the same method that writes its
 * `SavedStateHandle` page URIs, so the mirror can never structurally desync from
 * the persisted draft.
 */
class ScanDraftCache(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    /**
     * Replaces the protected file-name set with the backing file names of
     * [draftSharedImageUris] (FileProvider/file URI strings). Only names that
     * resolve via [SharedFileCache.fileNameFromSharedUri] are stored; an empty
     * input clears the entry. Uses `commit()` for a synchronous write so a
     * subsequent App-boot read can never race a pending `apply()`.
     */
    fun setProtectedFileNames(draftSharedImageUris: List<String>) {
        val names = draftSharedImageUris
            .mapNotNull { SharedFileCache.fileNameFromSharedUri(it) }
            .toSet()
        prefs.edit().apply {
            if (names.isEmpty()) remove(KEY_PROTECTED_NAMES) else putStringSet(KEY_PROTECTED_NAMES, names)
        }.commit()
    }

    /** File names referenced by the persisted draft; empty if no draft exists. */
    fun getProtectedFileNames(): Set<String> =
        prefs.getStringSet(KEY_PROTECTED_NAMES, emptySet())?.toSet() ?: emptySet()

    companion object {
        internal const val PREFS_NAME = "scan_draft_cache"
        internal const val KEY_PROTECTED_NAMES = "protected_shared_image_names"
    }
}
