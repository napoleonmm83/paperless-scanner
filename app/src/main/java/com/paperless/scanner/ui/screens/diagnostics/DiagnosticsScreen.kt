package com.paperless.scanner.ui.screens.diagnostics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.paperless.scanner.R
import com.paperless.scanner.data.api.models.ServerStatusResponse

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onNavigateBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.diagnostics_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadDiagnostics() }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.diagnostics_refresh)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> {
                    LoadingState()
                }
                uiState.isUnavailable -> {
                    UnavailableState()
                }
                uiState.error != null -> {
                    ErrorState(
                        error = uiState.error!!,
                        onRetry = { viewModel.loadDiagnostics() }
                    )
                }
                uiState.serverStatus != null -> {
                    DiagnosticsContent(
                        serverStatus = uiState.serverStatus!!,
                        healthStatus = uiState.healthStatus,
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.diagnostics_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UnavailableState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.diagnostics_unavailable),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.diagnostics_unavailable_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ErrorState(error: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    text = stringResource(R.string.diagnostics_error),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsContent(
    serverStatus: ServerStatusResponse,
    healthStatus: HealthStatus,
    viewModel: DiagnosticsViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Health Status Banner
        HealthStatusBanner(healthStatus)

        // Version Section
        DiagnosticsSection(title = stringResource(R.string.diagnostics_section_version)) {
            DiagnosticsInfoItem(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.diagnostics_version),
                value = serverStatus.paperlessVersion ?: stringResource(R.string.diagnostics_unavailable)
            )
        }

        // System Info Section
        if (serverStatus.serverOs != null || serverStatus.installType != null) {
            DiagnosticsSection(title = stringResource(R.string.diagnostics_section_system)) {
                serverStatus.serverOs?.let { os ->
                    DiagnosticsInfoItem(
                        icon = Icons.Filled.Computer,
                        title = stringResource(R.string.diagnostics_server_os),
                        value = os
                    )
                }
                serverStatus.installType?.let { type ->
                    if (serverStatus.serverOs != null) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                    DiagnosticsInfoItem(
                        icon = Icons.Filled.Category,
                        title = stringResource(R.string.diagnostics_install_type),
                        value = type
                    )
                }
            }
        }

        // Storage Section
        serverStatus.storage?.let { storage ->
            if (storage.total != null && storage.available != null) {
                StorageSection(
                    total = storage.total,
                    available = storage.available,
                    viewModel = viewModel
                )
            }
        }

        // Database Section
        serverStatus.database?.let { database ->
            DatabaseSection(database = database)
        }

        // Task System Section
        serverStatus.tasks?.let { tasks ->
            TaskSystemSection(tasks = tasks)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun HealthStatusBanner(healthStatus: HealthStatus) {
    val (icon, text, containerColor, contentColor) = when (healthStatus) {
        HealthStatus.GOOD -> Quadruple(
            Icons.Filled.CheckCircle,
            stringResource(R.string.diagnostics_health_good),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        HealthStatus.WARNING -> Quadruple(
            Icons.Filled.Warning,
            stringResource(R.string.diagnostics_health_warning),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        HealthStatus.CRITICAL -> Quadruple(
            Icons.Filled.Error,
            stringResource(R.string.diagnostics_health_critical),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
        HealthStatus.UNKNOWN -> Quadruple(
            Icons.Filled.HelpOutline,
            stringResource(R.string.diagnostics_status_unknown),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = contentColor
            )
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        }
    }
}

@Composable
private fun DiagnosticsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            letterSpacing = MaterialTheme.typography.labelLarge.letterSpacing,
            color = MaterialTheme.colorScheme.primary
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.padding(4.dp),
                content = content
            )
        }
    }
}

@Composable
private fun DiagnosticsInfoItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StorageSection(
    total: Long,
    available: Long,
    viewModel: DiagnosticsViewModel
) {
    val usedPercentage = viewModel.calculateUsedPercentage(available, total)
    val progressColor = when {
        usedPercentage >= 95 -> MaterialTheme.colorScheme.error
        usedPercentage >= 85 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    DiagnosticsSection(title = stringResource(R.string.diagnostics_section_storage)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Progress Bar
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${stringResource(R.string.diagnostics_storage_used)}: $usedPercentage%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = viewModel.formatBytes(total - available),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LinearProgressIndicator(
                    progress = { (usedPercentage / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = progressColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Storage Details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.diagnostics_storage_total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = viewModel.formatBytes(total),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.diagnostics_storage_available),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = viewModel.formatBytes(available),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (usedPercentage >= 95) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }

            // Warning Messages
            if (usedPercentage >= 95) {
                Text(
                    text = stringResource(R.string.diagnostics_storage_critical),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (usedPercentage >= 85) {
                Text(
                    text = stringResource(R.string.diagnostics_storage_warning),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun DatabaseSection(database: com.paperless.scanner.data.api.models.DatabaseInfo) {
    // Only show section if at least one field is available
    if (database.type == null && database.status == null && database.migrationStatus?.unappliedMigrations == null) {
        return
    }

    DiagnosticsSection(title = stringResource(R.string.diagnostics_section_database)) {
        database.type?.let { type ->
            DiagnosticsInfoItem(
                icon = Icons.Filled.Storage,
                title = stringResource(R.string.diagnostics_database_type),
                value = type
            )
        }

        database.status?.let { status ->
            if (database.type != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }
            StatusItem(
                title = stringResource(R.string.diagnostics_database_status),
                status = status
            )
        }

        database.migrationStatus?.let { migration ->
            if (database.type != null || database.status != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }
            migration.unappliedMigrations?.let { migrations ->
                DiagnosticsInfoItem(
                    icon = Icons.Filled.Update,
                    title = stringResource(R.string.diagnostics_database_migrations),
                    value = migrations.size.toString()
                )
            }
        }
    }
}

@Composable
private fun TaskSystemSection(tasks: com.paperless.scanner.data.api.models.TasksInfo) {
    // Only show section if at least one status is available
    if (tasks.redisStatus == null && tasks.celeryStatus == null) {
        return
    }

    DiagnosticsSection(title = stringResource(R.string.diagnostics_section_tasks)) {
        tasks.redisStatus?.let { status ->
            StatusItem(
                title = stringResource(R.string.diagnostics_redis_status),
                status = status
            )
        }

        tasks.celeryStatus?.let { status ->
            if (tasks.redisStatus != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }
            StatusItem(
                title = stringResource(R.string.diagnostics_celery_status),
                status = status
            )
        }
    }
}

@Composable
private fun StatusItem(title: String, status: String) {
    val isOk = status.equals("OK", ignoreCase = true)
    val isError = status.equals("ERROR", ignoreCase = true)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when {
                isOk -> Icons.Filled.CheckCircle
                isError -> Icons.Filled.Error
                else -> Icons.Filled.HelpOutline
            },
            contentDescription = null,
            tint = when {
                isOk -> MaterialTheme.colorScheme.primary
                isError -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = when {
                    isOk -> stringResource(R.string.diagnostics_status_ok)
                    isError -> stringResource(R.string.diagnostics_status_error)
                    else -> status
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = when {
                    isOk -> MaterialTheme.colorScheme.primary
                    isError -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

// Helper data class for multiple values
private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
