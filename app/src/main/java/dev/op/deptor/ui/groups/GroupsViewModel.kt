package dev.op.deptor.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.Group
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.repository.AuthRepository
import dev.op.deptor.domain.repository.GroupRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GroupsUiState(
    val groups: List<Group>? = null,
    val error: String? = null,
    val creating: Boolean = false,
) {
    val loading: Boolean get() = groups == null
}

@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val groups: GroupRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val errors = MutableStateFlow<String?>(null)
    private val creating = MutableStateFlow(false)

    private val loaded: StateFlow<List<Group>?> = groups.observeGroups()
        .map { list -> list.sortedBy { it.name.lowercase() } }
        .catch { error ->
            errors.value = "Skupiny se nepodařilo načíst: ${error.message}"
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _state = MutableStateFlow(GroupsUiState())
    val state: StateFlow<GroupsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            loaded.collect { list -> _state.update { it.copy(groups = list) } }
        }
        viewModelScope.launch {
            errors.collect { error -> _state.update { it.copy(error = error) } }
        }
        viewModelScope.launch {
            creating.collect { busy -> _state.update { it.copy(creating = busy) } }
        }
    }

    fun createGroup(name: String, currency: CurrencyCode, onCreated: (GroupId) -> Unit) {
        if (name.isBlank() || creating.value) return

        viewModelScope.launch {
            creating.value = true

            // Jméno zakladatele: u anonymního účtu žádné není, takže se doplní zástupné —
            // bez člena by skupina neměla komu nic účtovat.
            val creatorName = auth.currentUserOrNull()?.displayName?.takeIf { it.isNotBlank() } ?: "Já"

            groups.createGroup(name.trim(), currency, creatorName).fold(
                onSuccess = onCreated,
                onFailure = { errors.value = "Skupinu se nepodařilo vytvořit: ${it.message}" },
            )
            creating.value = false
        }
    }

    fun dismissError() {
        errors.value = null
    }

    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
