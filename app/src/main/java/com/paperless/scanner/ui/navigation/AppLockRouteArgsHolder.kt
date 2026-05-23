package com.paperless.scanner.ui.navigation

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the dynamic route arguments that
 * [AppLockNavigationInterceptor] needs when it reconstructs the current route
 * before locking (issue #30, F-003).
 *
 * Background: screens like Scan and MultiPageUpload mutate their URI lists at
 * runtime. Previously those URIs were written to TWO places — the ViewModel's
 * `SavedStateHandle` (process-death survival) AND the navigation
 * `BackStackEntry.savedStateHandle` (read by the interceptor). The two writes
 * lived in different scopes (ViewModel vs. Screen `LaunchedEffect`) and could
 * desync, so route reconstruction on AppLock unlock could restore stale or
 * missing pages.
 *
 * This holder collapses the interceptor's read path to one in-memory store that
 * the owning ViewModel updates **atomically, in the same method** as its
 * `SavedStateHandle` write — making desync structurally impossible. The
 * interceptor only reads at lock time, when the owning screen (and its
 * ViewModel) is still alive, and the reconstructed route string is then
 * persisted by the interceptor's own `rememberSaveable`, so an in-memory store
 * is sufficient (process death while locked is covered by that saved route).
 */
@Singleton
class AppLockRouteArgsHolder @Inject constructor() {

    private val args = ConcurrentHashMap<String, String>()

    /** Store [value] under [key]; a null/blank value clears the entry. */
    fun put(key: String, value: String?) {
        if (value.isNullOrEmpty()) {
            args.remove(key)
        } else {
            args[key] = value
        }
    }

    /** Latest value for [key], or null if unset. */
    fun get(key: String): String? = args[key]
}
