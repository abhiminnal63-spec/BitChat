package com.example.ui.navigation

sealed class Screen {
    data object Auth : Screen()
    data object Home : Screen()
    data class Chat(val conversationId: String, val otherUserId: String) : Screen()
    data object Profile : Screen()
    data object NewChat : Screen()
}
