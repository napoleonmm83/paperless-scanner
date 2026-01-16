package com.paperless.scanner.ui.screens.applock

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupAppLockScreen(
    onBackClick: () -> Unit,
    onSetupComplete: () -> Unit,
    isChangingPassword: Boolean = false,
    viewModel: SetupAppLockViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var currentPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    var currentPasswordVisible by remember { mutableStateOf(false) }

    val passwordFocusRequester = remember { FocusRequester() }
    val confirmPasswordFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Handle setup success
    LaunchedEffect(uiState) {
        if (uiState is SetupAppLockUiState.Success) {
            android.util.Log.d("SetupAppLockScreen", "Setup successful, calling onSetupComplete()")
            onSetupComplete()
        }
    }

    // Auto-focus first field
    LaunchedEffect(Unit) {
        if (isChangingPassword) {
            // Focus current password field
        } else {
            passwordFocusRequester.requestFocus()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isChangingPassword) {
                            "Passwort ändern"
                        } else {
                            "App-Sperre einrichten"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Zurück"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Info Text
            Text(
                text = if (isChangingPassword) {
                    "Gib dein aktuelles Passwort ein und wähle ein neues Passwort."
                } else {
                    "Wähle ein Passwort zum Schutz deiner App. Das Passwort muss mindestens 4 Zeichen lang sein."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Current Password (only when changing)
            if (isChangingPassword) {
                OutlinedTextField(
                    value = currentPassword,
                    onValueChange = { currentPassword = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Aktuelles Passwort") },
                    visualTransformation = if (currentPasswordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { currentPasswordVisible = !currentPasswordVisible }) {
                            Icon(
                                imageVector = if (currentPasswordVisible) {
                                    Icons.Default.Visibility
                                } else {
                                    Icons.Default.VisibilityOff
                                },
                                contentDescription = if (currentPasswordVisible) {
                                    "Passwort verbergen"
                                } else {
                                    "Passwort anzeigen"
                                }
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { passwordFocusRequester.requestFocus() }
                    ),
                    singleLine = true,
                    enabled = uiState !is SetupAppLockUiState.Loading,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
            }

            // New Password
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(passwordFocusRequester),
                label = { Text(if (isChangingPassword) "Neues Passwort" else "Passwort") },
                placeholder = { Text("Mindestens 4 Zeichen") },
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
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { confirmPasswordFocusRequester.requestFocus() }
                ),
                singleLine = true,
                enabled = uiState !is SetupAppLockUiState.Loading,
                isError = uiState is SetupAppLockUiState.Error && password.isNotBlank(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            // Confirm Password
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(confirmPasswordFocusRequester),
                label = { Text("Passwort bestätigen") },
                placeholder = { Text("Passwort wiederholen") },
                visualTransformation = if (confirmPasswordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                        Icon(
                            imageVector = if (confirmPasswordVisible) {
                                Icons.Default.Visibility
                            } else {
                                Icons.Default.VisibilityOff
                            },
                            contentDescription = if (confirmPasswordVisible) {
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
                        if (password.isNotBlank() && confirmPassword.isNotBlank()) {
                            keyboardController?.hide()
                            if (isChangingPassword) {
                                viewModel.changePassword(currentPassword, password, confirmPassword)
                            } else {
                                viewModel.setupPassword(password, confirmPassword)
                            }
                        }
                    }
                ),
                singleLine = true,
                enabled = uiState !is SetupAppLockUiState.Loading,
                isError = uiState is SetupAppLockUiState.Error && confirmPassword.isNotBlank(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            // Error Message
            if (uiState is SetupAppLockUiState.Error) {
                val errorState = uiState as SetupAppLockUiState.Error
                Text(
                    text = errorState.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Setup Button
            Button(
                onClick = {
                    keyboardController?.hide()
                    if (isChangingPassword) {
                        viewModel.changePassword(currentPassword, password, confirmPassword)
                    } else {
                        viewModel.setupPassword(password, confirmPassword)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = password.isNotBlank() &&
                        confirmPassword.isNotBlank() &&
                        (!isChangingPassword || currentPassword.isNotBlank()) &&
                        uiState !is SetupAppLockUiState.Loading,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (uiState is SetupAppLockUiState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = if (isChangingPassword) "Passwort ändern" else "Aktivieren",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}
