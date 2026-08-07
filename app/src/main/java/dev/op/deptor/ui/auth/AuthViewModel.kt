package dev.op.deptor.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.op.deptor.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AuthMode { SIGN_IN, REGISTER }

data class AuthUiState(
    val mode: AuthMode = AuthMode.SIGN_IN,
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean
        get() = !busy &&
            email.contains('@') &&
            password.length >= MIN_PASSWORD_LENGTH &&
            (mode == AuthMode.SIGN_IN || name.isNotBlank())

    companion object {
        const val MIN_PASSWORD_LENGTH = 6
    }
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun onNameChange(value: String) = _state.update { it.copy(name = value, error = null) }

    fun onEmailChange(value: String) = _state.update { it.copy(email = value.trim(), error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun toggleMode() = _state.update {
        it.copy(
            mode = if (it.mode == AuthMode.SIGN_IN) AuthMode.REGISTER else AuthMode.SIGN_IN,
            error = null,
        )
    }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return

        run(
            when (current.mode) {
                AuthMode.SIGN_IN -> suspend { auth.signInWithEmail(current.email, current.password) }
                AuthMode.REGISTER -> suspend {
                    auth.registerWithEmail(current.email, current.password, current.name.trim())
                }
            },
        )
    }

    /**
     * Vstup bez účtu.
     *
     * Anonymní přihlášení je celý „lokální režim" ze zadání — uživatel může appku
     * používat hned a účet si založit později, aniž by přišel o data.
     */
    fun continueWithoutAccount() = run { auth.signInAnonymously() }

    private fun run(action: suspend () -> Result<*>) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val result = action()
            _state.update { state ->
                state.copy(
                    busy = false,
                    error = result.exceptionOrNull()?.let(::readableMessage),
                )
            }
        }
    }

    /**
     * Firebase vrací technické hlášky typu „The password is invalid or the user does not
     * have a password". Uživateli je nepředkládáme doslova.
     */
    private fun readableMessage(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("password", ignoreCase = true) ||
                raw.contains("credential", ignoreCase = true) ->
                "Nesprávný e-mail nebo heslo."

            raw.contains("already in use", ignoreCase = true) ->
                "Na tenhle e-mail už účet existuje. Zkus se přihlásit."

            raw.contains("network", ignoreCase = true) ->
                "Nepodařilo se spojit se serverem. Zkontroluj připojení."

            raw.contains("badly formatted", ignoreCase = true) ->
                "E-mail nevypadá platně."

            else -> "Přihlášení se nepovedlo. Zkus to prosím znovu."
        }
    }
}
