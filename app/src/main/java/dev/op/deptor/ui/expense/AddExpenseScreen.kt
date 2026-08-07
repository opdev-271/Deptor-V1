package dev.op.deptor.ui.expense

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.ExpenseCategory
import dev.op.deptor.domain.model.Member
import dev.op.deptor.domain.model.SplitMethod
import dev.op.deptor.domain.recurrence.RecurrenceFrequency
import dev.op.deptor.domain.money.MoneyFormat
import dev.op.deptor.ui.common.LoadingState
import dev.op.deptor.ui.common.icon
import dev.op.deptor.ui.common.label
import dev.op.deptor.ui.common.formatMoney

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddExpenseScreen(
    onDone: () -> Unit,
    viewModel: AddExpenseViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.settlement) "Vyrovnání" else "Nový výdaj") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět")
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
        ) {
            // U rozpadu na položky se částka dopočítává, takže je pole jen ke čtení —
            // jinak by uživatel psal číslo, které se stejně ignoruje.
            val computedAmount = state.splitMethod == SplitMethod.ITEMS && !state.settlement

            OutlinedTextField(
                value = if (computedAmount) {
                    MoneyFormat.toEditableText(state.billTotal, state.currency)
                } else {
                    state.amountText
                },
                onValueChange = viewModel::onAmountChange,
                readOnly = computedAmount,
                label = { Text(if (computedAmount) "Částka (ze součtu položek)" else "Částka") },
                suffix = {
                    CurrencyPicker(
                        selected = state.currency,
                        onSelect = viewModel::onCurrencyChange,
                    )
                },
                singleLine = true,
                isError = state.amountInvalid,
                supportingText = if (state.amountInvalid) {
                    { Text("Zadej částku, například 250 nebo 250,50") }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.needsRate) {
                Spacer(Modifier.height(12.dp))
                RateSection(state = state, viewModel = viewModel)
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::onDescriptionChange,
                label = { Text("Popis") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            if (!state.settlement) {
                Spacer(Modifier.height(24.dp))
                CategorySection(state = state, viewModel = viewModel)

                Spacer(Modifier.height(24.dp))
                RepeatSection(state = state, viewModel = viewModel)
            }

            Spacer(Modifier.height(24.dp))

            PayerSection(state = state, viewModel = viewModel)

            Spacer(Modifier.height(24.dp))

            if (state.settlement) {
                SettlementReceiverSection(state = state, viewModel = viewModel)
            } else {
                SplitSection(state = state, viewModel = viewModel)
            }

            state.error?.let { error ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = { viewModel.save(onDone) },
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.settlement) "Zapsat vyrovnání" else "Uložit výdaj")
            }
        }
    }
}

