package com.paperless.scanner.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import com.paperless.scanner.util.AppLockManager
import com.paperless.scanner.util.AppLockState

/**
 * App-Lock Navigation Interceptor
 *
 * Observes the lock state and automatically navigates to AppLockScreen when locked.
 * Implements a white-list approach: certain screens (login, onboarding) are never locked.
 */
@Composable
fun AppLockNavigationInterceptor(
    navController: NavHostController,
    appLockManager: AppLockManager
) {
    val lockState by appLockManager.lockState.collectAsState()

    // White-listed routes that should NOT be protected by app-lock
    val unprotectedRoutes = remember {
        listOf(
            Screen.OnboardingWelcome.route,
            Screen.SimplifiedSetup.route,
            Screen.Welcome.route,
            Screen.ServerSetup.route,
            Screen.Login.route.split("/")[0], // Base route without params
            Screen.Success.route,
            Screen.AppLock.route,
            Screen.SetupAppLock.route.split("/")[0] // Base route without params
        )
    }

    LaunchedEffect(lockState) {
        val currentRoute = navController.currentBackStackEntry?.destination?.route
        val isCurrentRouteProtected = currentRoute?.let { route ->
            // Check if current route is NOT in white-list
            !unprotectedRoutes.any { unprotectedRoute ->
                route.startsWith(unprotectedRoute)
            }
        } ?: false

        when (lockState) {
            is AppLockState.Locked -> {
                // App is locked - navigate to AppLockScreen if not already there
                if (isCurrentRouteProtected && currentRoute != Screen.AppLock.route) {
                    navController.navigate(Screen.AppLock.route) {
                        // Don't add to back stack - user must unlock
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = false
                    }
                }
            }
            is AppLockState.LockedOut -> {
                // User was locked out after too many attempts - logout and go to onboarding
                if (currentRoute != Screen.OnboardingWelcome.route) {
                    navController.navigate(Screen.OnboardingWelcome.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
            is AppLockState.Unlocked -> {
                // App is unlocked - allow normal navigation
                // If user is on AppLockScreen and just unlocked, pop back stack
                if (currentRoute == Screen.AppLock.route) {
                    navController.popBackStack()
                }
            }
        }
    }
}
