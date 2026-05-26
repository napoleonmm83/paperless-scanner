package com.paperless.scanner.ui.screens.labels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R
import com.paperless.scanner.ui.theme.PaperlessScannerTheme

@Composable
fun SortFilterSheet(
    currentSort: LabelSortOption,
    currentFilter: LabelFilterOption,
    onApply: (LabelSortOption, LabelFilterOption) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedSort by remember { mutableStateOf(currentSort) }
    var selectedFilter by remember { mutableStateOf(currentFilter) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.labels_sort_filter),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Sort Section
        Text(
            text = stringResource(R.string.labels_sort_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column(modifier = Modifier.selectableGroup()) {
            SortOption(
                text = stringResource(R.string.labels_sort_name_asc),
                selected = selectedSort == LabelSortOption.NAME_ASC,
                onClick = { selectedSort = LabelSortOption.NAME_ASC }
            )
            SortOption(
                text = stringResource(R.string.labels_sort_name_desc),
                selected = selectedSort == LabelSortOption.NAME_DESC,
                onClick = { selectedSort = LabelSortOption.NAME_DESC }
            )
            SortOption(
                text = stringResource(R.string.labels_sort_count_desc),
                selected = selectedSort == LabelSortOption.COUNT_DESC,
                onClick = { selectedSort = LabelSortOption.COUNT_DESC }
            )
            SortOption(
                text = stringResource(R.string.labels_sort_count_asc),
                selected = selectedSort == LabelSortOption.COUNT_ASC,
                onClick = { selectedSort = LabelSortOption.COUNT_ASC }
            )
            SortOption(
                text = stringResource(R.string.labels_sort_newest),
                selected = selectedSort == LabelSortOption.NEWEST,
                onClick = { selectedSort = LabelSortOption.NEWEST }
            )
            SortOption(
                text = stringResource(R.string.labels_sort_oldest),
                selected = selectedSort == LabelSortOption.OLDEST,
                onClick = { selectedSort = LabelSortOption.OLDEST }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Spacer(modifier = Modifier.height(16.dp))

        // Filter Section
        Text(
            text = stringResource(R.string.labels_filter_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column(modifier = Modifier.selectableGroup()) {
            SortOption(
                text = stringResource(R.string.labels_filter_all),
                selected = selectedFilter == LabelFilterOption.ALL,
                onClick = { selectedFilter = LabelFilterOption.ALL }
            )
            SortOption(
                text = stringResource(R.string.labels_filter_with_docs),
                selected = selectedFilter == LabelFilterOption.WITH_DOCUMENTS,
                onClick = { selectedFilter = LabelFilterOption.WITH_DOCUMENTS }
            )
            SortOption(
                text = stringResource(R.string.labels_filter_empty),
                selected = selectedFilter == LabelFilterOption.EMPTY,
                onClick = { selectedFilter = LabelFilterOption.EMPTY }
            )
            SortOption(
                text = stringResource(R.string.labels_filter_many_docs),
                selected = selectedFilter == LabelFilterOption.MANY_DOCUMENTS,
                onClick = { selectedFilter = LabelFilterOption.MANY_DOCUMENTS }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                onClick = onReset,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.labels_reset_button),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Card(
                onClick = { onApply(selectedSort, selectedFilter) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.labels_apply_button),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SortOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary
            )
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Preview
@Composable
private fun SortFilterSheetPreview() {
    PaperlessScannerTheme {
        SortFilterSheet(
            currentSort = LabelSortOption.NAME_ASC,
            currentFilter = LabelFilterOption.ALL,
            onApply = { _, _ -> },
            onReset = {},
            onDismiss = {}
        )
    }
}
