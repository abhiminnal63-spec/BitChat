package com.example.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.dao.UserDao
import com.example.data.model.ConversationEntity
import com.example.data.model.UserEntity
import com.example.data.realtime.RealtimeManager
import com.example.data.repository.ChatRepository
import com.example.data.repository.UserRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EnrichedConversation(
    val conversation: ConversationEntity,
    val otherUser: UserEntity,
    val unreadCount: Int,
    val isOtherUserTyping: Boolean
)

data class HomeUiState(
    val activeTab: Int = 0, // 0 = Chats, 1 = Directory
    val searchQuery: String = "",
    val isSwitchUserDialogVisible: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val userRepository: UserRepository,
    private val chatRepository: ChatRepository,
    private val userDao: UserDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val currentUser: StateFlow<UserEntity?> = userRepository.currentUser
    val isNetworkConnected: StateFlow<Boolean> = RealtimeManager.isNetworkConnected

    val allRegisteredUsers: StateFlow<List<UserEntity>> = userRepository.getAllUsersFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Reactive conversations list
    val conversationsList: StateFlow<List<EnrichedConversation>> = userRepository.currentUserId
        .flatMapLatest { userId ->
            if (userId == null) {
                flowOf(emptyList())
            } else {
                combine(
                    chatRepository.getConversationsForUser(userId),
                    userDao.getAllUsersFlow(),
                    RealtimeManager.typingUsers,
                    _uiState
                ) { rawConversations, allUsers, typingMap, state ->
                    val userMap = allUsers.associateBy { it.id }

                    rawConversations.mapNotNull { conv ->
                        val otherId = if (conv.participant1Id == userId) conv.participant2Id else conv.participant1Id
                        val otherUser = userMap[otherId] ?: return@mapNotNull null

                        val unreadCount = if (conv.participant1Id == userId) conv.unreadCountForUser1 else conv.unreadCountForUser2
                        val typingSet = typingMap[conv.id] ?: emptySet()
                        val isTyping = typingSet.contains(otherId)

                        EnrichedConversation(
                            conversation = conv,
                            otherUser = otherUser,
                            unreadCount = unreadCount,
                            isOtherUserTyping = isTyping
                        )
                    }.filter { enriched ->
                        if (state.searchQuery.isBlank()) true
                        else {
                            val q = state.searchQuery.lowercase().trim()
                            enriched.otherUser.displayName.lowercase().contains(q) ||
                                    enriched.otherUser.username.lowercase().contains(q) ||
                                    enriched.conversation.lastMessageText.lowercase().contains(q)
                        }
                    }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Directory of all users on Easapp except current user
    val directoryUsers: StateFlow<List<UserEntity>> = combine(
        userRepository.currentUserId,
        userDao.getAllUsersFlow(),
        _uiState
    ) { currentId, allUsers, state ->
        allUsers.filter { it.id != currentId }.filter { user ->
            if (state.searchQuery.isBlank()) true
            else {
                val q = state.searchQuery.lowercase().trim()
                user.displayName.lowercase().contains(q) || user.username.lowercase().contains(q)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setActiveTab(index: Int) {
        _uiState.value = _uiState.value.copy(activeTab = index)
    }

    fun onSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun setSwitchUserDialogVisible(visible: Boolean) {
        _uiState.value = _uiState.value.copy(isSwitchUserDialogVisible = visible)
    }

    fun switchUser(userId: String) {
        viewModelScope.launch {
            userRepository.switchUser(userId)
            setSwitchUserDialogVisible(false)
        }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            userRepository.logout()
            onLoggedOut()
        }
    }

    fun startChatWithUser(otherUserId: String, onNavigateToChat: (conversationId: String, otherUserId: String) -> Unit) {
        val current = currentUser.value ?: return
        viewModelScope.launch {
            val convId = chatRepository.getOrCreateConversation(current.id, otherUserId)
            onNavigateToChat(convId, otherUserId)
        }
    }

    fun toggleNetworkSimulation() {
        RealtimeManager.setNetworkConnected(!isNetworkConnected.value)
    }
}
