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
    val searchErrorMessage: String? = null,
    val conversationPendingDeletion: EnrichedConversation? = null
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
        viewModelScope.launch {
            userRepository.currentUserId.collect { uid ->
                if (!uid.isNullOrBlank() && normalizeUsername(_uiState.value.searchQuery).isNotBlank()) {
                    triggerBackendSearch(_uiState.value.searchQuery, immediate = true)
                } else {
                    _backendSearchResults.value = emptyList()
                }
            }
        }
    }

    // ONLY accounts that have explicitly authenticated on THIS physical device (for local session switcher)
    val allRegisteredUsers: StateFlow<List<UserEntity>> = userRepository.getLocalAuthenticatedSessionsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val fetchingPeerProfileIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun triggerFetchPeerProfile(peerId: String) {
        if (peerId.isBlank() || !fetchingPeerProfileIds.add(peerId)) return
        viewModelScope.launch {
            try {
                userRepository.fetchAndCacheUserById(peerId)
            } finally {
                fetchingPeerProfileIds.remove(peerId)
            }
        }
    }

    // Reactive conversations list backed by shared cloud conversations and per-user deletion state
    val conversationsList: StateFlow<List<EnrichedConversation>> = userRepository.currentUserId
        .flatMapLatest { userId ->
            if (userId == null) {
                flowOf(emptyList())
            } else {
                combine(
                    chatRepository.getConversationsForUser(userId),
                    userDao.getAllUsersFlow(),
                    _backendSearchResults,
                    RealtimeManager.typingUsers,
                    _uiState
                ) { rawConversations, allUsers, backendUsers, typingMap, state ->
                    val userMap = linkedMapOf<String, UserEntity>()
                    for (u in backendUsers) userMap[u.id] = u
                    for (u in allUsers) {
                        val existing = userMap[u.id]
                        if (existing == null || u.lastSeenTimestamp >= existing.lastSeenTimestamp) {
                            userMap[u.id] = u
                        }
                    }

                    rawConversations
                        .filter { it.lastMessageText.isNotBlank() || it.lastMessageTimestamp > 0L }
                        .distinctBy { conv ->
                            if (conv.participant1Id == userId) conv.participant2Id else conv.participant1Id
                        }
                        .mapNotNull { conv ->
                            val otherId = if (conv.participant1Id == userId) conv.participant2Id else conv.participant1Id
                            if (otherId.isBlank() || otherId == userId) return@mapNotNull null

                            val otherUser = userMap[otherId] ?: run {
                                triggerFetchPeerProfile(otherId)
                                UserEntity(
                                    id = otherId,
                                    username = "",
                                    usernameNormalized = "",
                                    displayName = "Loading profile...",
                                    statusMessage = "Available on Easapp",
                                    isOnline = false
                                )
                            }

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

    // Shared cloud user search results — ONLY populated when the user searches a non-blank username
    val directoryUsers: StateFlow<List<UserEntity>> = combine(
        userRepository.currentUserId,
        userDao.getAllUsersFlow(),
        _backendSearchResults,
        _uiState
    ) { currentId, allUsers, backendUsers, state ->
        val normQ = normalizeUsername(state.searchQuery)
        if (normQ.isBlank() || state.searchErrorMessage != null) {
            emptyList()
        } else {
            val liveById = allUsers.associateBy { it.id }
            val merged = linkedMapOf<String, UserEntity>()
            for (u in backendUsers) {
                if (u.id != currentId) {
                    val key = normalizeUsername(u.usernameNormalized.ifBlank { u.username })
                    if (key.isNotBlank()) {
                        val live = liveById[u.id]
                        val freshest = if (live != null && live.lastSeenTimestamp >= u.lastSeenTimestamp) {
                            u.copy(
                                displayName = live.displayName.ifBlank { u.displayName },
                                avatarSeed = live.avatarSeed.ifBlank { u.avatarSeed },
                                statusMessage = live.statusMessage.ifBlank { u.statusMessage },
                                isOnline = live.isOnline,
                                lastSeenTimestamp = live.lastSeenTimestamp
                            )
                        } else {
                            u
                        }
                        merged[key] = freshest
                    }
                }
            }
            merged.values.filter { user ->
                user.usernameNormalized.contains(normQ) ||
                    user.username.lowercase().contains(normQ)
            }.sortedWith(
                compareBy<UserEntity> {
                    when {
                        it.usernameNormalized == normQ -> 0
                        it.usernameNormalized.startsWith(normQ) -> 1
                        else -> 2
                    }
                }.thenByDescending { it.isOnline }.thenBy { it.displayName.lowercase() }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setActiveTab(index: Int) {
        _uiState.value = _uiState.value.copy(activeTab = index)
        if (index == 1 && normalizeUsername(_uiState.value.searchQuery).isNotBlank()) {
            triggerBackendSearch(_uiState.value.searchQuery, immediate = true)
        }
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
        if (normQ.isBlank()) {
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

            if (!immediate) {
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

    fun requestDeleteConversation(item: EnrichedConversation) {
        _uiState.value = _uiState.value.copy(conversationPendingDeletion = item)
    }

    fun cancelDeleteConversation() {
        _uiState.value = _uiState.value.copy(conversationPendingDeletion = null)
    }

    fun confirmDeleteConversation() {
        val target = _uiState.value.conversationPendingDeletion ?: return
        val authenticatedUid = userRepository.currentUserId.value ?: return
        _uiState.value = _uiState.value.copy(conversationPendingDeletion = null)
        viewModelScope.launch {
            chatRepository.deleteConversationForUser(
                conversationId = target.conversation.id,
                authenticatedUserId = authenticatedUid
            )
        }
    }

    fun deleteConversationById(conversationId: String) {
        val authenticatedUid = userRepository.currentUserId.value ?: return
        _uiState.value = _uiState.value.copy(conversationPendingDeletion = null)
        viewModelScope.launch {
            chatRepository.deleteConversationForUser(
                conversationId = conversationId,
                authenticatedUserId = authenticatedUid
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
        if (_uiState.value.searchQuery.isNotBlank() || _uiState.value.activeTab == 1) {
            triggerBackendSearch(_uiState.value.searchQuery, immediate = true)
        }
    }
}
