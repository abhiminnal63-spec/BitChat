package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.data.database.EasappDatabase
import com.example.data.firestore.FirestoreSyncManager
import com.example.data.repository.ChatRepository
import com.example.data.repository.UserRepository
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.AuthViewModel
import com.example.ui.chat.ChatScreen
import com.example.ui.chat.ChatViewModel
import com.example.ui.home.HomeScreen
import com.example.ui.home.HomeViewModel
import com.example.ui.navigation.Screen
import com.example.ui.profile.ProfileScreen
import com.example.ui.profile.ProfileViewModel
import com.example.ui.search.NewChatScreen
import com.example.ui.theme.BrutalistBackground
import com.example.ui.theme.BrutalistBlack
import com.example.ui.theme.EasappTheme
import com.example.util.ThemeManager

class MainActivity : ComponentActivity() {

    private var userRepositoryRef: UserRepository? = null

    override fun onStart() {
        super.onStart()
        userRepositoryRef?.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        userRepositoryRef?.onAppBackgrounded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = EasappDatabase.getInstance(applicationContext)
        val globalRelayEngine = com.example.data.relay.GlobalRelayEngine(
            userDao = database.userDao(),
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao()
        )
        val firestoreSyncManager = FirestoreSyncManager(
            context = applicationContext,
            userDao = database.userDao(),
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao()
        )
        val userRepository = UserRepository(
            userDao = database.userDao(),
            context = applicationContext,
            firestoreSyncManager = firestoreSyncManager,
            relayEngine = globalRelayEngine
        )
        userRepositoryRef = userRepository

        val chatRepository = ChatRepository(
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            userDao = database.userDao(),
            firestoreSyncManager = firestoreSyncManager,
            relayEngine = globalRelayEngine,
            appContext = applicationContext
        )
        val themeManager = ThemeManager(applicationContext)

        setContent {
            val isDarkMode by themeManager.isDarkMode.collectAsState()
            val selectedTheme by themeManager.selectedTheme.collectAsState()

            EasappTheme(
                darkTheme = isDarkMode,
                colorTheme = selectedTheme
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = if (isDarkMode) BrutalistBlack else BrutalistBackground
                ) {
                    EasappApp(
                        database = database,
                        userRepository = userRepository,
                        chatRepository = chatRepository,
                        themeManager = themeManager
                    )
                }
            }
        }
    }
}

@Composable
fun EasappApp(
    database: EasappDatabase,
    userRepository: UserRepository,
    chatRepository: ChatRepository,
    themeManager: ThemeManager
) {
    val currentUserId by userRepository.currentUserId.collectAsState()
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Auth) }

    // Synchronize authentication state with current screen
    LaunchedEffect(currentUserId) {
        if (currentUserId == null) {
            currentScreen = Screen.Auth
        } else if (currentScreen is Screen.Auth) {
            currentScreen = Screen.Home
        }
    }

    // Mobile-first Animated Navigation Graph
    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            if (targetState is Screen.Chat || targetState is Screen.Profile || targetState is Screen.NewChat) {
                (slideInHorizontally(initialOffsetX = { it }) + fadeIn())
                    .togetherWith(slideOutHorizontally(targetOffsetX = { -it / 3 }) + fadeOut())
            } else {
                (slideInHorizontally(initialOffsetX = { -it / 3 }) + fadeIn())
                    .togetherWith(slideOutHorizontally(targetOffsetX = { it }) + fadeOut())
            }
        },
        label = "ScreenTransition"
    ) { screen ->
        when (screen) {
            is Screen.Auth -> {
                val authViewModel = remember { AuthViewModel(userRepository) }
                AuthScreen(
                    viewModel = authViewModel,
                    onAuthSuccess = {
                        currentScreen = Screen.Home
                    }
                )
            }

            is Screen.Home -> {
                val homeViewModel = remember(currentUserId) {
                    HomeViewModel(
                        userRepository = userRepository,
                        chatRepository = chatRepository,
                        userDao = database.userDao()
                    )
                }
                HomeScreen(
                    viewModel = homeViewModel,
                    onNavigateToChat = { convId, otherId ->
                        currentScreen = Screen.Chat(convId, otherId)
                    },
                    onNavigateToProfile = {
                        currentScreen = Screen.Profile
                    },
                    onNavigateToNewChat = {
                        currentScreen = Screen.NewChat
                    },
                    onLoggedOut = {
                        currentScreen = Screen.Auth
                    }
                )
            }

            is Screen.Chat -> {
                val chatViewModel = remember(screen.conversationId, currentUserId) {
                    ChatViewModel(
                        conversationId = screen.conversationId,
                        otherUserId = screen.otherUserId,
                        chatRepository = chatRepository,
                        userRepository = userRepository,
                        userDao = database.userDao()
                    )
                }
                ChatScreen(
                    viewModel = chatViewModel,
                    onNavigateBack = {
                        currentScreen = Screen.Home
                    },
                    onSwitchedToOtherUser = { newConvId, newOtherId ->
                        currentScreen = Screen.Chat(newConvId, newOtherId)
                    }
                )
            }

            is Screen.Profile -> {
                val profileViewModel = remember(currentUserId) {
                    ProfileViewModel(userRepository, themeManager)
                }
                ProfileScreen(
                    viewModel = profileViewModel,
                    onNavigateBack = {
                        currentScreen = Screen.Home
                    },
                    onLoggedOut = {
                        currentScreen = Screen.Auth
                    }
                )
            }

            is Screen.NewChat -> {
                NewChatScreen(
                    userRepository = userRepository,
                    chatRepository = chatRepository,
                    onNavigateBack = {
                        currentScreen = Screen.Home
                    },
                    onNavigateToChat = { convId, otherId ->
                        currentScreen = Screen.Chat(convId, otherId)
                    }
                )
            }
        }
    }
}
