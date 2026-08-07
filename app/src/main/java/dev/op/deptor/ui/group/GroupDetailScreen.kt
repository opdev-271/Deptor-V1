package dev.op.deptor.ui.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.op.deptor.domain.balance.Transfer
import dev.op.deptor.domain.invite.Invite
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.ExpenseCategory
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.Transaction
import dev.op.deptor.domain.model.TransactionType
import dev.op.deptor.domain.money.PercentFormat
import dev.op.deptor.domain.money.convertedTo
import dev.op.deptor.domain.money.isForeign
import dev.op.deptor.ui.common.EmptyState
import dev.op.deptor.ui.common.LoadingState
import dev.op.deptor.domain.report.CategoryTotal
import dev.op.deptor.ui.common.UNCATEGORIZED_ICON
import dev.op.deptor.ui.common.UNCATEGORIZED_LABEL
import dev.op.deptor.domain.report.TransactionCsv
import dev.op.deptor.ui.common.formatMoney
import dev.op.deptor.ui.common.shareCsv
import dev.op.deptor.ui.invite.ShareInviteDialog
import dev.op.deptor.ui.common.icon
import dev.op.deptor.ui.common.label
import dev.op.deptor.ui.theme.creditColor
import dev.op.deptor.ui.theme.debtColor
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    onBack: () -> Unit,
    onAddExpense: (GroupId) -> Unit,
    onManageMembers: (GroupId) -> Unit,
    onShowRecurrences: (GroupId) -> Unit,
    onSettle: (GroupId, Transfer) -> Unit,
    onOpenMember: (GroupId, MemberId) -> Unit,
    viewModel: GroupDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showInvite by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.group?.name ?: "Skupina") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět")
                    }
                },
                actions = {
                    // Sdílet jde jen skupina, která už má kód — u čerstvě založené
                    // dorazí ze serveru se zpožděním.
                    if (state.group?.inviteCode != null) {
                        IconButton(onClick = { showInvite = true }) {
                            Icon(Icons.Default.Share, contentDescription = "Pozvat do skupiny")
                        }
                    }
                    IconButton(onClick = { onShowRecurrences(viewModel.groupId) }) {
                        Icon(Icons.Default.Autorenew, contentDescription = "Opakující se výdaje")
                    }
                    IconButton(onClick = { onManageMembers(viewModel.groupId) }) {
                        Icon(Icons.Default.Group, contentDescription = "Členové")
                    }
                    // Exportovat jde jen to, co existuje.
                    if (state.transactions.isNotEmpty()) {
                        val context = LocalContext.current
                        IconButton(
                            onClick = {
                                val group = state.group ?: return@IconButton
                                shareCsv(
                                    context = context,
                                    fileName = TransactionCsv.fileName(group),
                                    content = TransactionCsv.build(group, state.transactions),
                                )
                            },
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Exportovat do CSV")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAddExpense(viewModel.groupId) }) {
                Icon(Icons.Default.Add, contentDescription = "Přidat výdaj")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Výdaje") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Bilance") },
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Přehled") },
                )
            }

            if (state.rejected.isNotEmpty()) {
                RejectedBanner(count = state.rejected.size)
            }

            when {
                state.loading -> LoadingState()
                state.error != null -> EmptyState(
                    icon = Icons.Default.Warning,
                    title = "Skupina není dostupná",
                    description = state.error.orEmpty(),
                )

                selectedTab == 0 -> TransactionList(
                    state = state,
                    onDelete = viewModel::deleteTransaction,
                )

                selectedTab == 1 -> BalanceTab(
                    state = state,
                    onSettle = { transfer -> onSettle(viewModel.groupId, transfer) },
                    onOpenMember = { memberId -> onOpenMember(viewModel.groupId, memberId) },
                )

                else -> CategoryTab(state)
            }
        }
    }

    val group = state.group
    val inviteCode = group?.inviteCode
    if (showInvite && group != null && inviteCode != null) {
        ShareInviteDialog(
            groupName = group.name,
            invite = Invite(group.id, inviteCode),
            onDismiss = { showInvite = false },
        )
    }
}

/**
 * Upozornění na dokumenty, které se nepodařilo přečíst.
 *
 * Bez něj by chybějící transakce jen tiše posunula salda a uživatel by neměl šanci
 * zjistit, že vidí neúplná data.
 */
