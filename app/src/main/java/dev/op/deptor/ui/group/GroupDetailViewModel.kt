package dev.op.deptor.ui.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.op.deptor.domain.balance.Balance
import dev.op.deptor.domain.balance.Transfer
import dev.op.deptor.domain.balance.computeBalances
import dev.op.deptor.domain.balance.simplify
import dev.op.deptor.domain.model.Group
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.model.Member
import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Transaction
import dev.op.deptor.domain.report.CategoryTotal
import dev.op.deptor.domain.report.categoryTotals
import dev.op.deptor.domain.recurrence.generateDueTransactions
import dev.op.deptor.domain.repository.GroupRepository
import dev.op.deptor.domain.repository.RecurrenceRepository
import dev.op.deptor.domain.repository.RejectedDocument
import dev.op.deptor.domain.repository.TransactionRepository
import dev.op.deptor.ui.navigation.Routes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MemberBalance(
    val member: Member,
    val balance: Balance,
)

/** Návrh platby s doplněnými jmény, aby UI nemuselo dohledávat členy. */
data class SuggestedTransfer(
    val transfer: Transfer,
    val fromName: String,
    val toName: String,
)

data class GroupDetailUiState(
    val group: Group? = null,
    val transactions: List<Transaction> = emptyList(),
    val balances: List<MemberBalance> = emptyList(),
    val suggestedTransfers: List<SuggestedTransfer> = emptyList(),
    val rejected: List<RejectedDocument> = emptyList(),
    val categories: List<CategoryTotal> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
) {
    fun memberName(id: MemberId): String =
        group?.member(id)?.name ?: "Bývalý člen"
}

@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    groups: GroupRepository,
    private val transactions: TransactionRepository,
    private val recurrences: RecurrenceRepository,
) : ViewModel() {

    val groupId = GroupId(checkNotNull(savedStateHandle.get<String>(Routes.GROUP_ID)))

    init {
        // Dohánění se spouští při otevření skupiny. Generuje se na zařízeních členů,
        // ne na serveru, takže se pravidlo projeví, až se někdo podívá — zameškaná
        // období se ale dopočítají zpětně, takže se nic neztratí.
        viewModelScope.launch {
            runCatching { generateDueTransactions(groupId, recurrences, transactions) }
        }
    }

    val state: StateFlow<GroupDetailUiState> = combine(
        groups.observeGroup(groupId),
        transactions.observeTransactions(groupId),
    ) { group, load ->
        if (group == null) {
            return@combine GroupDetailUiState(loading = false, error = "Skupina už neexistuje.")
        }

        // Salda se počítají i pro členy, kteří už ve skupině nejsou, ale mají transakce —
        // jinak by jejich dluh z bilance zmizel, aniž by byl vyrovnaný.
        val memberIds = (group.members.map { it.id } +
            load.transactions.flatMap { it.involvedMembers }).distinct()

        val balances = computeBalances(load.transactions, memberIds, group.currency)

        val suggestions = runCatching { simplify(balances) }.getOrDefault(emptyList())

        GroupDetailUiState(
            group = group,
            transactions = load.transactions,
            balances = balances
                .map { balance ->
                    MemberBalance(
                        member = group.member(balance.memberId)
                            ?: Member(id = balance.memberId, name = "Bývalý člen"),
                        balance = balance,
                    )
                }
                .sortedByDescending { it.balance.amount.minor },
            suggestedTransfers = suggestions.map { transfer ->
                SuggestedTransfer(
                    transfer = transfer,
                    fromName = group.member(transfer.from)?.name ?: "Bývalý člen",
                    toName = group.member(transfer.to)?.name ?: "Bývalý člen",
                )
            },
            rejected = load.rejected,
            categories = categoryTotals(load.transactions, group.currency),
            loading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = GroupDetailUiState(),
    )

    fun deleteTransaction(transaction: Transaction) {
        viewModelScope.launch {
            transactions.deleteTransaction(groupId, transaction.id)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
