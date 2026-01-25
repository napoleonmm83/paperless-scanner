package com.paperless.scanner.ui.navigation

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.paperless.scanner.util.AppLockManager
import com.paperless.scanner.util.AppLockState

/**
 * Reconstructs the full route with actual argument values.
 *
 * IMPORTANT: For screens that dynamically update URIs (MultiPageUpload, Scan),
 * we read the CURRENT URIs from SavedStateHandle, not the stale navigation arguments!
 *
 * Example:
 * - Route template: "document/{documentId}"
 * - Arguments: ["documentId" = "133"]
 * - Result: "document/133"
 */
private fun reconstructRouteWithArgs(backStackEntry: NavBackStackEntry?): String? {
    if (backStackEntry == null) return null

    val routeTemplate = backStackEntry.destination.route ?: return null
    val args = backStackEntry.arguments ?: return routeTemplate

    var reconstructed = routeTemplate

    // Special handling for MultiPageUpload/Scan: Use current URIs from SavedStateHandle
    // instead of stale navigation arguments (user may have added/removed images)
    when {
        routeTemplate.startsWith("multi-page-upload/") -> {
            // Read current URIs from SavedStateHandle (where UploadViewModel stores them)
            val currentUris = backStackEntry.savedStateHandle.get<String>("documentUris")
            if (currentUris != null) {
                Log.d("AppLockInterceptor", "MultiPageUpload: Using CURRENT URIs from SavedStateHandle: $currentUris")
                val encodedUris = android.net.Uri.encode(currentUris)
                reconstructed = reconstructed.replace("{documentUris}", encodedUris)
                return reconstructed
            }
        }
        routeTemplate.startsWith("scan") -> {
            // Read current page URIs from SavedStateHandle (where ScanViewModel stores them)
            val currentPageUris = backStackEntry.savedStateHandle.get<String>("pageUris")
            if (currentPageUris != null && currentPageUris.isNotEmpty()) {
                Log.d("AppLockInterceptor", "Scan: Using CURRENT page URIs from SavedStateHandle: $currentPageUris")
                // Encode the entire pipe-separated URI string
                val encodedUris = android.net.Uri.encode(currentPageUris)
                // Build route with query parameter
                reconstructed = "scan?pageUris=$encodedUris"
                return reconstructed
            } else {
                // No pages - return base route for mode selection
                Log.d("AppLockInterceptor", "Scan: No pages, using base route")
                return "scan"
            }
        }
    }

    // Default: Replace all argument placeholders with actual values (URI-encoded)
    args.keySet().forEach { key ->
        // Skip navigation internal keys
        if (key.startsWith("android-support-nav:")) return@forEach

        val value = args.get(key)
        if (value != null) {
            // Replace {key} with URI-encoded value (important for file:// URIs and special chars)
            val encodedValue = android.net.Uri.encode(value.toString())
            reconstructed = reconstructed.replace("{$key}", encodedValue)
        }
    }

    return reconstructed
}

/**
 * App-Lock Navigation Interceptor
 *
 * Observes the lock state and automatically navigates to AppLockScreen when locked.
 * Implements a white-list approach: certain screens (login, onboarding) are never locked.
 *
 * SECURITY: Saves the route before locking so user can return to exactly where they were
 * after unlocking, without compromising security by keeping the back stack.
 */
