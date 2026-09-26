package com.example.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.dao.UserDao
import com.example.data.model.MessageEntity
import com.example.data.model.UserEntity
import com.example.data.realtime.RealtimeManager
import com.example.data.repository.ChatRepository
import com.example.data.repository.UserRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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

    val messages: StateFlow<List<MessageEntity>> = chatRepository.getMessagesForConversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isOtherUserTyping: StateFlow<Boolean> = RealtimeManager.typingUsers
        .combine(MutableStateFlow(conversationId)) { typingMap, convId ->
            val set = typingMap[convId] ?: emptySet()
            set.contains(otherUserId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var typingDebounceJob: Job? = null

    init {
        onEnterScreen()
    }

    fun onEnterScreen() {
        val myId = currentUserId.value ?: return
        RealtimeManager.setUserActiveConversation(myId, conversationId)
        chatRepository.enterConversationScreen(conversationId, myId)
        viewModelScope.launch {
            chatRepository.markConversationAsRead(conversationId, myId)
        }
    }

    fun onExitScreen() {
        typingDebounceJob?.cancel()
        typingDebounceJob = null
        val myId = currentUserId.value ?: return
        RealtimeManager.setUserActiveConversation(myId, null)
        chatRepository.exitConversationScreen(conversationId)
        chatRepository.stopTyping(conversationId, myId)
    }

    fun onTextInputChange(text: String) {
        _uiState.value = _uiState.value.copy(textInput = text)
        val myId = currentUserId.value ?: return
        typingDebounceJob?.cancel()
        if (text.isNotBlank()) {
            chatRepository.notifyTyping(conversationId, myId)
            typingDebounceJob = viewModelScope.launch {
                delay(2200L)
                chatRepository.stopTyping(conversationId, myId)
            }
        } else {
            chatRepository.stopTyping(conversationId, myId)
        }
    }

    fun appendEmoji(emoji: String) {
        val currentText = _uiState.value.textInput
        onTextInputChange(currentText + emoji)
    }

    fun toggleEmojiPicker() {
        _uiState.value = _uiState.value.copy(isEmojiPickerOpen = !_uiState.value.isEmojiPickerOpen)
    }

    fun setAttachment(uri: String?, name: String?, size: Long?, type: String? = "IMAGE") {
        _uiState.value = _uiState.value.copy(
            selectedAttachmentUri = uri,
            selectedAttachmentName = name,
            selectedAttachmentSize = size,
            selectedAttachmentType = type
        )
    }

    fun clearAttachment() {
        _uiState.value = _uiState.value.copy(
            selectedAttachmentUri = null,
            selectedAttachmentName = null,
            selectedAttachmentSize = null,
            selectedAttachmentType = null
        )
    }

    fun setPreviewImage(uri: String?) {
        _uiState.value = _uiState.value.copy(previewImageUri = uri)
    }

    fun sendMessage() {
        val myId = currentUserId.value ?: return
        val state = _uiState.value
        val text = state.textInput.trim()
        val hasAttachment = state.selectedAttachmentUri != null

        if (text.isBlank() && !hasAttachment) return

        typingDebounceJob?.cancel()
        typingDebounceJob = null

        viewModelScope.launch {
            chatRepository.sendMessage(
                conversationId = conversationId,
                senderId = myId,
                recipientId = otherUserId,
                content = text,
                attachmentUri = state.selectedAttachmentUri,
                attachmentType = state.selectedAttachmentType,
                attachmentSize = state.selectedAttachmentSize,
                attachmentName = state.selectedAttachmentName
            )

            // Clear composer state
            _uiState.value = _uiState.value.copy(
                textInput = "",
                selectedAttachmentUri = null,
                selectedAttachmentName = null,
                selectedAttachmentSize = null,
                selectedAttachmentType = null,
                isEmojiPickerOpen = false
            )
        }
    }

    fun switchUserToOther(onSwitched: (newConversationId: String, newOtherUserId: String) -> Unit) {
        val myId = currentUserId.value ?: return
        viewModelScope.launch {
            // Switch session to other user
            userRepository.switchUser(otherUserId)
            // Reverse conversation roles
            onSwitched(conversationId, myId)
        }
    }

    override fun onCleared() {
        super.onCleared()
        onExitScreen()
    }
}
