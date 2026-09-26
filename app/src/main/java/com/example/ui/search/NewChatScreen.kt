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
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.example.data.repository.ChatRepository
import com.example.data.repository.UserRepository
import com.example.ui.home.DirectoryUserItem
import com.example.ui.theme.BrutalistCard
import com.example.ui.theme.BrutalistTheme
import com.example.ui.theme.SharpCorner
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
    var searchQuery by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val searchResults by userRepository.searchUsers(
        query = searchQuery,
        currentUserId = currentUser?.id ?: ""
    ).collectAsState(initial = emptyList())

    val allUsers by userRepository.getAllUsersExcept(
        currentUserId = currentUser?.id ?: ""
    ).collectAsState(initial = emptyList())

    val displayList = if (searchQuery.isBlank()) allUsers else searchResults

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
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
                    )
                }
            }

            // Results List
            if (displayList.isEmpty()) {
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
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PersonSearch,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = if (searchQuery.isNotBlank()) "NO USERS MATCHED QUERY" else "NO OTHER USERS REGISTERED",
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textPrimary
                            )
                            Text(
                                text = if (searchQuery.isNotBlank())
                                    "No user found matching '$searchQuery'. Verify the exact username."
                                else
                                    "To test peer-to-peer real-time communication, create another account via Switch / Logout to have two registered peers communicating.",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textSecondary,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text(
                            text = if (searchQuery.isNotBlank()) "SEARCH RESULTS (${displayList.size})" else "ALL REGISTERED USERS (${displayList.size})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = colors.textPrimary
                        )
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
