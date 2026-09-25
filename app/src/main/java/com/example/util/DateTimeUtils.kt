package com.example.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateTimeUtils {

    fun formatLastSeen(isOnline: Boolean, lastSeenTimestamp: Long): String {
        if (isOnline) {
            return "ONLINE"
        }
        if (lastSeenTimestamp <= 0L) {
            return "OFFLINE"
        }

        val now = System.currentTimeMillis()
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())

        val calendarNow = Calendar.getInstance().apply { timeInMillis = now }
        val calendarTarget = Calendar.getInstance().apply { timeInMillis = lastSeenTimestamp }

        val isSameDay = calendarNow.get(Calendar.YEAR) == calendarTarget.get(Calendar.YEAR) &&
                calendarNow.get(Calendar.DAY_OF_YEAR) == calendarTarget.get(Calendar.DAY_OF_YEAR)

        val isYesterday = calendarNow.get(Calendar.YEAR) == calendarTarget.get(Calendar.YEAR) &&
                calendarNow.get(Calendar.DAY_OF_YEAR) - calendarTarget.get(Calendar.DAY_OF_YEAR) == 1

        val timeString = timeFormat.format(Date(lastSeenTimestamp))

        return when {
            isSameDay -> "LAST SEEN TODAY AT $timeString"
            isYesterday -> "LAST SEEN YESTERDAY AT $timeString"
            else -> "LAST SEEN ${dateFormat.format(Date(lastSeenTimestamp)).uppercase()} AT $timeString"
        }
    }

    fun formatMessageTimestamp(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        return timeFormat.format(Date(timestamp))
    }

    fun formatConversationListTime(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        val now = System.currentTimeMillis()
        val calendarNow = Calendar.getInstance().apply { timeInMillis = now }
        val calendarTarget = Calendar.getInstance().apply { timeInMillis = timestamp }

        val isSameDay = calendarNow.get(Calendar.YEAR) == calendarTarget.get(Calendar.YEAR) &&
                calendarNow.get(Calendar.DAY_OF_YEAR) == calendarTarget.get(Calendar.DAY_OF_YEAR)

        val isYesterday = calendarNow.get(Calendar.YEAR) == calendarTarget.get(Calendar.YEAR) &&
                calendarNow.get(Calendar.DAY_OF_YEAR) - calendarTarget.get(Calendar.DAY_OF_YEAR) == 1

        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())

        return when {
            isSameDay -> timeFormat.format(Date(timestamp))
            isYesterday -> "YESTERDAY"
            else -> dateFormat.format(Date(timestamp))
        }
    }
}
