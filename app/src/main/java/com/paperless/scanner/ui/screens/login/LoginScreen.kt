package com.paperless.scanner.ui.screens.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val uiState by viewModel.uiState.collectAsState()
    val canUseBiometric by viewModel.canUseBiometric.collectAsState()
    val serverStatus by viewModel.serverStatus.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    var serverUrl by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var apiToken by rememberSaveable { mutableStateOf("") }
    var selectedTabIndex by rememberSaveable { mutableStateOf(0) } // 0 = Password, 1 = Token
    var showTokenScanner by remember { mutableStateOf(false) }

    // Pre-compute supporting text based on server status
    val serverSupportingText = when (serverStatus) {
        is ServerStatus.Idle -> "HTTP/HTTPS wird automatisch erkannt"
        is ServerStatus.Checking -> "Server wird geprüft..."
        is ServerStatus.Success -> {
            if ((serverStatus as ServerStatus.Success).isHttps) {
                "✓ Sichere Verbindung (HTTPS)"
            } else {
                "✓ Verbindung hergestellt (HTTP)"
            }
        }
        is ServerStatus.Error -> (serverStatus as ServerStatus.Error).message
    }
    val serverSupportingTextColor = when (serverStatus) {
        is ServerStatus.Success -> MaterialTheme.colorScheme.primary
        is ServerStatus.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // Auto-trigger biometric if available
    LaunchedEffect(canUseBiometric) {
        if (canUseBiometric && activity != null) {
            viewModel.biometricHelper.authenticate(
                activity = activity,
                onSuccess = { viewModel.onBiometricSuccess() },
                onError = { message -> viewModel.onBiometricError(message) },
                onFallback = { /* Show password form */ }
            )
        }
    }

    LaunchedEffect(uiState) {
        when (uiState) {
            is LoginUiState.Success -> onLoginSuccess()
            is LoginUiState.Error -> {
                snackbarHostState.showSnackbar((uiState as LoginUiState.Error).message)
            }
            else -> {}
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Paperless Scanner",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Mit deiner Paperless-ngx Instanz verbinden",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Login Mode Tabs
            TabRow(
                selectedTabIndex = selectedTabIndex,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("Passwort") }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text("Schlüssel") }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    viewModel.onServerUrlChanged(it)
                },
                label = { Text("Server") },
                placeholder = { Text("paperless.example.com") },
                supportingText = {
                    Text(
                        text = serverSupportingText,
                        color = serverSupportingTextColor
                    )
                },
                trailingIcon = {
                    when (serverStatus) {
                        is ServerStatus.Checking -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        is ServerStatus.Success -> {
                            val status = serverStatus as ServerStatus.Success
                            Icon(
                                imageVector = if (status.isHttps) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = if (status.isHttps) "HTTPS" else "HTTP",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        is ServerStatus.Error -> {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = "Fehler",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> {}
                    }
                },
                isError = serverStatus is ServerStatus.Error,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Password Login Mode
            if (selectedTabIndex == 0) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            if (serverUrl.isNotBlank() &&
                                username.isNotBlank() &&
                                password.isNotBlank() &&
                                serverStatus is ServerStatus.Success) {
                                viewModel.login(serverUrl, username, password)
                            }
                        }
                    ),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (passwordVisible) {
                                    "Hide password"
                                } else {
                                    "Show password"
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = { viewModel.login(serverUrl, username, password) },
                    enabled = serverUrl.isNotBlank() &&
                            username.isNotBlank() &&
                            password.isNotBlank() &&
                            serverStatus is ServerStatus.Success &&
                            uiState !is LoginUiState.Loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    if (uiState is LoginUiState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Anmelden...")
                    } else {
                        Text("Login")
                    }
                }
            }

            // Token Login Mode
            if (selectedTabIndex == 1) {
                OutlinedTextField(
                    value = apiToken,
                    onValueChange = { apiToken = it },
                    label = { Text("Zugangsschlüssel") },
                    placeholder = { Text("Hier einfügen...") },
                    supportingText = {
                        Text("Findest du in Paperless unter: Einstellungen → Mein Profil")
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { showTokenScanner = true }) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = "Schlüssel scannen"
                            )
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            if (serverUrl.isNotBlank() &&
                                apiToken.isNotBlank() &&
                                serverStatus is ServerStatus.Success) {
                                viewModel.loginWithToken(serverUrl, apiToken)
                            }
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Nutze diese Option, wenn du dich nicht mit Passwort anmelden kannst – zum Beispiel weil du eine zusätzliche Sicherheitsabfrage eingerichtet hast.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = { viewModel.loginWithToken(serverUrl, apiToken) },
                    enabled = serverUrl.isNotBlank() &&
                            apiToken.isNotBlank() &&
                            serverStatus is ServerStatus.Success &&
                            uiState !is LoginUiState.Loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    if (uiState is LoginUiState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Anmelden...")
                    } else {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Anmelden")
                    }
                }
            }

            // Biometric login button
            if (canUseBiometric && activity != null) {
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = {
                        viewModel.biometricHelper.authenticate(
                            activity = activity,
                            onSuccess = { viewModel.onBiometricSuccess() },
                            onError = { message -> viewModel.onBiometricError(message) },
                            onFallback = { /* Show password form */ }
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Mit Biometrie anmelden")
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }

    // Token Scanner Sheet
    if (showTokenScanner) {
        TokenScannerSheet(
            onDismiss = { showTokenScanner = false },
            onTokenFound = { token ->
                apiToken = token
                showTokenScanner = false
            }
        )
    }
}
