package com.example.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.UserEntity
import com.example.data.model.normalizeUsername
import com.example.data.realtime.RealtimeManager
import com.example.data.repository.ChatRepository
import com.example.data.repository.UserRepository
import com.example.ui.home.DirectoryUserItem
import com.example.ui.theme.BrutalistButton
import com.example.ui.theme.BrutalistCard
import com.example.ui.theme.BrutalistTheme
import com.example.ui.theme.EasappSecondary
import com.example.ui.theme.SharpCorner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NewChatScreen(
    userRepository: UserRepository,
    chatRepository: ChatRepository,
    onNavigateBack: () -> Unit,
    onNavigateToChat: (conversationId: String, otherUserId: String) -> Unit
) {
    val colors = BrutalistTheme.colors
    val currentUser by userRepository.currentUser.collectAsState()
    val currentUserId = currentUser?.id ?: ""
    val isNetworkConnected by RealtimeManager.isNetworkConnected.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var backendResults by remember { mutableStateOf<List<UserEntity>>(emptyList()) }
    var isSearchingBackend by remember { mutableStateOf(false) }
    var backendError by remember { mutableStateOf<String?>(null) }
    var retryCounter by remember { mutableIntStateOf(0) }

    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val liveUsers by userRepository.getAllUsersFlow().collectAsState(initial = emptyList())
    val normalizedQuery = normalizeUsername(searchQuery)

    // Query shared cloud backend in real time ONLY when a non-blank username query is entered
    LaunchedEffect(normalizedQuery, currentUserId, isNetworkConnected, retryCounter) {
        if (normalizedQuery.isBlank()) {
            backendResults = emptyList()
            backendError = null
            isSearchingBackend = false
            return@LaunchedEffect
        }

        if (!isNetworkConnected) {
            isSearchingBackend = false
            backendError = "Unable to search because of a network/backend error."
            return@LaunchedEffect
        }

        isSearchingBackend = true
        backendError = null

        delay(100L)

        val result = userRepository.searchUsersInBackend(
            rawQuery = normalizedQuery,
            currentUserId = currentUserId
        )

        result.fold(
            onSuccess = { users ->
                backendResults = users
                backendError = null
                isSearchingBackend = false
            },
            onFailure = {
                backendError = "Unable to search because of a network/backend error."
                isSearchingBackend = false
            }
        )
    }

    // Use ONLY shared backend results for discovery; enrich online/lastSeen status for matched backend users only
    val displayList = remember(backendResults, liveUsers, normalizedQuery, currentUserId, backendError) {
        if (normalizedQuery.isBlank() || backendError != null) {
            emptyList()
        } else {
            val liveById = liveUsers.associateBy { it.id }
            val merged = linkedMapOf<String, UserEntity>()
            for (u in backendResults) {
                if (u.id != currentUserId) {
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
                user.usernameNormalized.contains(normalizedQuery) ||
                    user.username.lowercase().contains(normalizedQuery)
            }.sortedWith(
                compareBy<UserEntity> {
                    when {
                        it.usernameNormalized == normalizedQuery -> 0
                        it.usernameNormalized.startsWith(normalizedQuery) -> 1
                        else -> 2
                    }
                }.thenByDescending { it.isOnline }.thenBy { it.displayName.lowercase() }
            )
        }
    }

    BackHandler {
        onNavigateBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header
            BrutalistCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = colors.cardBackground,
                borderColor = colors.border,
                shadowOffset = 0.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onNavigateBack,
                                modifier = Modifier.testTag("new_chat_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = colors.textPrimary
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "START NEW CHAT // DISCOVERY",
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textPrimary
                            )
                        }

                        if (normalizedQuery.isNotBlank()) {
                            IconButton(
                                onClick = { retryCounter++ },
                                modifier = Modifier.testTag("new_chat_refresh_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh search",
                                    tint = colors.accent
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Search Input
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .testTag("new_chat_search_input"),
                        singleLine = true,
                        placeholder = {
                            Text(
                                text = "SEARCH BY @USERNAME OR NAME...",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textSecondary
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = colors.textPrimary
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear",
                                        tint = colors.textPrimary
                                    )
                                }
                            }
                        },
                        shape = SharpCorner,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.border.copy(alpha = 0.5f),
                            focusedContainerColor = colors.inputBackground,
                            unfocusedContainerColor = colors.inputBackground,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            cursorColor = colors.accent
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                focusManager.clearFocus()
                                retryCounter++
                            }
                        )
                    )
                }
            }

            // Results / Status Content
            when {
                normalizedQuery.isBlank() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BrutalistCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("discovery_neutral_card"),
                            backgroundColor = colors.cardBackground,
                            borderColor = colors.border,
                            shadowOffset = 3.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PersonSearch,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    text = "FIND USERS BY @USERNAME",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = "Enter a @username in the search box above to find registered accounts on the shared backend.",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.textSecondary,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }

                backendError != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BrutalistCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("search_error_card"),
                            backgroundColor = colors.cardBackground,
                            borderColor = EasappSecondary,
                            shadowOffset = 3.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudOff,
                                    contentDescription = null,
                                    tint = EasappSecondary,
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    text = "Unable to search because of a network/backend error.",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = "Check your internet connection and retry querying the shared user database.",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.textSecondary,
                                    lineHeight = 16.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                BrutalistButton(
                                    text = "RETRY CLOUD SEARCH",
                                    onClick = { retryCounter++ },
                                    backgroundColor = colors.accent,
                                    textColor = colors.accentOn,
                                    shadowOffset = 2.dp,
                                    testTag = "button_retry_search"
                                )
                            }
                        }
                    }
                }

                isSearchingBackend && displayList.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BrutalistCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = colors.cardBackground,
                            borderColor = colors.border,
                            shadowOffset = 3.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = colors.accent,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(32.dp)
                                )
                                Text(
                                    text = "SEARCHING SHARED BACKEND...",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.textPrimary
                                )
                            }
                        }
                    }
                }

                displayList.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BrutalistCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("no_user_found_card"),
                            backgroundColor = colors.cardBackground,
                            borderColor = colors.border,
                            shadowOffset = 3.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PersonSearch,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    text = "USER NOT FOUND",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = "No registered account matched '@$normalizedQuery' on the shared database.",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.textSecondary,
                                    lineHeight = 16.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                BrutalistButton(
                                    text = "RETRY SEARCH",
                                    onClick = { retryCounter++ },
                                    backgroundColor = colors.accent,
                                    textColor = colors.accentOn,
                                    shadowOffset = 2.dp
                                )
                            }
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "SEARCH RESULTS FOR '@$normalizedQuery' (${displayList.size})",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.textPrimary
                                )
                                if (isSearchingBackend) {
                                    CircularProgressIndicator(
                                        color = colors.accent,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }

                        items(displayList, key = { it.id }) { user ->
                            DirectoryUserItem(
                                user = user,
                                onStartChat = {
                                    val current = currentUser ?: return@DirectoryUserItem
                                    scope.launch {
                                        val convId = chatRepository.getOrCreateConversation(current.id, user.id)
                                        onNavigateToChat(convId, user.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
