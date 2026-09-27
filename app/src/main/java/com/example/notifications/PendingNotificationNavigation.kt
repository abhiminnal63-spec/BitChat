package com.example.notifications

import android.content.Intent
import com.example.data.model.buildDeterministicConversationId
import com.example.util.BitchatLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PendingNotificationTarget(
    val conversationId: String,
    val otherUserId: String,
    val messageId: String? = null,
    val type: String = "chat_message"
)

object PendingNotificationNavigation {
    private val _pendingTarget = MutableStateFlow<PendingNotificationTarget?>(null)
    val pendingTarget: StateFlow<PendingNotificationTarget?> = _pendingTarget.asStateFlow()

    fun handleIntent(intent: Intent?, currentUserId: String? = null): PendingNotificationTarget? {
        if (intent == null) return null
        val convId = (
            intent.getStringExtra(BitchatNotificationManager.EXTRA_CONVERSATION_ID)
                ?: intent.getStringExtra(BitchatNotificationManager.EXTRA_CONVERSATION_ID_KEY)
                ?: intent.getStringExtra("chatId")
                ?: intent.getStringExtra("conversationId")
            )?.trim().orEmpty()

        val otherId = (
            intent.getStringExtra(BitchatNotificationManager.EXTRA_OTHER_USER_ID)
                ?: intent.getStringExtra(BitchatNotificationManager.EXTRA_SENDER_UID)
                ?: intent.getStringExtra("senderId")
                ?: intent.getStringExtra("senderUid")
            )?.trim().orEmpty()

        val messageId = intent.getStringExtra("messageId")?.trim()?.takeIf { it.isNotBlank() }
        val type = intent.getStringExtra("type")?.trim() ?: "chat_message"

        if (convId.isNotBlank() || otherId.isNotBlank()) {
            val resolvedOtherId = if (otherId.isNotBlank()) {
                otherId
            } else if (!currentUserId.isNullOrBlank() && convId.contains("_")) {
                convId.removePrefix("${currentUserId}_").removeSuffix("_$currentUserId")
            } else {
                ""
            }

            val resolvedConvId = if (convId.isNotBlank()) {
                convId
            } else if (!currentUserId.isNullOrBlank() && resolvedOtherId.isNotBlank()) {
                buildDeterministicConversationId(currentUserId, resolvedOtherId)
            } else {
                ""
            }

            if (resolvedConvId.isNotBlank() || resolvedOtherId.isNotBlank()) {
                val target = PendingNotificationTarget(
                    conversationId = resolvedConvId,
                    otherUserId = resolvedOtherId,
                    messageId = messageId,
                    type = type
                )
                _pendingTarget.value = target
                BitchatLog.fcmNotificationTap(resolvedConvId, resolvedOtherId, messageId)

                // Clean up intent extras so recreation doesn't re-trigger unintentionally
                intent.removeExtra(BitchatNotificationManager.EXTRA_CONVERSATION_ID)
                intent.removeExtra(BitchatNotificationManager.EXTRA_CONVERSATION_ID_KEY)
                intent.removeExtra(BitchatNotificationManager.EXTRA_OTHER_USER_ID)
                intent.removeExtra(BitchatNotificationManager.EXTRA_SENDER_UID)
                intent.removeExtra("chatId")
                intent.removeExtra("conversationId")
                intent.removeExtra("senderId")
                intent.removeExtra("senderUid")
                intent.removeExtra("messageId")

                return target
            }
        }
        return null
    }

    fun setTarget(target: PendingNotificationTarget?) {
        _pendingTarget.value = target
    }

    fun consumeTarget(): PendingNotificationTarget? {
        val current = _pendingTarget.value
        _pendingTarget.value = null
        return current
    }

    fun clear() {
        _pendingTarget.value = null
    }
}
