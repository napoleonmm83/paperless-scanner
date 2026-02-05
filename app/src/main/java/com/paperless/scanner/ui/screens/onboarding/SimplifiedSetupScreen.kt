package com.paperless.scanner.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.paperless.scanner.R
import com.paperless.scanner.ui.components.HttpFallbackWarningDialog
import com.paperless.scanner.ui.components.SslCertificateDialog
import com.paperless.scanner.ui.screens.login.LoginUiState
import com.paperless.scanner.ui.screens.login.LoginViewModel
import com.paperless.scanner.ui.screens.login.ServerStatus
import com.paperless.scanner.ui.screens.login.TokenScannerSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class AuthMethod {
    CREDENTIALS, TOKEN
}

/**
 * Input length limits to prevent memory issues and match server-side constraints.
 */
private object InputLimits {
    const val URL_MAX_LENGTH = 2048     // Standard URL limit
    const val TOKEN_MAX_LENGTH = 256    // API token limit
    const val USERNAME_MAX_LENGTH = 150 // Django default
    const val PASSWORD_MAX_LENGTH = 128 // Standard password limit
}

sealed class SetupState {
    data object Idle : SetupState()
    data object Testing : SetupState()
    data object Success : SetupState()
    data class Error(val message: String) : SetupState()
}

