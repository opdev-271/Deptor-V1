package dev.op.deptor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.op.deptor.domain.invite.InviteLink
import dev.op.deptor.domain.repository.AuthRepository
import dev.op.deptor.domain.repository.AuthUser
import dev.op.deptor.ui.auth.AuthScreen
import dev.op.deptor.ui.common.LoadingState
import dev.op.deptor.ui.navigation.DeptorNavHost
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import javax.inject.Inject

sealed interface SessionState {
    data object Loading : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val user: AuthUser) : SessionState
}

@HiltViewModel
class SessionViewModel @Inject constructor(
    auth: AuthRepository,
) : ViewModel() {

    val session: StateFlow<SessionState> = auth.currentUser
        .map { user -> user?.let(SessionState::SignedIn) ?: SessionState.SignedOut }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            // Než dorazí první stav z Firebase, nesmíme ukázat přihlašovací obrazovku —
            // u už přihlášeného uživatele by problikla a vypadalo by to jako odhlášení.
            initialValue = SessionState.Loading,
        )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun DeptorApp(
    pendingLink: String? = null,
    onLinkHandled: () -> Unit = {},
    viewModel: SessionViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()

    when (session) {
        SessionState.Loading -> LoadingState()

        // Pozvánka se tu **nezahazuje**: uživatel se nejdřív přihlásí a odkaz se
        // uplatní až potom, jakmile navigační graf existuje.
        SessionState.SignedOut -> AuthScreen()

        is SessionState.SignedIn -> DeptorNavHost(
            pendingInvite = InviteLink.parse(pendingLink),
            onInviteHandled = onLinkHandled,
        )
    }
}
