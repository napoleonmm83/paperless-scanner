package com.paperless.scanner.ui.screens.documents.sharelinks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R
import com.paperless.scanner.domain.error.PaperlessException
import com.paperless.scanner.domain.error.getLocalizedMessage
import com.paperless.scanner.domain.model.FeatureStatus
import com.paperless.scanner.domain.model.ShareFileVersion
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareLinksDialog(
    state: ShareLinksUiState,
    onRetry: () -> Unit,
    onCreate: (Int?, ShareFileVersion) -> Unit,
    onCopy: (Int) -> Unit,
    onShare: (Int) -> Unit,
    onRevoke: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val busy = rememberUpdatedState(state.busy)
    val sheet = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target -> target != SheetValue.Hidden || !busy.value },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!busy.value) onClose() },
        sheetState = sheet,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        // Material3 1.3.1 captures native Back dismissal at dialog creation.
        // Handle it here so a later busy change is respected too.
        BackHandler { if (!busy.value) onClose() }
        ShareLinksSheet(state, onRetry, onCreate, onCopy, onShare, onRevoke, onClose)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareLinksSheet(
    state: ShareLinksUiState,
    onRetry: () -> Unit,
    onCreate: (Int?, ShareFileVersion) -> Unit,
    onCopy: (Int) -> Unit,
    onShare: (Int) -> Unit,
    onRevoke: (Int) -> Unit,
    onClose: () -> Unit,
) {
    var days by remember { mutableStateOf<Int?>(7) }
    var version by remember(state.hasArchiveVersion) {
        mutableStateOf(if (state.hasArchiveVersion) ShareFileVersion.ARCHIVE else ShareFileVersion.ORIGINAL)
    }
    var pendingRevoke by remember { mutableStateOf<Int?>(null) }
    val available = state.availability == FeatureStatus.AVAILABLE
    val enabled = available && !state.busy
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.share_links_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.share_links_public_notice), style = MaterialTheme.typography.bodyMedium)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!available) Text(stringResource(R.string.share_links_unavailable))
        state.error?.let {
            Text(PaperlessException.from(it).getLocalizedMessage(LocalContext.current), color = MaterialTheme.colorScheme.error)
        }
        if (!state.loaded || state.error != null) {
            TextButton(onClick = onRetry, enabled = enabled) { Text(stringResource(R.string.document_detail_retry)) }
        }
        if (state.loaded && available) {
            Text(stringResource(R.string.share_links_file), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = version == ShareFileVersion.ORIGINAL, onClick = { version = ShareFileVersion.ORIGINAL }, enabled = enabled,
                    label = { Text(stringResource(R.string.share_links_original)) })
                if (state.hasArchiveVersion) FilterChip(selected = version == ShareFileVersion.ARCHIVE, onClick = { version = ShareFileVersion.ARCHIVE }, enabled = enabled,
                    label = { Text(stringResource(R.string.share_links_archive)) })
            }
            Text(stringResource(R.string.share_links_expiration), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 7, 30, null).forEach { choice ->
                    FilterChip(selected = days == choice, onClick = { days = choice }, enabled = enabled,
                        label = { Text(when (choice) {
                            null -> stringResource(R.string.share_links_never)
                            1 -> stringResource(R.string.share_links_day)
                            else -> stringResource(R.string.share_links_days, choice)
                        }) })
                }
            }
            Button(onClick = { onCreate(days, version) }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("share-link-create")) {
                Text(stringResource(R.string.share_links_create))
            }
            Text(stringResource(R.string.share_links_existing), style = MaterialTheme.typography.titleMedium)
            if (state.links.isEmpty()) Text(stringResource(R.string.share_links_empty))
            state.links.forEach { link ->
                Card(Modifier.fillMaxWidth().testTag("share-link-${link.id}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.share_links_created, readableDate(link.created)))
                        Text(if (link.expiration == null) stringResource(R.string.share_links_never) else stringResource(R.string.share_links_expires, readableDate(link.expiration)))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onCopy(link.id) }, enabled = enabled) { Text(stringResource(R.string.share_links_copy)) }
                            TextButton(onClick = { onShare(link.id) }, enabled = enabled) { Text(stringResource(R.string.share_links_share)) }
                            TextButton(onClick = { pendingRevoke = link.id }, enabled = enabled) { Text(stringResource(R.string.share_links_revoke)) }
                        }
                    }
                }
            }
        }
        TextButton(onClick = onClose, enabled = !state.busy) { Text(stringResource(R.string.share_links_close)) }
    }
    pendingRevoke?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingRevoke = null },
            title = { Text(stringResource(R.string.share_links_revoke_title)) },
            text = { Text(stringResource(R.string.share_links_revoke_notice)) },
            confirmButton = { TextButton(onClick = { pendingRevoke = null; onRevoke(id) }, enabled = enabled, modifier = Modifier.testTag("share-link-revoke-confirm")) { Text(stringResource(R.string.share_links_revoke)) } },
            dismissButton = { TextButton(onClick = { pendingRevoke = null }) { Text(stringResource(R.string.document_detail_cancel_button)) } },
        )
    }
}

private fun readableDate(raw: String): String = try {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.parse(raw))
} catch (_: java.time.DateTimeException) { raw }