@Composable
private fun RejectedBanner(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (count == 1) {
                "Jeden záznam se nepodařilo načíst, bilance nemusí sedět."
            } else {
                "$count záznamů se nepodařilo načíst, bilance nemusí sedět."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun TransactionList(
    state: GroupDetailUiState,
    onDelete: (Transaction) -> Unit,
) {
    if (state.transactions.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Receipt,
            title = "Zatím žádný výdaj",
            description = "Přidej první výdaj a Deptor spočítá, kdo komu kolik dluží.",
        )
        return
    }

    val currency = state.group?.currency ?: CurrencyCode.CZK

    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        items(state.transactions, key = { it.id.value }) { transaction ->
            TransactionRow(
                transaction = transaction,
                currency = currency,
                state = state,
                onDelete = { onDelete(transaction) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun TransactionRow(
    transaction: Transaction,
    currency: CurrencyCode,
    state: GroupDetailUiState,
    onDelete: () -> Unit,
) {
    val payerNames = transaction.payers.joinToString { state.memberName(it.memberId) }

    val category = ExpenseCategory.fromId(transaction.categoryId)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (transaction.type != TransactionType.SETTLEMENT) {
            Icon(
                imageVector = category?.icon ?: UNCATEGORIZED_ICON,
                contentDescription = category?.label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp).size(20.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = transaction.description.ifBlank {
                    if (transaction.type == TransactionType.SETTLEMENT) "Vyrovnání" else "Výdaj"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = when (transaction.type) {
                    TransactionType.SETTLEMENT ->
                        "$payerNames → ${transaction.splits.joinToString { state.memberName(it.memberId) }}"

                    else -> "zaplatil: $payerNames · ${dateText(transaction)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            // U cizí měny se ukazuje obojí: nahoře co se do sald započítalo,
            // pod tím původní částka, aby šla porovnat s účtenkou.
            Text(
                text = formatMoney(transaction.convertedTo(currency).amount, currency),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (transaction.isForeign(currency)) {
                Text(
                    text = formatMoney(transaction.amount, transaction.currency),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Smazat ${transaction.description}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val dateFormatter = DateTimeFormatter.ofPattern("d. M. yyyy")

private fun dateText(transaction: Transaction): String =
    transaction.date.atZone(ZoneId.systemDefault()).format(dateFormatter)

@Composable
private fun BalanceTab(
    state: GroupDetailUiState,
    onSettle: (Transfer) -> Unit,
    onOpenMember: (MemberId) -> Unit,
) {
    val currency = state.group?.currency ?: CurrencyCode.CZK

    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Text("Salda", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Klepnutím na člena uvidíš, komu konkrétně dluží.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        items(state.balances, key = { it.member.id.value }) { entry ->
            BalanceRow(
                name = entry.member.name,
                amount = entry.balance.amount,
                currency = currency,
                onClick = { onOpenMember(entry.member.id) },
            )
        }

        item {
            Spacer(Modifier.height(24.dp))
            Text("Návrh vyrovnání", style = MaterialTheme.typography.titleMedium)
            // Vědomě neslibujeme „nejmenší počet plateb" — hledání skutečného minima je
            // NP-těžké a `simplify()` je hladová aproximace. Slibovat zaručené optimum
            // by bylo nepravdivé.
            Text(
                text = "Místo aby platil každý s každým, stačí tyhle platby.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (state.suggestedTransfers.isEmpty()) {
            item {
                Text(
                    text = "Všichni jsou vyrovnaní.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(state.suggestedTransfers) { suggestion ->
                SuggestionCard(
                    suggestion = suggestion,
                    currency = currency,
                    onSettle = { onSettle(suggestion.transfer) },
                )
            }
        }
    }
}

/**
 * Na co skupina utrácí.
 *
 * Vyrovnání se sem nepočítají — jsou to přesuny peněz, ne útrata.
 */
@Composable
private fun CategoryTab(state: GroupDetailUiState) {
    if (state.categories.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Category,
            title = "Zatím není co shrnout",
            description = "Přidej výdaje a uvidíš, na co skupina utrácí nejvíc.",
        )
        return
    }

    val currency = state.group?.currency ?: CurrencyCode.CZK
    val total = Money(state.categories.sumOf { it.total.minor })

    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Text("Celkem utraceno", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = formatMoney(total, currency),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Bez vyrovnání — ta jen přesouvají peníze mezi členy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }

        items(state.categories) { entry ->
            CategoryRow(entry = entry, currency = currency)
        }
    }
}

@Composable
private fun CategoryRow(entry: CategoryTotal, currency: CurrencyCode) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = entry.category?.icon ?: UNCATEGORIZED_ICON,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp).size(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.category?.label ?: UNCATEGORIZED_LABEL,
                style = MaterialTheme.typography.bodyLarge,
            )
            LinearProgressIndicator(
                progress = { entry.shareBasisPoints / 10_000f },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        Column(
            modifier = Modifier.padding(start = 12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = formatMoney(entry.total, currency),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = PercentFormat.format(entry.shareBasisPoints),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BalanceRow(
    name: String,
    amount: Money,
    currency: CurrencyCode,
    onClick: () -> Unit,
) {
    // Barva sama o sobě informaci nenese — vedle ní je vždy i slovní popis,
    // aby to fungovalo i pro toho, kdo zelenou od červené nerozliší.
    val color = when {
        amount.isPositive -> MaterialTheme.colorScheme.creditColor
        amount.isNegative -> MaterialTheme.colorScheme.debtColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = when {
                    amount.isPositive -> "má dostat"
                    amount.isNegative -> "má poslat"
                    else -> "vyrovnán"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = formatMoney(amount.abs(), currency),
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SuggestionCard(
    suggestion: SuggestedTransfer,
    currency: CurrencyCode,
    onSettle: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "${suggestion.fromName} → ${suggestion.toName}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = formatMoney(suggestion.transfer.amount, currency),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            OutlinedButton(onClick = onSettle) {
                Text("Vyrovnat")
            }
        }
    }
}
