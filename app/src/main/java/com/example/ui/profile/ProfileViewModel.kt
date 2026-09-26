package com.example.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.UserEntity
import com.example.data.repository.UserRepository
import com.example.ui.theme.EasappColorTheme
import com.example.util.ThemeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    val selectedTheme: StateFlow<EasappColorTheme> = themeManager.selectedTheme
    val isCloudConnected: StateFlow<Boolean> = userRepository.firestoreSyncManager?.isCloudConnected
        ?: MutableStateFlow(false)
    val isRelayConnected: StateFlow<Boolean> = userRepository.relayEngine?.isConnected
        ?: MutableStateFlow(false)

    private fun normalizeSeed(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank() || trimmed.startsWith("boy_", ignoreCase = true) || trimmed.startsWith("girl_", ignoreCase = true)) {
            return "BRUTAL_1"
        }
        return trimmed
    }

    private val _uiState = MutableStateFlow(
        userRepository.currentUser.value?.let { user ->
            ProfileUiState(
                displayName = user.displayName,
                statusMessage = user.statusMessage,
                selectedAvatar = normalizeSeed(user.avatarSeed)
            )
        } ?: ProfileUiState()
    )
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            userRepository.currentUser.collect { user ->
                if (user != null) {
                    _uiState.update { current ->
                        current.copy(
                            displayName = user.displayName,
                            statusMessage = user.statusMessage,
                            selectedAvatar = normalizeSeed(user.avatarSeed)
                        )
                    }
                }
            }
        }
    }

    fun toggleDarkMode() {
        themeManager.toggleDarkMode()
    }

    fun selectColorTheme(theme: EasappColorTheme) {
        themeManager.setColorTheme(theme)
    }

    fun onDisplayNameChange(value: String) {
        _uiState.update { it.copy(displayName = value, saveSuccess = false) }
    }

    fun onStatusMessageChange(value: String) {
        _uiState.update { it.copy(statusMessage = value, saveSuccess = false) }
    }

    fun onAvatarSelect(avatarSeed: String) {
        _uiState.update { it.copy(selectedAvatar = avatarSeed, saveSuccess = false) }
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
