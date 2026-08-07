package dev.op.deptor.ui.member

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.op.deptor.domain.balance.Balance
import dev.op.deptor.domain.balance.computeBalances
import dev.op.deptor.domain.balance.computePairwiseDebts
import dev.op.deptor.domain.balance.involving
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import dev.op.deptor.domain.model.Transaction
import dev.op.deptor.domain.repository.GroupRepository
import dev.op.deptor.domain.repository.TransactionRepository
import dev.op.deptor.ui.navigation.Routes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import dev.op.deptor.domain.balance.involving as transactionsInvolving

/** Dluh vůči konkrétnímu člověku, už z pohledu prohlíženého člena. */
data class MemberDebt(
    val counterpartName: String,
    val amount: Money,
    /** `true` = prohlížený člen dluží, `false` = dluží jemu. */
    val owedByMember: Boolean,
)

data class MemberDetailUiState(
    val memberName: String = "",
    val currency: CurrencyCode = CurrencyCode.CZK,
    val balance: Balance? = null,
    val debts: List<MemberDebt> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
) {
    /** Dluhy člena vůči ostatním. */
    val owes: List<MemberDebt> get() = debts.filter { it.owedByMember }

    /** Dluhy ostatních vůči členovi. */
    val owedToMember: List<MemberDebt> get() = debts.filterNot { it.owedByMember }
}

@HiltViewModel
class MemberDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    groups: GroupRepository,
    transactions: TransactionRepository,
) : ViewModel() {

    private val groupId = GroupId(checkNotNull(savedStateHandle.get<String>(Routes.GROUP_ID)))
    private val memberId = MemberId(checkNotNull(savedStateHandle.get<String>(Routes.MEMBER_ID)))

    val state: StateFlow<MemberDetailUiState> = combine(
        groups.observeGroup(groupId),
        transactions.observeTransactions(groupId),
    ) { group, load ->
        if (group == null) {
            return@combine MemberDetailUiState(loading = false, error = "Skupina už neexistuje.")
        }

        // Odebraný člen s nevyrovnaným saldem se pořád musí dát otevřít, jinak by
        // jeho dluh nebylo kde dohledat.
        val name = group.member(memberId)?.name ?: "Bývalý člen"

        val memberIds = (group.members.map { it.id } +
            load.transactions.flatMap { it.involvedMembers }).distinct()

        val balance = computeBalances(load.transactions, memberIds, group.currency)
            .firstOrNull { it.memberId == memberId }

        val debts = computePairwiseDebts(load.transactions, group.currency)
            .involving(memberId)
            .map { debt ->
                val counterpart = if (debt.from == memberId) debt.to else debt.from
                MemberDebt(
                    counterpartName = group.member(counterpart)?.name ?: "Bývalý člen",
                    amount = debt.amount,
                    owedByMember = debt.from == memberId,
                )
            }
            .sortedByDescending { it.amount.minor }

        MemberDetailUiState(
            memberName = name,
            currency = group.currency,
            balance = balance,
            debts = debts,
            transactions = load.transactions.transactionsInvolving(memberId),
            loading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = MemberDetailUiState(),
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
