package dev.op.deptor.ui.invite

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.op.deptor.domain.invite.Invite
import dev.op.deptor.domain.invite.InviteLink
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.repository.AuthRepository
import dev.op.deptor.domain.repository.GroupRepository
import dev.op.deptor.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class JoinGroupUiState(
    val invite: Invite? = null,
    val name: String = "",
    val joining: Boolean = false,
    val error: String? = null,
) {
    val linkInvalid: Boolean get() = invite == null
    val canJoin: Boolean get() = invite != null && name.isNotBlank() && !joining
}

@HiltViewModel
class JoinGroupViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groups: GroupRepository,
    auth: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        JoinGroupUiState(
            invite = parseInvite(savedStateHandle),
            // Jméno se předvyplní z účtu, ale zůstává editovatelné — ve skupině může
            // chtít člověk vystupovat jinak než v profilu.
            name = auth.currentUserOrNull()?.displayName.orEmpty(),
        ),
    )
    val state: StateFlow<JoinGroupUiState> = _state.asStateFlow()

    fun onNameChange(value: String) = _state.update { it.copy(name = value, error = null) }

    fun join(onJoined: (GroupId) -> Unit) {
        val current = _state.value
        val invite = current.invite ?: return
        if (!current.canJoin) return

        viewModelScope.launch {
            _state.update { it.copy(joining = true, error = null) }

            groups.joinGroup(invite, current.name.trim()).fold(
                onSuccess = {
                    _state.update { it.copy(joining = false) }
                    onJoined(invite.groupId)
                },
                onFailure = { error ->
                    _state.update { it.copy(joining = false, error = readableMessage(error)) }
                },
            )
        }
    }

    /**
     * Špatný kód se pozná až tím, že ho odmítne server. Uživateli ale nemá smysl
     * ukazovat „PERMISSION_DENIED" — z jeho pohledu je pozvánka prostě neplatná.
     */
    private fun readableMessage(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("PERMISSION_DENIED", ignoreCase = true) ->
                "Pozvánka neplatí. Mohla být zrušená, nebo je odkaz neúplný."

            raw.contains("NOT_FOUND", ignoreCase = true) ->
                "Skupina už neexistuje."

            else -> "Do skupiny se nepodařilo vstoupit. Zkontroluj připojení."
        }
    }

    private companion object {
        fun parseInvite(savedStateHandle: SavedStateHandle): Invite? {
            val groupId = savedStateHandle.get<String>(Routes.GROUP_ID)
            val code = savedStateHandle.get<String>(Routes.INVITE_CODE)
            if (groupId == null || code == null) return null

            return InviteLink.parse("${InviteLink.SCHEME}://${InviteLink.HOST}/$groupId/$code")
        }
    }
}
