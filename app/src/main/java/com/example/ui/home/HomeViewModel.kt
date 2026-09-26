package com.example.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.dao.UserDao
import com.example.data.model.ConversationEntity
import com.example.data.model.UserEntity
import com.example.data.model.normalizeUsername
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
    val isSwitchUserDialogVisible: Boolean = false,
    val isSearchingBackend: Boolean = false,
    val searchErrorMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val userRepository: UserRepository,
    private val chatRepository: ChatRepository,
    private val userDao: UserDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _backendSearchResults = MutableStateFlow<List<UserEntity>>(emptyList())
    private var searchJob: Job? = null

    val currentUser: StateFlow<UserEntity?> = userRepository.currentUser
    val isNetworkConnected: StateFlow<Boolean> = RealtimeManager.isNetworkConnected

    init {
        // Proactively sync registered users from the shared cloud backend when session starts
        viewModelScope.launch {
            userRepository.currentUserId.collect { uid ->
                if (!uid.isNullOrBlank() && RealtimeManager.isNetworkConnected.value) {
                    userRepository.searchUsersInBackend("", uid)
                }
            }
        }
    }

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
                        val normQ = normalizeUsername(state.searchQuery)
                        if (normQ.isBlank()) true
                        else {
                            val rawLower = state.searchQuery.trim().lowercase()
                            enriched.otherUser.usernameNormalized.contains(normQ) ||
                                enriched.otherUser.username.lowercase().contains(normQ) ||
                                enriched.otherUser.displayName.lowercase().contains(normQ) ||
                                enriched.conversation.lastMessageText.lowercase().contains(rawLower)
                        }
                    }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Real-time cloud + synced directory users matching normalized username query
    val directoryUsers: StateFlow<List<UserEntity>> = combine(
        userRepository.currentUserId,
        userDao.getAllUsersFlow(),
        _backendSearchResults,
        _uiState
    ) { currentId, allUsers, backendUsers, state ->
        if (state.searchErrorMessage != null) {
            emptyList()
        } else {
            val normQ = normalizeUsername(state.searchQuery)
            val merged = linkedMapOf<String, UserEntity>()
            for (u in backendUsers) {
                if (u.id != currentId) {
                    val key = normalizeUsername(u.usernameNormalized.ifBlank { u.username })
                    if (key.isNotBlank()) merged[key] = u
                }
            }
            for (u in allUsers) {
                if (u.id != currentId) {
                    val key = normalizeUsername(u.usernameNormalized.ifBlank { u.username })
                    if (key.isNotBlank() && !merged.containsKey(key)) {
                        merged[key] = u
                    }
                }
            }
            merged.values.filter { user ->
                if (normQ.isBlank()) true
                else {
                    user.usernameNormalized.contains(normQ) ||
                        user.username.lowercase().contains(normQ) ||
                        user.displayName.lowercase().contains(normQ)
                }
            }.sortedWith(
                compareBy<UserEntity> {
                    when {
                        normQ.isNotBlank() && it.usernameNormalized == normQ -> 0
                        normQ.isNotBlank() && it.usernameNormalized.startsWith(normQ) -> 1
                        else -> 2
                    }
                }.thenByDescending { it.isOnline }.thenBy { it.displayName.lowercase() }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setActiveTab(index: Int) {
        _uiState.value = _uiState.value.copy(activeTab = index)
    }

    fun onSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(
            searchQuery = query,
            searchErrorMessage = null
        )
        triggerBackendSearch(query)
    }

    fun retrySearch() {
        triggerBackendSearch(_uiState.value.searchQuery, immediate = true)
    }

    private fun triggerBackendSearch(rawQuery: String, immediate: Boolean = false) {
        searchJob?.cancel()
        val normQ = normalizeUsername(rawQuery)
        if (rawQuery.isBlank() && !immediate) {
            _backendSearchResults.value = emptyList()
            _uiState.value = _uiState.value.copy(
                isSearchingBackend = false,
                searchErrorMessage = null
            )
            return
        }

        searchJob = viewModelScope.launch {
            if (!RealtimeManager.isNetworkConnected.value) {
                _uiState.value = _uiState.value.copy(
                    isSearchingBackend = false,
                    searchErrorMessage = "Unable to search because of a network/backend error."
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isSearchingBackend = true,
                searchErrorMessage = null
            )

            if (!immediate && normQ.isNotEmpty()) {
                delay(120L)
            }

            val currentId = userRepository.currentUserId.value ?: ""
            val result = userRepository.searchUsersInBackend(rawQuery, currentId)
            result.fold(
                onSuccess = { users ->
                    _backendSearchResults.value = users
                    _uiState.value = _uiState.value.copy(
                        isSearchingBackend = false,
                        searchErrorMessage = null
                    )
                },
                onFailure = {
                    _uiState.value = _uiState.value.copy(
                        isSearchingBackend = false,
                        searchErrorMessage = "Unable to search because of a network/backend error."
                    )
                }
            )
        }
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
        val newState = !isNetworkConnected.value
        RealtimeManager.setNetworkConnected(newState)
        if (_uiState.value.searchQuery.isNotBlank()) {
            triggerBackendSearch(_uiState.value.searchQuery, immediate = true)
        }
    }
}
