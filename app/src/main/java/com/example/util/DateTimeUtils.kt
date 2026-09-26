package com.example.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateTimeUtils {
    const val PRESENCE_FRESHNESS_MS = 90_000L

    private val timeFormatLocal = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    }

    private val dateFormatLocal = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
    }

    private val calendarNowLocal = object : ThreadLocal<Calendar>() {
        override fun initialValue(): Calendar = Calendar.getInstance()
    }

    private val calendarTargetLocal = object : ThreadLocal<Calendar>() {
        override fun initialValue(): Calendar = Calendar.getInstance()
    }

    fun isEffectivelyOnline(
        isOnline: Boolean,
        lastSeenTimestamp: Long,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        return isOnline && lastSeenTimestamp > 0L && (now - lastSeenTimestamp) < PRESENCE_FRESHNESS_MS
    }

    fun formatLastSeen(
        isOnline: Boolean,
        lastSeenTimestamp: Long,
        now: Long = System.currentTimeMillis()
    ): String {
        if (isEffectivelyOnline(isOnline, lastSeenTimestamp, now)) {
            return "ONLINE"
        }
        if (lastSeenTimestamp <= 0L) {
            return "OFFLINE"
        }

        val clampedLastSeen = if (lastSeenTimestamp > now) now else lastSeenTimestamp
        val diffMs = now - clampedLastSeen
        val timeFormat = timeFormatLocal.get() ?: SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateFormat = dateFormatLocal.get() ?: SimpleDateFormat("MMM d", Locale.getDefault())

        val calendarNow = (calendarNowLocal.get() ?: Calendar.getInstance()).apply { timeInMillis = now }
        val calendarTarget = (calendarTargetLocal.get() ?: Calendar.getInstance()).apply { timeInMillis = clampedLastSeen }

        val isSameDay = calendarNow.get(Calendar.YEAR) == calendarTarget.get(Calendar.YEAR) &&
                calendarNow.get(Calendar.DAY_OF_YEAR) == calendarTarget.get(Calendar.DAY_OF_YEAR)

        val isYesterday = (calendarNow.get(Calendar.YEAR) == calendarTarget.get(Calendar.YEAR) &&
                calendarNow.get(Calendar.DAY_OF_YEAR) - calendarTarget.get(Calendar.DAY_OF_YEAR) == 1) ||
                (calendarNow.get(Calendar.YEAR) - calendarTarget.get(Calendar.YEAR) == 1 &&
                        calendarNow.get(Calendar.DAY_OF_YEAR) == 1 &&
                        calendarTarget.get(Calendar.DAY_OF_YEAR) >= 365)

        val dateObj = Date(clampedLastSeen)
        val timeString = timeFormat.format(dateObj)
        val minutesAgo = (diffMs / 60_000L).toInt()

        return when {
            diffMs < 60_000L && isSameDay -> "Last seen just now"
            minutesAgo in 1..59 && isSameDay -> "Last seen $minutesAgo min ago ($timeString)"
            isSameDay -> "Last seen today at $timeString"
            isYesterday -> "Last seen yesterday at $timeString"
            else -> "Last seen ${dateFormat.format(dateObj)} at $timeString"
        }
    }

    fun formatMessageTimestamp(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        val timeFormat = timeFormatLocal.get() ?: SimpleDateFormat("h:mm a", Locale.getDefault())
        return timeFormat.format(Date(timestamp))
    }

    fun formatConversationListTime(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        val now = System.currentTimeMillis()
        val calendarNow = (calendarNowLocal.get() ?: Calendar.getInstance()).apply { timeInMillis = now }
        val calendarTarget = (calendarTargetLocal.get() ?: Calendar.getInstance()).apply { timeInMillis = timestamp }

        val isSameDay = calendarNow.get(Calendar.YEAR) == calendarTarget.get(Calendar.YEAR) &&
                calendarNow.get(Calendar.DAY_OF_YEAR) == calendarTarget.get(Calendar.DAY_OF_YEAR)

        val isYesterday = calendarNow.get(Calendar.YEAR) == calendarTarget.get(Calendar.YEAR) &&
                calendarNow.get(Calendar.DAY_OF_YEAR) - calendarTarget.get(Calendar.DAY_OF_YEAR) == 1

        val timeFormat = timeFormatLocal.get() ?: SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateFormat = dateFormatLocal.get() ?: SimpleDateFormat("MMM d", Locale.getDefault())
        val dateObj = Date(timestamp)

        return when {
            isSameDay -> timeFormat.format(dateObj)
            isYesterday -> "YESTERDAY"
            else -> dateFormat.format(dateObj)
        }
    }
}
