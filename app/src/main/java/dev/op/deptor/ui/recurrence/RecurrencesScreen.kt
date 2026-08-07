package dev.op.deptor.ui.recurrence

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.op.deptor.domain.recurrence.Recurrence
import dev.op.deptor.domain.recurrence.RecurrenceFrequency
import dev.op.deptor.ui.common.EmptyState
import dev.op.deptor.ui.common.LoadingState
import dev.op.deptor.ui.common.formatMoney
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurrencesScreen(
    onBack: () -> Unit,
    viewModel: RecurrencesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Opakující se výdaje") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))

            state.recurrences.isEmpty() -> EmptyState(
                icon = Icons.Default.Autorenew,
                title = "Žádné opakování",
                description = "Když u výdaje zapneš opakování, bude se vytvářet sám.",
                modifier = Modifier.padding(padding),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
            ) {
                items(state.recurrences, key = { it.id.value }) { recurrence ->
                    RecurrenceRow(
                        recurrence = recurrence,
                        currency = state.currency,
                        onStop = { viewModel.stopRecurrence(recurrence.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

private val dateFormatter = DateTimeFormatter.ofPattern("d. M. yyyy")

@Composable
private fun RecurrenceRow(
    recurrence: Recurrence,
    currency: dev.op.deptor.domain.model.CurrencyCode,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = recurrence.template.description.ifBlank { "Opakovaný výdaj" },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "${frequencyLabel(recurrence.rule.frequency)} · " +
                    formatMoney(recurrence.template.amount, currency),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                // Nejbližší termín, ne datum založení — to uživatele nezajímá.
                text = nextOccurrenceText(recurrence),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onStop) { Text("Zrušit") }
    }
}

private fun frequencyLabel(frequency: RecurrenceFrequency) = when (frequency) {
    RecurrenceFrequency.DAILY -> "Denně"
    RecurrenceFrequency.WEEKLY -> "Týdně"
    RecurrenceFrequency.MONTHLY -> "Měsíčně"
    RecurrenceFrequency.YEARLY -> "Ročně"
}

private fun nextOccurrenceText(recurrence: Recurrence): String {
    val today = java.time.LocalDate.now()
    var index = 0
    while (index < 1_000) {
        val date = recurrence.rule.occurrence(index)
        if (!date.isBefore(today)) return "Příště ${date.format(dateFormatter)}"
        index++
    }
    return "Bez dalšího termínu"
}
