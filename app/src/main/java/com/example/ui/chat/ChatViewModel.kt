package com.example.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.dao.UserDao
import com.example.data.model.MessageEntity
import com.example.data.model.MessageStatus
import com.example.data.model.UserEntity
import com.example.data.realtime.RealtimeManager
import com.example.data.repository.ChatRepository
import com.example.data.repository.UserRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val textInput: String = "",
    val selectedAttachmentUri: String? = null,
    val selectedAttachmentType: String? = null,
    val selectedAttachmentSize: Long? = null,
    val selectedAttachmentName: String? = null,
    val isEmojiPickerOpen: Boolean = false,
    val previewImageUri: String? = null // for full screen viewer modal
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    val conversationId: String,
    val otherUserId: String,
    private val chatRepository: ChatRepository,
    private val userRepository: UserRepository,
    private val userDao: UserDao
) : ViewModel() {

    val currentUserId: StateFlow<String?> = userRepository.currentUserId
    val currentUser: StateFlow<UserEntity?> = userRepository.currentUser

    val otherUser: StateFlow<UserEntity?> = userDao.getUserById(otherUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isProfileLoading = MutableStateFlow(false)
    val isProfileLoading: StateFlow<Boolean> = _isProfileLoading.asStateFlow()

    private val _isProfileUnavailable = MutableStateFlow(false)
    val isProfileUnavailable: StateFlow<Boolean> = _isProfileUnavailable.asStateFlow()

    val canSwitchToOtherUser: StateFlow<Boolean> = userRepository.getLocalAuthenticatedSessionsFlow()
        .combine(MutableStateFlow(otherUserId)) { localSessions, peerId ->
            localSessions.any { it.id == peerId }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val messages: StateFlow<List<MessageEntity>> = userRepository.currentUserId
        .flatMapLatest { uid ->
            chatRepository.getMessagesForConversation(conversationId, uid)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isOtherUserTyping: StateFlow<Boolean> = RealtimeManager.typingUsers
        .combine(MutableStateFlow(conversationId)) { typingMap, convId ->
            val set = typingMap[convId] ?: emptySet()
            set.contains(otherUserId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var typingDebounceJob: Job? = null
    private var profileFetchJob: Job? = null
    @Volatile
    private var isScreenActive: Boolean = false

    init {
        onEnterScreen()
        loadRecipientProfile()

        // Automatically retry profile fetch when network reconnects if previously failed
        viewModelScope.launch {
            RealtimeManager.isNetworkConnected.collect { connected ->
                if (connected && (_isProfileUnavailable.value || otherUser.value == null)) {
                    loadRecipientProfile()
                }
            }
        }

        // Automatically mark incoming messages as READ ONLY while the conversation is actively open in the foreground
        viewModelScope.launch {
            messages.collect { list ->
                if (!isScreenActive || !RealtimeManager.isAppInForeground.value) return@collect
                val myId = currentUserId.value ?: return@collect
                val hasUnreadIncoming = list.any {
                    it.recipientId == myId && it.status != MessageStatus.READ.name
                }
                if (hasUnreadIncoming) {
                    chatRepository.markConversationAsRead(conversationId, myId)
                }
            }
        }
    }

    fun loadRecipientProfile() {
        if (otherUserId.isBlank()) return
        profileFetchJob?.cancel()
        profileFetchJob = viewModelScope.launch {
            val local = userDao.getUserByIdDirect(otherUserId)
            if (local == null || local.displayName.isBlank()) {
                _isProfileLoading.value = true
            }
            val result = userRepository.fetchAndCacheUserById(otherUserId)
            _isProfileLoading.value = false
            val updatedLocal = userDao.getUserByIdDirect(otherUserId)
            if (result.isFailure && updatedLocal == null) {
                _isProfileUnavailable.value = true
            } else {
                _isProfileUnavailable.value = false
            }
        }
    }

    fun retryFetchProfile() {
        loadRecipientProfile()
    }

    fun onEnterScreen() {
        isScreenActive = true
        val myId = currentUserId.value ?: return
        RealtimeManager.setUserActiveConversation(myId, conversationId)
        chatRepository.enterConversationScreen(conversationId, myId)
        loadRecipientProfile()
        if (RealtimeManager.isAppInForeground.value) {
            viewModelScope.launch {
                chatRepository.markConversationAsRead(conversationId, myId)
            }
        }
    }

    fun onExitScreen() {
        isScreenActive = false
        typingDebounceJob?.cancel()
        typingDebounceJob = null
        val myId = currentUserId.value ?: return
        RealtimeManager.setUserActiveConversation(myId, null)
        chatRepository.exitConversationScreen(conversationId)
        chatRepository.stopTyping(conversationId, myId, otherUserId)
    }

    fun onTextInputChange(text: String) {
        _uiState.update { it.copy(textInput = text) }
        val myId = currentUserId.value ?: return
        typingDebounceJob?.cancel()
        if (text.isNotBlank()) {
            chatRepository.notifyTyping(conversationId, myId, otherUserId)
            typingDebounceJob = viewModelScope.launch {
                delay(2200L)
                chatRepository.stopTyping(conversationId, myId, otherUserId)
            }
        } else {
            chatRepository.stopTyping(conversationId, myId, otherUserId)
        }
    }

    fun appendEmoji(emoji: String) {
        val currentText = _uiState.value.textInput
        onTextInputChange(currentText + emoji)
    }

    fun toggleEmojiPicker() {
        _uiState.update { it.copy(isEmojiPickerOpen = !it.isEmojiPickerOpen) }
    }

    fun setAttachment(uri: String?, name: String?, size: Long?, type: String? = "image") {
        _uiState.update {
            it.copy(
                selectedAttachmentUri = uri,
                selectedAttachmentName = name,
                selectedAttachmentSize = size,
                selectedAttachmentType = type
            )
        }
    }

    fun clearAttachment() {
        _uiState.update {
            it.copy(
                selectedAttachmentUri = null,
                selectedAttachmentName = null,
                selectedAttachmentSize = null,
                selectedAttachmentType = null
            )
        }
    }

    fun setPreviewImage(uri: String?) {
        _uiState.update { it.copy(previewImageUri = uri) }
    }

    fun sendMessage() {
        val myId = currentUserId.value ?: return
        val state = _uiState.value
        val text = state.textInput.trim()
        val attachmentUri = state.selectedAttachmentUri
        val attachmentType = state.selectedAttachmentType
        val attachmentSize = state.selectedAttachmentSize
        val attachmentName = state.selectedAttachmentName
        val hasAttachment = !attachmentUri.isNullOrBlank()

        if (text.isBlank() && !hasAttachment) return

        typingDebounceJob?.cancel()
        typingDebounceJob = null

        // Clear composer state immediately using functional update so rapid consecutive messages never collide
        _uiState.update {
            it.copy(
                textInput = "",
                selectedAttachmentUri = null,
                selectedAttachmentName = null,
                selectedAttachmentSize = null,
                selectedAttachmentType = null,
                isEmojiPickerOpen = false
            )
        }

        viewModelScope.launch {
            chatRepository.sendMessage(
                conversationId = conversationId,
                senderId = myId,
                recipientId = otherUserId,
                content = text,
                attachmentUri = attachmentUri,
                attachmentType = attachmentType,
                attachmentSize = attachmentSize,
                attachmentName = attachmentName
            )
        }
    }

    fun retryMessage(messageId: String) {
        viewModelScope.launch {
            chatRepository.retryMessage(messageId)
        }
    }

    fun switchUserToOther(onSwitched: (newConversationId: String, newOtherUserId: String) -> Unit) {
        val myId = currentUserId.value ?: return
        viewModelScope.launch {
            // Only switch if the other account is actually authenticated on this device
            val result = userRepository.switchUser(otherUserId)
            if (result.isSuccess) {
                onSwitched(conversationId, myId)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        onExitScreen()
    }
}
