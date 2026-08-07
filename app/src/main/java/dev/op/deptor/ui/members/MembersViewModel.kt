package dev.op.deptor.ui.members

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.model.Member
import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.repository.GroupRepository
import dev.op.deptor.ui.navigation.Routes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MembersUiState(
    val members: List<Member> = emptyList(),
)

@HiltViewModel
class MembersViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groups: GroupRepository,
) : ViewModel() {

    private val groupId = GroupId(checkNotNull(savedStateHandle.get<String>(Routes.GROUP_ID)))

    val state: StateFlow<MembersUiState> = groups.observeGroup(groupId)
        .map { group -> MembersUiState(members = group?.members.orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MembersUiState())

    fun addMember(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { groups.addMember(groupId, name.trim()) }
    }

    fun removeMember(memberId: MemberId) {
        viewModelScope.launch { groups.removeMember(groupId, memberId) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
