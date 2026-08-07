package dev.op.deptor.ui.member

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.Transaction
import dev.op.deptor.domain.model.TransactionType
import dev.op.deptor.ui.common.EmptyState
import dev.op.deptor.ui.common.LoadingState
import dev.op.deptor.ui.common.formatMoney
import dev.op.deptor.ui.theme.creditColor
import dev.op.deptor.ui.theme.debtColor
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberDetailScreen(
    onBack: () -> Unit,
    viewModel: MemberDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.memberName.ifBlank { "Člen" }) },
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

            state.error != null -> EmptyState(
                icon = Icons.Default.Warning,
                title = "Není co zobrazit",
                description = state.error.orEmpty(),
                modifier = Modifier.padding(padding),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
            ) {
                item { BalanceCard(state) }

                if (state.debts.isEmpty()) {
                    item {
                        Spacer(Modifier.height(24.dp))
                        Text(
                            text = "S nikým ve skupině nemá nevyrovnaný účet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (state.owes.isNotEmpty()) {
                    item { SectionTitle("Dluží") }
                    items(state.owes) { debt ->
                        DebtRow(debt = debt, currency = state.currency, owed = true)
                    }
                }

                if (state.owedToMember.isNotEmpty()) {
                    item { SectionTitle("Dluží mu") }
                    items(state.owedToMember) { debt ->
                        DebtRow(debt = debt, currency = state.currency, owed = false)
                    }
                }

                item { SectionTitle("Historie") }

                if (state.transactions.isEmpty()) {
                    item {
                        Text(
                            text = "Zatím žádná transakce.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(state.transactions, key = { it.id.value }) { transaction ->
                        TransactionRow(transaction, state.currency)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun BalanceCard(state: MemberDetailUiState) {
    val amount = state.balance?.amount
    val color = when {
        amount == null || amount.isZero -> MaterialTheme.colorScheme.onSurfaceVariant
        amount.isPositive -> MaterialTheme.colorScheme.creditColor
        else -> MaterialTheme.colorScheme.debtColor
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = when {
                    amount == null || amount.isZero -> "Vyrovnán"
                    amount.isPositive -> "Celkem má dostat"
                    else -> "Celkem má poslat"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatMoney(amount?.abs() ?: dev.op.deptor.domain.model.Money.ZERO, state.currency),
                style = MaterialTheme.typography.headlineMedium,
                color = color,
            )
            Text(
                // Rozdíl, který mate nejvíc: bilance skupiny navrhuje nejkratší cestu
                // k vyrovnání, tenhle seznam ukazuje skutečné vztahy.
                text = "Součet dluhů níž odpovídá tomuhle saldu.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(24.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun DebtRow(debt: MemberDebt, currency: CurrencyCode, owed: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(debt.counterpartName, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = formatMoney(debt.amount, currency),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (owed) {
                MaterialTheme.colorScheme.debtColor
            } else {
                MaterialTheme.colorScheme.creditColor
            },
        )
    }
}

private val dateFormatter = DateTimeFormatter.ofPattern("d. M. yyyy")

@Composable
private fun TransactionRow(transaction: Transaction, currency: CurrencyCode) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.padding(end = 12.dp)) {
            Text(
                text = transaction.description.ifBlank {
                    if (transaction.type == TransactionType.SETTLEMENT) "Vyrovnání" else "Výdaj"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = transaction.date.atZone(ZoneId.systemDefault()).format(dateFormatter),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = formatMoney(transaction.amount, currency),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