@Composable
fun SimplifiedSetupScreen(
    onSuccess: () -> Unit,
    isEditMode: Boolean = false,
    viewModel: LoginViewModel = hiltViewModel()
) {
    var authMethod by remember { mutableStateOf(AuthMethod.TOKEN) }
    var serverUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var setupState by remember { mutableStateOf<SetupState>(SetupState.Idle) }
    var showTokenScanner by remember { mutableStateOf(false) }
    var showHttpFallbackDialog by remember { mutableStateOf(false) }
    var httpFallbackAcceptedForSession by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    val serverStatus by viewModel.serverStatus.collectAsState()
    val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues()

    val isServerValid = serverStatus is ServerStatus.Success

    // Extract host from detected server URL for HTTP fallback checking
    val currentHost = remember(serverStatus) {
        (serverStatus as? ServerStatus.Success)?.url?.let { url ->
            try {
                java.net.URI(url).host
            } catch (e: Exception) {
                null
            }
        }
    }

    // Check if HTTP fallback requires warning (not already accepted)
    val needsHttpFallbackWarning = remember(serverStatus, httpFallbackAcceptedForSession, currentHost) {
        val status = serverStatus as? ServerStatus.Success ?: return@remember false
        val host = currentHost ?: return@remember false
        status.isHttpFallback && !httpFallbackAcceptedForSession && !viewModel.isHttpAcceptedForHost(host)
    }

    // Show HTTP fallback dialog when needed
    LaunchedEffect(needsHttpFallbackWarning) {
        if (needsHttpFallbackWarning) {
            showHttpFallbackDialog = true
        }
    }

    // Handle ViewModel state changes
    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is LoginUiState.Loading -> setupState = SetupState.Testing
            is LoginUiState.Success -> {
                setupState = SetupState.Success
                delay(1500) // Show success message briefly
                onSuccess()
            }
            is LoginUiState.Error -> setupState = SetupState.Error(state.message)
            is LoginUiState.SslError -> {
                // SSL error will be handled by the dialog below
                setupState = SetupState.Idle
            }
            is LoginUiState.RateLimited -> {
                // Rate limiting - show error with lockout message
                setupState = SetupState.Error(state.message)
            }
            is LoginUiState.Idle -> {} // Do nothing
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp)
                .padding(navigationBarPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Title
            Text(
                text = if (isEditMode) stringResource(R.string.setup_title_edit) else stringResource(R.string.setup_title_connect),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold
                ),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isEditMode)
                    stringResource(R.string.setup_subtitle_edit)
                else
                    stringResource(R.string.setup_subtitle_connect),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Authentication Method Tabs
            TabRow(
                selectedTabIndex = authMethod.ordinal,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = authMethod == AuthMethod.TOKEN,
                    onClick = { authMethod = AuthMethod.TOKEN },
                    text = { Text(stringResource(R.string.setup_tab_token)) }
                )
                Tab(
                    selected = authMethod == AuthMethod.CREDENTIALS,
                    onClick = { authMethod = AuthMethod.CREDENTIALS },
                    text = { Text(stringResource(R.string.setup_tab_password)) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Server URL TextField
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { newValue ->
                    val limited = newValue.take(InputLimits.URL_MAX_LENGTH)
                    serverUrl = limited
                    viewModel.onServerUrlChanged(limited)
                    setupState = SetupState.Idle // Reset state on input
                },
                label = { Text(stringResource(R.string.setup_server_url)) },
                placeholder = { Text(stringResource(R.string.setup_server_url_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text(
                        text = when (serverStatus) {
                            is ServerStatus.Idle -> stringResource(R.string.setup_server_url_hint)
                            is ServerStatus.Checking -> stringResource(R.string.setup_checking_server)
                            is ServerStatus.Success -> {
                                if ((serverStatus as ServerStatus.Success).isHttps) {
                                    stringResource(R.string.setup_https_verified)
                                } else {
                                    stringResource(R.string.setup_http_unencrypted)
                                }
                            }
                            is ServerStatus.Error -> (serverStatus as ServerStatus.Error).message
                        },
                        color = when (serverStatus) {
                            is ServerStatus.Success -> MaterialTheme.colorScheme.primary
                            is ServerStatus.Error -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
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
                                contentDescription = if (status.isHttps) stringResource(R.string.setup_https) else stringResource(R.string.setup_http),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        is ServerStatus.Error -> {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = stringResource(R.string.setup_error),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> {}
                    }
                },
                isError = serverStatus is ServerStatus.Error,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    autoCorrect = false
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                ),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                enabled = setupState !is SetupState.Testing && setupState !is SetupState.Success
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Authentication Fields (Token or Password)
            when (authMethod) {
                AuthMethod.TOKEN -> {
                    // Token TextField with QR Scanner
                    OutlinedTextField(
                        value = token,
                        onValueChange = { newValue ->
                            token = newValue.take(InputLimits.TOKEN_MAX_LENGTH)
                            setupState = SetupState.Idle
                        },
                        label = { Text(stringResource(R.string.setup_auth_token)) },
                        placeholder = { Text(stringResource(R.string.setup_auth_token_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (tokenVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            Row {
                                IconButton(onClick = { showTokenScanner = true }) {
                                    Icon(
                                        imageVector = Icons.Default.QrCodeScanner,
                                        contentDescription = stringResource(R.string.setup_scan_qr)
                                    )
                                }
                                IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                    Icon(
                                        imageVector = if (tokenVisible) {
                                            Icons.Default.VisibilityOff
                                        } else {
                                            Icons.Default.Visibility
                                        },
                                        contentDescription = if (tokenVisible) stringResource(R.string.setup_hide_token) else stringResource(R.string.setup_show_token)
                                    )
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                if (serverUrl.isNotBlank() && token.isNotBlank()) {
                                    coroutineScope.launch {
                                        viewModel.loginWithToken(serverUrl, token)
                                    }
                                }
                            }
                        ),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        enabled = setupState !is SetupState.Testing && setupState !is SetupState.Success
                    )
                }
                AuthMethod.CREDENTIALS -> {
                    // Username TextField
                    OutlinedTextField(
                        value = username,
                        onValueChange = { newValue ->
                            username = newValue.take(InputLimits.USERNAME_MAX_LENGTH)
                            setupState = SetupState.Idle
                        },
                        label = { Text(stringResource(R.string.setup_username)) },
                        placeholder = { Text(stringResource(R.string.setup_username_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        enabled = setupState !is SetupState.Testing && setupState !is SetupState.Success
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Password TextField
                    OutlinedTextField(
                        value = password,
                        onValueChange = { newValue ->
                            password = newValue.take(InputLimits.PASSWORD_MAX_LENGTH)
                            setupState = SetupState.Idle
                        },
                        label = { Text(stringResource(R.string.setup_password)) },
                        placeholder = { Text(stringResource(R.string.setup_password_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                    contentDescription = if (passwordVisible) stringResource(R.string.setup_hide_password) else stringResource(R.string.setup_show_password)
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                if (serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                                    coroutineScope.launch {
                                        viewModel.login(serverUrl, username, password)
                                    }
                                }
                            }
                        ),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        enabled = setupState !is SetupState.Testing && setupState !is SetupState.Success
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Status message / Connect button
            when (val state = setupState) {
                is SetupState.Idle -> {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                // Use detected URL from serverStatus if available
                                val urlToUse = (serverStatus as? ServerStatus.Success)?.url ?: serverUrl
                                when (authMethod) {
                                    AuthMethod.TOKEN -> viewModel.loginWithToken(urlToUse, token)
                                    AuthMethod.CREDENTIALS -> viewModel.login(urlToUse, username, password)
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = when (authMethod) {
                            AuthMethod.TOKEN -> isServerValid && token.isNotBlank()
                            AuthMethod.CREDENTIALS -> isServerValid && username.isNotBlank() && password.isNotBlank()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.setup_connect),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
                is SetupState.Testing -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                is SetupState.Success -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = stringResource(R.string.setup_success),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                is SetupState.Error -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = stringResource(R.string.setup_error),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(32.dp)
                                )
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    // Use detected URL from serverStatus if available
                                    val urlToUse = (serverStatus as? ServerStatus.Success)?.url ?: serverUrl
                                    when (authMethod) {
                                        AuthMethod.TOKEN -> viewModel.loginWithToken(urlToUse, token)
                                        AuthMethod.CREDENTIALS -> viewModel.login(urlToUse, username, password)
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.setup_retry),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Help text
            Text(
                text = when (authMethod) {
                    AuthMethod.TOKEN -> stringResource(R.string.setup_token_help)
                    AuthMethod.CREDENTIALS -> stringResource(R.string.setup_password_help)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        // Token Scanner Sheet
        if (showTokenScanner) {
            TokenScannerSheet(
                onDismiss = { showTokenScanner = false },
                onTokenFound = { scannedToken ->
                    token = scannedToken
                    showTokenScanner = false
                    setupState = SetupState.Idle
                }
            )
        }

        // SSL Certificate Dialog
        if (uiState is LoginUiState.SslError) {
            val sslError = uiState as LoginUiState.SslError
            SslCertificateDialog(
                host = sslError.host,
                errorMessage = sslError.message,
                onAccept = {
                    viewModel.acceptSslCertificate(sslError.host)
                    // Retry login after accepting
                    coroutineScope.launch {
                        delay(500) // Small delay to ensure certificate is accepted
                        val urlToUse = (serverStatus as? ServerStatus.Success)?.url ?: serverUrl
                        when (authMethod) {
                            AuthMethod.TOKEN -> viewModel.loginWithToken(urlToUse, token)
                            AuthMethod.CREDENTIALS -> viewModel.login(urlToUse, username, password)
                        }
                    }
                },
                onCancel = {
                    viewModel.resetState()
                }
            )
        }

        // HTTP Fallback Warning Dialog
        if (showHttpFallbackDialog && currentHost != null) {
            HttpFallbackWarningDialog(
                host = currentHost,
                onAccept = { rememberChoice ->
                    showHttpFallbackDialog = false
                    httpFallbackAcceptedForSession = true
                    if (rememberChoice) {
                        // Store permanently
                        viewModel.acceptHttpForHost(currentHost)
                    }
                },
                onCancel = {
                    showHttpFallbackDialog = false
                    // Reset server status to allow user to try again with a different URL
                    viewModel.clearServerStatus()
                }
            )
        }
    }
}