@Composable
fun AppLockNavigationInterceptor(
    navController: NavHostController,
    appLockManager: AppLockManager
) {
    val lockState by appLockManager.lockState.collectAsState()

    // Remember the route before locking
    var routeBeforeLock by remember { mutableStateOf<String?>(null) }

    // White-listed routes that should NOT be protected by app-lock
    val unprotectedRoutes = remember {
        listOf(
            Screen.Welcome.route, // Unified onboarding flow (SimplifiedSetup)
            Screen.AppLock.route,
            Screen.SetupAppLock.route.split("/")[0] // Base route without params
        )
    }

    LaunchedEffect(lockState) {
        // Get the route template for checking if protected
        val currentRouteTemplate = navController.currentBackStackEntry?.destination?.route
        // Get the FULL route with actual argument values for saving/restoring
        val currentFullRoute = reconstructRouteWithArgs(navController.currentBackStackEntry)

        val isCurrentRouteProtected = currentRouteTemplate?.let { route ->
            // Check if current route is NOT in white-list
            !unprotectedRoutes.any { unprotectedRoute ->
                route.startsWith(unprotectedRoute)
            }
        } ?: false

        Log.d("AppLockInterceptor", "=== LOCK STATE CHANGED ===")
        Log.d("AppLockInterceptor", "New lockState: $lockState")
        Log.d("AppLockInterceptor", "currentRouteTemplate: $currentRouteTemplate")
        Log.d("AppLockInterceptor", "currentFullRoute: $currentFullRoute")
        Log.d("AppLockInterceptor", "isProtected: $isCurrentRouteProtected")
        Log.d("AppLockInterceptor", "==========================")

        when (lockState) {
            is AppLockState.Locked -> {
                Log.d("AppLockInterceptor", "State: LOCKED")
                // App is locked - navigate to AppLockScreen if not already there
                if (isCurrentRouteProtected && currentRouteTemplate != Screen.AppLock.route) {
                    // Save FULL route with actual arguments before locking
                    routeBeforeLock = currentFullRoute
                    Log.d("AppLockInterceptor", "Saved FULL route before lock: $routeBeforeLock")

                    Log.d("AppLockInterceptor", "Navigating to AppLock screen")
                    navController.navigate(Screen.AppLock.route) {
                        // Clear back stack - user must unlock
                        popUpTo(navController.graph.startDestinationId) {
                            // No need to save navigation state - ViewModels persist via SavedStateHandle
                            saveState = false
                        }
                        launchSingleTop = true
                        restoreState = false
                    }
                } else {
                    Log.d("AppLockInterceptor", "NOT saving route - Already on AppLock screen or route not protected (routeBeforeLock remains: $routeBeforeLock)")
                }
            }
            is AppLockState.LockedOut -> {
                val lockedOutState = lockState as AppLockState.LockedOut
                Log.d("AppLockInterceptor", "State: LOCKED_OUT (permanent=${lockedOutState.isPermanent})")
                // Only navigate away if permanent lockout (after 15 total attempts)
                // Temporary lockout (every 5 attempts) is handled in AppLockScreen UI
                if (lockedOutState.isPermanent) {
                    // User was permanently locked out - logout and go to onboarding
                    routeBeforeLock = null  // Clear saved route
                    if (currentRouteTemplate != Screen.OnboardingWelcome.route) {
                        navController.navigate(Screen.OnboardingWelcome.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
                // For temporary lockout, stay on AppLock screen (don't navigate away)
            }
            is AppLockState.Unlocked -> {
                Log.d("AppLockInterceptor", "State: UNLOCKED")
                // ONLY navigate if we're currently ON the AppLock screen
                // If user disabled AppLock while on Settings, DON'T navigate away
                if (currentRouteTemplate == Screen.AppLock.route) {
                    // Use saved route, or fallback to Home for logged-in users
                    val targetRoute = routeBeforeLock ?: Screen.Home.route
                    Log.d("AppLockInterceptor", "Currently on AppLock screen, navigating back to: $targetRoute (saved=$routeBeforeLock, fallback=${Screen.Home.route})")

                    try {
                        // IMPORTANT: restoreState = false to avoid restoring entire back stack
                        // ViewModels already persist state via SavedStateHandle (ScanViewModel)
                        // restoreState = true would reactivate ALL screens in back stack, not just target
                        navController.navigate(targetRoute) {
                            // Only remove AppLock screen from back stack
                            popUpTo(Screen.AppLock.route) { inclusive = true }
                            launchSingleTop = true
                            // Don't restore navigation state - ViewModels handle their own state via SavedStateHandle
                            restoreState = false
                        }
                        Log.d("AppLockInterceptor", "Navigation successful to: $targetRoute")
                    } catch (e: Exception) {
                        Log.e("AppLockInterceptor", "Navigation FAILED to $targetRoute", e)
                        // Emergency fallback: try navigating to Home without any options
                        try {
                            navController.navigate(Screen.Home.route)
                            Log.d("AppLockInterceptor", "Emergency fallback navigation to Home successful")
                        } catch (e2: Exception) {
                            Log.e("AppLockInterceptor", "Emergency fallback ALSO failed", e2)
                        }
                    }

                    // Clear saved route after using it
                    routeBeforeLock = null
                } else {
                    Log.d("AppLockInterceptor", "State changed to UNLOCKED but not on AppLock screen (current=$currentRouteTemplate), no navigation needed")
                }
            }
        }
    }
}
