package com.example.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.UserEntity
import com.example.data.repository.UserRepository
import com.example.util.ThemeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfileUiState(
    val displayName: String = "",
    val statusMessage: String = "",
    val selectedAvatar: String = "BRUTAL_1",
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val errorMessage: String? = null
)

class ProfileViewModel(
    private val userRepository: UserRepository,
    private val themeManager: ThemeManager
) : ViewModel() {

    val currentUser: StateFlow<UserEntity?> = userRepository.currentUser
    val isDarkMode: StateFlow<Boolean> = themeManager.isDarkMode
    val isCloudConnected: StateFlow<Boolean> = userRepository.firestoreSyncManager?.isCloudConnected
        ?: MutableStateFlow(false)
    val isRelayConnected: StateFlow<Boolean> = userRepository.relayEngine?.isConnected
        ?: MutableStateFlow(false)

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            userRepository.currentUser.collect { user ->
                if (user != null) {
                    _uiState.value = _uiState.value.copy(
                        displayName = user.displayName,
                        statusMessage = user.statusMessage,
                        selectedAvatar = user.avatarSeed.ifBlank { "BRUTAL_1" }
                    )
                }
            }
        }
    }

    fun toggleDarkMode() {
        themeManager.toggleDarkMode()
    }

    fun onDisplayNameChange(value: String) {
        _uiState.value = _uiState.value.copy(displayName = value, saveSuccess = false)
    }

    fun onStatusMessageChange(value: String) {
        _uiState.value = _uiState.value.copy(statusMessage = value, saveSuccess = false)
    }

    fun onAvatarSelect(seed: String) {
        _uiState.value = _uiState.value.copy(selectedAvatar = seed, saveSuccess = false)
    }

    fun saveChanges() {
        val state = _uiState.value
        _uiState.value = state.copy(isSaving = true, errorMessage = null, saveSuccess = false)

        viewModelScope.launch {
            val result = userRepository.updateProfile(
                displayName = state.displayName,
                statusMessage = state.statusMessage,
                avatarSeed = state.selectedAvatar
            )

            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saveSuccess = true,
                    errorMessage = null
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saveSuccess = false,
                    errorMessage = result.exceptionOrNull()?.message ?: "Failed to save profile"
                )
            }
        }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            userRepository.logout()
            onLoggedOut()
        }
    }
}
