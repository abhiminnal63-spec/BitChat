package com.example.util

import android.util.Log

/**
 * Standardized logging utility for BITCHAT lifecycle, messaging, auth, and presence events.
 * Note: Never logs passwords, auth tokens, private message contents, or sensitive credentials.
 */
object BitchatLog {
    private const val TAG = "BITCHAT"

    fun authState(state: String, uid: String? = null) {
        Log.i(TAG, "[AUTH_STATE] state=$state uid=${uid ?: "null"}")
    }

    fun fcmReceived(messageId: String, conversationId: String, senderId: String) {
        Log.i(TAG, "[FCM_RECEIVED] messageId=$messageId conversationId=$conversationId senderId=$senderId")
    }

    fun fcmNotificationTap(conversationId: String, otherUserId: String, messageId: String? = null) {
        Log.i(TAG, "[FCM_NOTIFICATION_TAP] conversationId=$conversationId otherUserId=$otherUserId messageId=$messageId")
    }

    fun chatNavigation(screen: String, conversationId: String? = null, otherUserId: String? = null) {
        Log.i(TAG, "[CHAT_NAVIGATION] screen=$screen conversationId=$conversationId otherUserId=$otherUserId")
    }

    fun messageSendStart(messageId: String, conversationId: String, recipientId: String) {
        Log.i(TAG, "[MESSAGE_SEND_START] messageId=$messageId conversationId=$conversationId recipientId=$recipientId")
    }

    fun messageSendSuccess(messageId: String, conversationId: String) {
        Log.i(TAG, "[MESSAGE_SEND_SUCCESS] messageId=$messageId conversationId=$conversationId")
    }

    fun messageSendFailure(messageId: String, conversationId: String, reason: String? = null) {
        Log.w(TAG, "[MESSAGE_SEND_FAILURE] messageId=$messageId conversationId=$conversationId reason=${reason ?: "unknown"}")
    }

    fun messageRetry(messageId: String, conversationId: String) {
        Log.i(TAG, "[MESSAGE_RETRY] messageId=$messageId conversationId=$conversationId")
    }

    fun messageDelivered(messageId: String, conversationId: String, recipientId: String) {
        Log.i(TAG, "[MESSAGE_DELIVERED] messageId=$messageId conversationId=$conversationId recipientId=$recipientId")
    }

    fun messageSeen(messageId: String, conversationId: String, readerId: String) {
        Log.i(TAG, "[MESSAGE_SEEN] messageId=$messageId conversationId=$conversationId readerId=$readerId")
    }

    fun presenceOnline(uid: String) {
        Log.i(TAG, "[PRESENCE_ONLINE] uid=$uid")
    }

    fun presenceOffline(uid: String) {
        Log.i(TAG, "[PRESENCE_OFFLINE] uid=$uid")
    }
}
