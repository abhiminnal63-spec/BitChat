package com.example.ui.home

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.UserEntity
import com.example.data.model.normalizeUsername
import com.example.ui.theme.BrutalistAvatar
import com.example.ui.theme.BrutalistBadge
import com.example.ui.theme.BrutalistBlack
import com.example.ui.theme.BrutalistButton
import com.example.ui.theme.BrutalistCard
import com.example.ui.theme.BrutalistWhite
import com.example.ui.theme.EasappAccent
import com.example.ui.theme.EasappBackground
import com.example.ui.theme.EasappBorder
import com.example.ui.theme.EasappOnlineGreen
import com.example.ui.theme.EasappSecondary
import com.example.ui.theme.MessageStatusIndicator
import com.example.ui.theme.SharpCorner
import com.example.ui.theme.ShootingStarFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToChat: (conversationId: String, otherUserId: String) -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToNewChat: () -> Unit,
    onLoggedOut: () -> Unit
) {
    val currentUser by viewModel.currentUser.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val conversations by viewModel.conversationsList.collectAsState()
    val directoryUsers by viewModel.directoryUsers.collectAsState()
    val allUsers by viewModel.allRegisteredUsers.collectAsState()
    val isNetworkConnected by viewModel.isNetworkConnected.collectAsState()
    val focusManager = LocalFocusManager.current
    val colors = com.example.ui.theme.BrutalistTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top Brutalist Header
            BrutalistCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = colors.cardBackground,
                borderColor = colors.border,
                shadowOffset = 0.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "BITCHAT",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 26.sp,
                                    fontFamily = ShootingStarFontFamily,
                                    letterSpacing = 0.5.sp,
                                    color = colors.textPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                // Network state indicator
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .background(if (isNetworkConnected) EasappOnlineGreen else EasappSecondary, SharpCorner)
                                        .border(1.dp, colors.border, SharpCorner)
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                        .clickable { viewModel.toggleNetworkSimulation() }
                                ) {
                                    Icon(
                                        imageVector = if (isNetworkConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                                        contentDescription = null,
                                        tint = BrutalistBlack,
                                        modifier = Modifier.size(10.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = if (isNetworkConnected) "LIVE" else "OFFLINE",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.Monospace,
                                        color = BrutalistBlack
                                    )
                                }
                            }
                            if (currentUser != null) {
                                Text(
                                    text = "SESSION: @${currentUser?.username}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textSecondary
                                )
                            }
                        }

                        // Right actions: Fast Account Switcher + Profile
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Switch Session Button
                            Box(
                                modifier = Modifier
                                    .background(colors.accent, SharpCorner)
                                    .border(1.5.dp, colors.border, SharpCorner)
                                    .clickable { viewModel.setSwitchUserDialogVisible(true) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                                    .testTag("button_switch_session"),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.SwapHoriz,
                                        contentDescription = "Switch User",
                                        tint = colors.accentOn,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "SWITCH",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.Monospace,
                                        color = colors.accentOn
                                    )
                                }
                            }

                            // Profile Button
                            Box(
                                modifier = Modifier
                                    .clickable { onNavigateToProfile() }
                                    .testTag("button_open_profile")
                            ) {
                                BrutalistAvatar(
                                    seedOrName = currentUser?.displayName ?: "ME",
                                    avatarId = currentUser?.avatarSeed,
                                    size = 38.dp,
                                    isOnline = true,
                                    showOnlineBadge = false
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Brutalist Search Bar
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = { viewModel.onSearchQueryChange(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("home_search_input"),
                        singleLine = true,
                        placeholder = {
                            Text(
                                text = "SEARCH CHATS OR @USERS...",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textSecondary
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = colors.textPrimary
                            )
                        },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear search",
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
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Tab Segmented Control: [ CHATS ] [ USERS / DIRECTORY ]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isChatsActive = uiState.activeTab == 0
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (isChatsActive) colors.accent else colors.cardBackground,
                                    SharpCorner
                                )
                                .border(2.dp, colors.border, SharpCorner)
                                .clickable { viewModel.setActiveTab(0) }
                                .padding(vertical = 10.dp)
                                .testTag("tab_home_chats"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Chat,
                                    contentDescription = null,
                                    tint = if (isChatsActive) colors.accentOn else colors.textPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "CHATS (${conversations.size})",
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = if (isChatsActive) colors.accentOn else colors.textPrimary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            // Main List Content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val isSearchActive = normalizeUsername(uiState.searchQuery).isNotBlank()
                if (uiState.activeTab == 0) {
                    // CONVERSATIONS & REALTIME USERNAME SEARCH TAB
                    when {
                        isSearchActive && uiState.searchErrorMessage != null && conversations.isEmpty() -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                BrutalistCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("home_search_error_card"),
                                    backgroundColor = colors.cardBackground,
                                    borderColor = EasappSecondary,
                                    shadowOffset = 4.dp
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text(
                                            text = "Unable to search because of a network/backend error.",
                                            fontWeight = FontWeight.Black,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            color = colors.textPrimary
                                        )
                                        BrutalistButton(
                                            text = "RETRY CLOUD SEARCH",
                                            onClick = { viewModel.retrySearch() },
                                            backgroundColor = colors.accent,
                                            textColor = colors.accentOn,
                                            shadowOffset = 2.dp
                                        )
                                    }
                                }
                            }
                        }

                        isSearchActive && uiState.isSearchingBackend && conversations.isEmpty() && directoryUsers.isEmpty() -> {
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
                                    shadowOffset = 4.dp
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text(
                                            text = "SEARCHING SHARED BACKEND...",
                                            fontWeight = FontWeight.Black,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            color = colors.textPrimary
                                        )
                                    }
                                }
                            }
                        }

                        conversations.isEmpty() && (!isSearchActive || directoryUsers.isEmpty()) -> {
                            EmptyConversationsState(
                                hasSearchQuery = isSearchActive,
                                onExploreUsers = onNavigateToNewChat
                            )
                        }

                        else -> {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (isSearchActive && directoryUsers.isNotEmpty()) {
                                    item(key = "header_matching_users") {
                                        Text(
                                            text = "REGISTERED ACCOUNTS (${directoryUsers.size})",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = FontFamily.Monospace,
                                            color = colors.textPrimary
                                        )
                                    }
                                    items(directoryUsers, key = { "search_user_${it.id}" }) { user ->
                                        DirectoryUserItem(
                                            user = user,
                                            onStartChat = {
                                                viewModel.startChatWithUser(user.id, onNavigateToChat)
                                            }
                                        )
                                    }
                                }

                                if (conversations.isNotEmpty()) {
                                    if (isSearchActive) {
                                        item(key = "header_matching_chats") {
                                            Text(
                                                text = "CONVERSATIONS (${conversations.size})",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Black,
                                                fontFamily = FontFamily.Monospace,
                                                color = colors.textPrimary
                                            )
                                        }
                                    }
                                    items(conversations, key = { it.conversation.id }) { item ->
                                        ConversationListItem(
                                            item = item,
                                            currentUserId = currentUser?.id ?: "",
                                            onClick = {
                                                onNavigateToChat(item.conversation.id, item.otherUser.id)
                                            },
                                            onLongClick = {
                                                viewModel.requestDeleteConversation(item)
                                            }
                                        )
                                    }
                                }

                                item {
                                    Spacer(modifier = Modifier.height(72.dp))
                                }
                            }
                        }
                    }
                } else {
                    // DIRECTORY TAB
                    if (directoryUsers.isEmpty()) {
                        EmptyDirectoryState(
                            hasSearchQuery = uiState.searchQuery.isNotBlank(),
                            onInvite = { onNavigateToProfile() }
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(directoryUsers, key = { it.id }) { user ->
                                DirectoryUserItem(
                                    user = user,
                                    onStartChat = {
                                        viewModel.startChatWithUser(user.id, onNavigateToChat)
                                    }
                                )
                            }
                            item {
                                Spacer(modifier = Modifier.height(72.dp))
                            }
                        }
                    }
                }

                // Tactile Brutalist Floating Action Button
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(20.dp)
                ) {
                    BrutalistButton(
                        text = "+ NEW CHAT",
                        onClick = onNavigateToNewChat,
                        backgroundColor = colors.accent,
                        textColor = colors.accentOn,
                        leadingIcon = Icons.Default.Add,
                        shadowOffset = 4.dp,
                        testTag = "button_new_chat"
                    )
                }
            }
        }

        // Fast Switch User / Multi-Session Dialog
        if (uiState.isSwitchUserDialogVisible) {
            AlertDialog(
                onDismissRequest = { viewModel.setSwitchUserDialogVisible(false) },
                confirmButton = {},
                dismissButton = {
                    BrutalistButton(
                        text = "CLOSE",
                        onClick = { viewModel.setSwitchUserDialogVisible(false) },
                        backgroundColor = colors.cardBackground,
                        textColor = colors.textPrimary,
                        borderColor = colors.border,
                        shadowOffset = 2.dp
                    )
                },
                title = {
                    Text(
                        text = "ACTIVE SESSIONS // SWITCH",
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                        color = colors.textPrimary
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Test real-time messaging between accounts instantly without re-typing passwords:",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.textSecondary
                        )

                        allUsers.forEach { user ->
                            val isCurrent = user.id == currentUser?.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        2.dp,
                                        if (isCurrent) colors.accent else colors.border,
                                        SharpCorner
                                    )
                                    .background(
                                        if (isCurrent) colors.accent.copy(alpha = 0.25f) else colors.cardBackground,
                                        SharpCorner
                                    )
                                    .clickable {
                                        if (!isCurrent) viewModel.switchUser(user.id)
                                    }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BrutalistAvatar(
                                    seedOrName = user.displayName,
                                    avatarId = user.avatarSeed,
                                    size = 36.dp,
                                    isOnline = user.isOnline
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = user.displayName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = colors.textPrimary
                                    )
                                    Text(
                                        text = "@${user.username}",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = colors.textSecondary
                                    )
                                }
                                if (isCurrent) {
                                    Box(
                                        modifier = Modifier
                                            .background(colors.accent, SharpCorner)
                                            .border(1.dp, colors.border, SharpCorner)
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "ACTIVE",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = FontFamily.Monospace,
                                            color = colors.accentOn
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .background(colors.cardBackground, SharpCorner)
                                            .border(1.dp, colors.border, SharpCorner)
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "ACTIVATE",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = FontFamily.Monospace,
                                            color = colors.textPrimary
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Add new account button
                        BrutalistButton(
                            text = "+ REGISTER ANOTHER ACCOUNT",
                            onClick = {
                                viewModel.setSwitchUserDialogVisible(false)
                                viewModel.logout(onLoggedOut)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = colors.inputBackground,
                            textColor = colors.textPrimary,
                            borderColor = colors.border,
                            shadowOffset = 2.dp
                        )
                    }
                },
                shape = SharpCorner,
                containerColor = colors.cardBackground
            )
        }

        // Long-Press Delete Chat Confirmation Dialog
        val pendingDelete = uiState.conversationPendingDeletion
        if (pendingDelete != null) {
            Dialog(onDismissRequest = { viewModel.cancelDeleteConversation() }) {
                BrutalistCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = colors.cardBackground,
                    borderColor = colors.border,
                    borderWidth = 2.5.dp,
                    shadowOffset = 5.dp,
                    testTag = "delete_chat_dialog"
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Brutalist Header Banner
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(EasappSecondary, SharpCorner)
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = "DELETE CHAT?",
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 16.sp,
                                color = BrutalistWhite
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(colors.border)
                        )

                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(colors.inputBackground, SharpCorner)
                                    .border(1.5.dp, colors.border, SharpCorner)
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "TARGET: @${pendingDelete.otherUser.username}",
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = colors.textPrimary
                                )
                            }

                            Text(
                                text = "This will remove this conversation from your chat list.",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                                lineHeight = 18.sp
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                BrutalistButton(
                                    text = "CANCEL",
                                    onClick = { viewModel.cancelDeleteConversation() },
                                    modifier = Modifier.weight(1f),
                                    backgroundColor = colors.cardBackground,
                                    textColor = colors.textPrimary,
                                    borderColor = colors.border,
                                    shadowOffset = 3.dp,
                                    testTag = "button_cancel_delete_chat"
                                )

                                BrutalistButton(
                                    text = "DELETE",
                                    onClick = { viewModel.confirmDeleteConversation() },
                                    modifier = Modifier.weight(1f),
                                    backgroundColor = EasappSecondary,
                                    textColor = BrutalistWhite,
                                    borderColor = colors.border,
                                    shadowOffset = 3.dp,
                                    testTag = "button_confirm_delete_chat"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConversationListItem(
    item: EnrichedConversation,
    currentUserId: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val colors = com.example.ui.theme.BrutalistTheme.colors
    val isSenderMe = item.conversation.lastMessageSenderId == currentUserId
    val dateText = com.example.util.DateTimeUtils.formatConversationListTime(item.conversation.updatedAt)
    val isPeerOnline = com.example.util.DateTimeUtils.isEffectivelyOnline(
        isOnline = item.otherUser.isOnline,
        lastSeenTimestamp = item.otherUser.lastSeenTimestamp
    )

    BrutalistCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = colors.cardBackground,
        borderColor = colors.border,
        shadowOffset = 3.dp,
        onClick = onClick,
        onLongClick = onLongClick,
        testTag = "conv_item_${item.otherUser.username}"
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            BrutalistAvatar(
                seedOrName = item.otherUser.displayName,
                avatarId = item.otherUser.avatarSeed,
                size = 48.dp,
                isOnline = isPeerOnline
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Body
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.otherUser.displayName,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = dateText,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textSecondary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (item.isOtherUserTyping) {
                            Text(
                                text = "• typing...",
                                fontWeight = FontWeight.Bold,
                                color = EasappSecondary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        } else {
                            if (isSenderMe && item.conversation.lastMessageText.isNotBlank()) {
                                MessageStatusIndicator(
                                    status = item.conversation.lastMessageStatus,
                                    showLabel = true,
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                            }
                            Text(
                                text = item.conversation.lastMessageText.ifBlank { "Tap to send message" },
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Unread Pill
                    if (item.unreadCount > 0) {
                        Box(
                            modifier = Modifier
                                .background(colors.accent, SharpCorner)
                                .border(1.5.dp, colors.border, SharpCorner)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${item.unreadCount}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = colors.accentOn
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DirectoryUserItem(
    user: UserEntity,
    onStartChat: () -> Unit
) {
    val colors = com.example.ui.theme.BrutalistTheme.colors
    val isUserOnline = com.example.util.DateTimeUtils.isEffectivelyOnline(
        isOnline = user.isOnline,
        lastSeenTimestamp = user.lastSeenTimestamp
    )
    val statusBadgeText = if (isUserOnline) {
        "[ONLINE]"
    } else {
        "[${com.example.util.DateTimeUtils.formatLastSeen(false, user.lastSeenTimestamp)}]"
    }
    BrutalistCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = colors.cardBackground,
        borderColor = colors.border,
        shadowOffset = 3.dp,
        onClick = onStartChat,
        testTag = "directory_user_${user.username}"
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrutalistAvatar(
                seedOrName = user.displayName,
                avatarId = user.avatarSeed,
                size = 46.dp,
                isOnline = isUserOnline
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = user.displayName,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusBadgeText,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (isUserOnline) EasappOnlineGreen else colors.textSecondary
                    )
                }
                Text(
                    text = "@${user.username} // ${user.statusMessage}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(colors.accent, SharpCorner)
                    .border(1.5.dp, colors.border, SharpCorner)
                    .clickable { onStartChat() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "CHAT ->",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = colors.accentOn
                )
            }
        }
    }
}

@Composable
fun EmptyConversationsState(
    hasSearchQuery: Boolean,
    onExploreUsers: () -> Unit
) {
    val colors = com.example.ui.theme.BrutalistTheme.colors
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
            shadowOffset = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(colors.accent, SharpCorner)
                        .border(2.dp, colors.border, SharpCorner),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Chat,
                        contentDescription = null,
                        tint = colors.accentOn,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = if (hasSearchQuery) "USER NOT FOUND" else "ZERO CONVERSATIONS DETECTED",
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                BrutalistButton(
                    text = "START NEW CHAT // DISCOVERY ->",
                    onClick = onExploreUsers,
                    backgroundColor = colors.accent,
                    textColor = colors.accentOn,
                    shadowOffset = 2.dp
                )
            }
        }
    }
}

@Composable
fun EmptyDirectoryState(
    hasSearchQuery: Boolean,
    onInvite: () -> Unit
) {
    val colors = com.example.ui.theme.BrutalistTheme.colors
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
            shadowOffset = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (hasSearchQuery) "USER NOT FOUND" else "SEARCH BY @USERNAME",
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = colors.textPrimary
                )
                Text(
                    text = "Enter a @username in the search bar above to discover users on the shared backend.",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.textSecondary,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

fun formatTimestamp(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val formatTime = SimpleDateFormat("h:mm a", Locale.getDefault())
    val formatDate = SimpleDateFormat("MMM d", Locale.getDefault())

    return when {
        diff < 24 * 60 * 60 * 1000L -> formatTime.format(Date(timestamp))
        diff < 48 * 60 * 60 * 1000L -> "YESTERDAY"
        else -> formatDate.format(Date(timestamp))
    }
}
