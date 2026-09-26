package com.example.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.UserEntity
import com.example.data.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AuthUiState(
    val isRegisterMode: Boolean = false,
    val username: String = "",
    val displayName: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val registeredSuccess: Boolean = false
)

class AuthViewModel(
    private val userRepository: UserRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    val registeredUsers: StateFlow<List<UserEntity>> = userRepository.getLocalAuthenticatedSessionsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun toggleMode() {
        _uiState.value = _uiState.value.copy(
            isRegisterMode = !_uiState.value.isRegisterMode,
            errorMessage = null
        )
    }

    fun onUsernameChange(value: String) {
        _uiState.value = _uiState.value.copy(username = value, errorMessage = null)
    }

    fun onDisplayNameChange(value: String) {
        _uiState.value = _uiState.value.copy(displayName = value, errorMessage = null)
    }

    fun onPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(password = value, errorMessage = null)
    }

    fun onConfirmPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(confirmPassword = value, errorMessage = null)
    }

    fun quickSelectUser(user: UserEntity) {
        _uiState.value = _uiState.value.copy(
            isRegisterMode = false,
            username = user.username,
            password = "",
            errorMessage = null
        )
    }

    fun submit(onSuccess: () -> Unit) {
        val state = _uiState.value
        _uiState.value = state.copy(isLoading = true, errorMessage = null)

        viewModelScope.launch {
            if (state.isRegisterMode) {
                if (state.password != state.confirmPassword) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Passwords do not match"
                    )
                    return@launch
                }

                val result = userRepository.registerUser(
                    rawUsername = state.username,
                    displayName = state.displayName,
                    rawPassword = state.password
                )

                if (result.isSuccess) {
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = null)
                    onSuccess()
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Registration failed"
                    )
                }
            } else {
                val result = userRepository.loginUser(
                    rawUsername = state.username,
                    rawPassword = state.password
                )

                if (result.isSuccess) {
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = null)
                    onSuccess()
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Login failed"
                    )
                }
            }
        }
    }
}