/**
 * Opakování výdaje.
 *
 * První výdaj vzniká hned, pravidlo pak generuje další období. Proto je popiska
 * „a dál pravidelně" — jinak by nebylo jasné, jestli se dnešní výdaj počítá.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepeatSection(state: AddExpenseUiState, viewModel: AddExpenseViewModel) {
    Text("Opakovat", style = MaterialTheme.typography.titleSmall)
    Text(
        text = "Tenhle výdaj se uloží teď a dál se bude vytvářet automaticky.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    val options = listOf(
        RecurrenceFrequency.DAILY to "Denně",
        RecurrenceFrequency.WEEKLY to "Týdně",
        RecurrenceFrequency.MONTHLY to "Měsíčně",
        RecurrenceFrequency.YEARLY to "Ročně",
    )

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (frequency, label) ->
            FilterChip(
                selected = state.repeat == frequency,
                onClick = { viewModel.onRepeatChange(frequency) },
                label = { Text(label) },
            )
        }
    }
}

/** Kategorie je dobrovolná — proto chipy, ne povinný výběr. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategorySection(state: AddExpenseUiState, viewModel: AddExpenseViewModel) {
    Text("Kategorie", style = MaterialTheme.typography.titleSmall)
    Text(
        text = "Nepovinné. Slouží k přehledu, na co skupina utrácí.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExpenseCategory.entries.forEach { category ->
            FilterChip(
                selected = state.category == category,
                onClick = { viewModel.onCategoryChange(category) },
                label = { Text(category.label) },
                leadingIcon = {
                    Icon(
                        imageVector = category.icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PayerSection(state: AddExpenseUiState, viewModel: AddExpenseViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (state.settlement) "Kdo platí" else "Kdo zaplatil",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (!state.settlement) {
            Text("Víc plátců", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = state.multiplePayers,
                onCheckedChange = { viewModel.toggleMultiplePayers() },
            )
        }
    }

    if (state.multiplePayers && !state.settlement) {
        Text(
            text = "Zadej, kolik kdo vložil. Součet musí sedět na částku výdaje.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        state.members.forEach { member ->
            MemberValueRow(
                member = member,
                value = state.payerInputs[member.id].orEmpty(),
                onValueChange = { viewModel.onPayerAmountChange(member.id, it) },
                suffix = state.currency.code,
                keyboardType = KeyboardType.Decimal,
            )
        }
        state.payerProblem?.let { ProblemText(it) }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.members.forEach { member ->
                FilterChip(
                    selected = state.payerId == member.id,
                    onClick = { viewModel.onPayerChange(member.id) },
                    label = { Text(member.name) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettlementReceiverSection(state: AddExpenseUiState, viewModel: AddExpenseViewModel) {
    Text("Komu", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.members.forEach { member ->
            FilterChip(
                selected = member.id in state.participantIds,
                onClick = { viewModel.toggleParticipant(member.id) },
                label = { Text(member.name) },
            )
        }
    }
    state.splitProblem?.let { ProblemText(it) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SplitSection(state: AddExpenseUiState, viewModel: AddExpenseViewModel) {
    Text("Jak se dělí", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))

    val methods = listOf(
        SplitMethod.EQUAL to "Rovným dílem",
        SplitMethod.EXACT to "Přesně",
        SplitMethod.PERCENT to "Procenta",
        SplitMethod.SHARES to "Podíly",
        SplitMethod.ITEMS to "Položky",
    )

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        methods.forEachIndexed { index, (method, label) ->
            SegmentedButton(
                selected = state.splitMethod == method,
                onClick = { viewModel.onSplitMethodChange(method) },
                shape = SegmentedButtonDefaults.itemShape(index, methods.size),
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    when (state.splitMethod) {
        SplitMethod.EQUAL -> EqualSplitInputs(state, viewModel)
        SplitMethod.EXACT -> state.members.forEach { member ->
            MemberValueRow(
                member = member,
                value = state.exactInputs[member.id].orEmpty(),
                onValueChange = { viewModel.onExactChange(member.id, it) },
                suffix = state.currency.code,
                keyboardType = KeyboardType.Decimal,
            )
        }

        SplitMethod.PERCENT -> state.members.forEach { member ->
            MemberValueRow(
                member = member,
                value = state.percentInputs[member.id].orEmpty(),
                onValueChange = { viewModel.onPercentChange(member.id, it) },
                suffix = "%",
                keyboardType = KeyboardType.Decimal,
            )
        }

        SplitMethod.SHARES -> state.members.forEach { member ->
            MemberValueRow(
                member = member,
                value = state.weightInputs[member.id].orEmpty(),
                onValueChange = { viewModel.onWeightChange(member.id, it) },
                suffix = "×",
                keyboardType = KeyboardType.Number,
            )
        }

        SplitMethod.ITEMS -> BillItemsInputs(state, viewModel)
    }

    state.splitProblem?.let { ProblemText(it) }

    if (state.previewSplits.isNotEmpty() && state.splitMethod != SplitMethod.EXACT) {
        SplitPreview(state)
    }
}

/**
 * Zadání účtu po položkách.
 *
 * Celková částka se tady nezadává — dopočítá se ze součtu položek, daně a spropitného.
 * Dvě čísla zvlášť by se mohla rozejít a účtenku uživatel stejně opisuje po položkách.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BillItemsInputs(state: AddExpenseUiState, viewModel: AddExpenseViewModel) {
    state.items.forEach { item ->
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = item.name,
                    onValueChange = { viewModel.onItemNameChange(item.key, it) },
                    label = { Text("Položka") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = item.amountText,
                    onValueChange = { viewModel.onItemAmountChange(item.key, it) },
                    label = { Text("Cena") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.width(140.dp),
                )
                IconButton(onClick = { viewModel.removeBillItem(item.key) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Odebrat položku ${item.name}",
                    )
                }
            }

            Text(
                text = "Kdo si ji dal",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.members.forEach { member ->
                    FilterChip(
                        selected = member.id in item.consumers,
                        onClick = { viewModel.toggleItemConsumer(item.key, member.id) },
                        label = { Text(member.name) },
                    )
                }
            }
        }
    }

    TextButton(onClick = viewModel::addBillItem) { Text("+ Přidat položku") }

    Spacer(Modifier.height(8.dp))

    Row(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.taxText,
            onValueChange = viewModel::onTaxChange,
            label = { Text("Daň") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = state.tipText,
            onValueChange = viewModel::onTipChange,
            label = { Text("Spropitné") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.weight(1f),
        )
    }

    Text(
        text = "Daň i spropitné se rozpočítají poměrně podle toho, kdo kolik utratil.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EqualSplitInputs(state: AddExpenseUiState, viewModel: AddExpenseViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Za koho", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = viewModel::selectAllParticipants) { Text("Všichni") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.members.forEach { member ->
            FilterChip(
                selected = member.id in state.participantIds,
                onClick = { viewModel.toggleParticipant(member.id) },
                label = { Text(member.name) },
            )
        }
    }
}

@Composable
private fun MemberValueRow(
    member: Member,
    value: String,
    onValueChange: (String) -> Unit,
    suffix: String,
    keyboardType: KeyboardType,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = member.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            suffix = { Text(suffix) },
            placeholder = { Text("—") },
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.width(160.dp),
        )
    }
}

@Composable
private fun SplitPreview(state: AddExpenseUiState) {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))

    Text("Vyjde na", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))

    state.previewSplits.forEach { split ->
        val name = state.members.firstOrNull { it.id == split.memberId }?.name.orEmpty()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = formatMoney(split.amount, state.currency),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * Výběr měny výdaje.
 *
 * Nabídka začíná měnou skupiny a nejběžnějšími měnami; zbytek světa je pod nimi,
 * aby se v seznamu 180 kódů nemusel nikdo prokousávat k euru.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurrencyPicker(selected: CurrencyCode, onSelect: (CurrencyCode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { expanded = true }) {
            Text(selected.code)
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Změnit měnu")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            currencyOptions(selected).forEach { code ->
                DropdownMenuItem(
                    text = { Text("${code.code}${currencyName(code)?.let { " — $it" }.orEmpty()}") },
                    onClick = {
                        onSelect(code)
                        expanded = false
                    },
                )
            }
        }
    }
}

private val COMMON_CURRENCIES = listOf("CZK", "EUR", "USD", "GBP", "PLN", "CHF", "HUF")
    .map(::CurrencyCode)

private fun currencyOptions(selected: CurrencyCode): List<CurrencyCode> {
    val preferred = (listOf(selected) + COMMON_CURRENCIES).distinct()
    val rest = runCatching {
        java.util.Currency.getAvailableCurrencies()
            .map { CurrencyCode(it.currencyCode) }
            .filterNot { it in preferred }
            .sortedBy { it.code }
    }.getOrDefault(emptyList())
    return preferred + rest
}

private fun currencyName(code: CurrencyCode): String? = runCatching {
    java.util.Currency.getInstance(code.code).getDisplayName(java.util.Locale.getDefault())
}.getOrNull()

/** Kurz na měnu skupiny — načtený, ale vždy přepsatelný. */
@Composable
private fun RateSection(state: AddExpenseUiState, viewModel: AddExpenseViewModel) {
    OutlinedTextField(
        value = state.rateText,
        onValueChange = viewModel::onRateChange,
        label = { Text("Kurz 1 ${state.currency.code} = ? ${state.groupCurrency.code}") },
        singleLine = true,
        isError = state.rateInvalid,
        trailingIcon = if (state.loadingRate) {
            { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
        } else {
            null
        },
        supportingText = {
            val converted = state.convertedAmount
            when {
                state.rateProblem != null -> Text(state.rateProblem.orEmpty())
                state.rateFromCache -> Text("Poslední známý kurz — bez připojení nešel ověřit")
                converted != null -> Text("Do sald se započítá ${formatMoney(converted, state.groupCurrency)}")
                else -> Text("Kurz platný teď se uloží k výdaji a už se nezmění")
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProblemText(message: String) {
    Spacer(Modifier.height(8.dp))
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}
