package dev.op.deptor.ui.recurrence

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.recurrence.Recurrence
import dev.op.deptor.domain.recurrence.RecurrenceId
import dev.op.deptor.domain.repository.GroupRepository
import dev.op.deptor.domain.repository.RecurrenceRepository
import dev.op.deptor.ui.navigation.Routes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dev.op.deptor.domain.model.CurrencyCode
import javax.inject.Inject

data class RecurrencesUiState(
    val recurrences: List<Recurrence> = emptyList(),
    val currency: CurrencyCode = CurrencyCode.CZK,
    val loading: Boolean = true,
)

@HiltViewModel
class RecurrencesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    groups: GroupRepository,
    private val recurrences: RecurrenceRepository,
) : ViewModel() {

    private val groupId = GroupId(checkNotNull(savedStateHandle.get<String>(Routes.GROUP_ID)))

    val state: StateFlow<RecurrencesUiState> = combine(
        groups.observeGroup(groupId),
        recurrences.observeRecurrences(groupId),
    ) { group, list ->
        RecurrencesUiState(
            recurrences = list.sortedBy { it.rule.startDate },
            currency = group?.currency ?: CurrencyCode.CZK,
            loading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = RecurrencesUiState(),
    )

    /** Zruší pravidlo. Už vytvořené výdaje zůstávají — jsou to skutečné platby. */
    fun stopRecurrence(id: RecurrenceId) {
        viewModelScope.launch { recurrences.deleteRecurrence(groupId, id) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
