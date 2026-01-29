package com.paperless.scanner.ui.screens.applock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.util.Log
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun AppLockScreen(
    onUnlocked: () -> Unit,
    onLockedOut: () -> Unit,
    viewModel: AppLockViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val canUseBiometric by viewModel.canUseBiometric.collectAsState()

    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val activity = remember(context) {
        val act = context as? FragmentActivity
        Log.d("AppLockScreen", "Context type: ${context::class.java.simpleName}, Activity: $act")
        act
    }

    // Countdown timer for temporary lockout
    var remainingSeconds by remember { mutableStateOf(0) }

    // Update countdown every second during temporary lockout
    LaunchedEffect(uiState) {
        val currentState = uiState
        if (currentState is AppLockUiState.LockedOut && !currentState.isPermanent) {
            // Initialize from ViewModel for more accurate timing
            remainingSeconds = viewModel.getRemainingLockoutSeconds()
            Log.d("AppLockScreen", "Starting countdown timer with $remainingSeconds seconds remaining")

            while (remainingSeconds > 0) {
                kotlinx.coroutines.delay(1000)
                remainingSeconds = viewModel.getRemainingLockoutSeconds()
            }

            // Countdown expired - trigger state refresh
            Log.d("AppLockScreen", "Countdown expired, refreshing lockout state")
            viewModel.refreshLockoutState()
        }
    }

    // SECURITY: Prevent back button bypass - user MUST unlock
    // Back button should NOT allow bypassing the lock screen
    BackHandler(enabled = true) {
        // Do nothing - completely block back navigation
        // Alternative: Move app to background instead of allowing bypass
        // (context as? Activity)?.moveTaskToBack(true)
    }

    // Auto-focus password field on screen load
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Handle unlock success and permanent lockout
    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is AppLockUiState.Unlocked -> onUnlocked()
            is AppLockUiState.LockedOut -> {
                if (state.isPermanent) {
                    onLockedOut()
                }
                // Temporary lockout is handled in the UI (countdown timer)
            }
            else -> {}
        }
    }

    // PRIORITY: Show biometric prompt IMMEDIATELY when available
    // This ensures biometric is the primary unlock method (before PIN)
    LaunchedEffect(canUseBiometric, activity) {
        Log.d("AppLockScreen", "LaunchedEffect: canUseBiometric=$canUseBiometric, activity=$activity")
        if (canUseBiometric && activity != null) {
            Log.d("AppLockScreen", "Calling viewModel.showBiometricPrompt()")
            viewModel.showBiometricPrompt(activity)
        } else if (canUseBiometric && activity == null) {
            Log.e("AppLockScreen", "Biometric available but activity is NULL!")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Lock Icon
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                // Title
                Text(
                    text = "App gesperrt",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Subtitle with remaining attempts or lockout timer
                when (val state = uiState) {
                    is AppLockUiState.Error -> {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        if (state.remainingAttempts > 0) {
                            Text(
                                text = "Verbleibende Versuche: ${state.remainingAttempts}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    is AppLockUiState.LockedOut -> {
                        if (!state.isPermanent) {
                            // Temporary lockout - show countdown
                            val minutes = remainingSeconds / 60
                            val seconds = remainingSeconds % 60
                            Text(
                                text = "Zu viele Fehlversuche",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Passwort-Eingabe gesperrt für",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = String.format("%d:%02d", minutes, seconds),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Du kannst weiterhin biometrisch entsperren",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center
                            )
                        } else {
                            // Permanent lockout
                            Text(
                                text = "Maximale Fehlversuche erreicht",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Du wirst ausgeloggt...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = "Gib dein Passwort ein, um fortzufahren",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Check if in temporary lockout
                val currentUiState = uiState
                val isLockedOut = currentUiState is AppLockUiState.LockedOut && !currentUiState.isPermanent

                // Password Field (disabled during temporary lockout)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    label = { Text("Passwort") },
                    placeholder = { Text(if (isLockedOut) "Gesperrt" else "Passwort eingeben") },
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Default.Visibility
                                } else {
                                    Icons.Default.VisibilityOff
                                },
                                contentDescription = if (passwordVisible) {
                                    "Passwort verbergen"
                                } else {
                                    "Passwort anzeigen"
                                }
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (password.isNotBlank() && !isLockedOut) {
                                viewModel.unlockWithPassword(password)
                                keyboardController?.hide()
                            }
                        }
                    ),
                    singleLine = true,
                    enabled = uiState !is AppLockUiState.Unlocking && !isLockedOut,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        disabledBorderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                    )
                )

                // Unlock Button (disabled during temporary lockout)
                Button(
                    onClick = {
                        if (password.isNotBlank()) {
                            viewModel.unlockWithPassword(password)
                            keyboardController?.hide()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = password.isNotBlank() && uiState !is AppLockUiState.Unlocking && !isLockedOut,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(
                        text = when {
                            uiState is AppLockUiState.Unlocking -> "Entsperren..."
                            isLockedOut -> "Gesperrt"
                            else -> "Entsperren"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                // Biometric Button (if available)
                if (canUseBiometric && activity != null) {
                    OutlinedButton(
                        onClick = { viewModel.showBiometricPrompt(activity) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = "Biometrisch entsperren",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
