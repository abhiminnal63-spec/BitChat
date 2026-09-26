package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.EasappDatabase
import com.example.data.model.MessageEntity
import com.example.data.model.MessageStatus
import com.example.data.model.UserEntity
import com.example.data.model.buildDeterministicConversationId
import com.example.data.model.mergeMessageStatus
import com.example.data.realtime.RealtimeManager
import com.example.data.repository.ChatRepository
import com.example.util.DateTimeUtils
import com.example.util.ImageUtils
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var database: EasappDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, EasappDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        RealtimeManager.setNetworkConnected(true)
    }

    @After
    fun tearDown() {
        database.close()
        RealtimeManager.setNetworkConnected(true)
    }

    @Test
    fun `read string from context`() {
        val appName = context.getString(R.string.app_name)
        assertEquals("Easapp", appName)
    }

    @Test
    fun `120 rapid consecutive messages preserve all messages in strict order with unique IDs`() = runBlocking {
        val userDao = database.userDao()
        val convDao = database.conversationDao()
        val msgDao = database.messageDao()

        val uidA = "usr_alice_111"
        val uidB = "usr_bob_222"
        userDao.insertUser(UserEntity(id = uidA, username = "alice", displayName = "Alice"))
        userDao.insertUser(UserEntity(id = uidB, username = "bob", displayName = "Bob"))

        val chatRepository = ChatRepository(
            conversationDao = convDao,
            messageDao = msgDao,
            userDao = userDao,
            firestoreSyncManager = null,
            relayEngine = null,
            appContext = context
        )

        val convId = chatRepository.getOrCreateConversation(uidA, uidB)
        assertEquals(buildDeterministicConversationId(uidA, uidB), convId)

        val sentIds = mutableSetOf<String>()
        for (i in 1..120) {
            val sender = if (i % 2 == 1) uidA else uidB
            val receiver = if (i % 2 == 1) uidB else uidA
            val msg = chatRepository.sendMessage(
                conversationId = convId,
                senderId = sender,
                recipientId = receiver,
                content = "Message #$i"
            )
            assertTrue("Message ID must be unique", sentIds.add(msg.id))
            assertEquals("text", msg.type)
        }

        val stored = msgDao.getMessagesForConversation(convId).first()
        assertEquals(120, stored.size)

        // Verify strict chronological ordering and content integrity across all 120 messages
        for (i in 0 until stored.size) {
            assertEquals("Message #${i + 1}", stored[i].content)
            if (i > 0) {
                assertTrue(
                    "Timestamps must be strictly non-decreasing",
                    stored[i].timestamp >= stored[i - 1].timestamp
                )
            }
        }

        chatRepository.flushPendingMessages(convId)
    }

    @Test
    fun `idempotent upsert never downgrades message status or overwrites mediaUrl with blank`() = runBlocking {
        val msgDao = database.messageDao()
        val initial = MessageEntity(
            id = "msg_test_1",
            conversationId = "u1_u2",
            senderId = "u1",
            recipientId = "u2",
            content = "Photo caption",
            timestamp = 1000L,
            status = MessageStatus.READ.name,
            attachmentUri = "data:image/jpeg;base64,LOCAL_HIGH_RES",
            type = "image",
            mediaUrl = "https://ntfy.sh/file/cloud_media.jpg"
        )
        msgDao.upsertMessageSafely(initial)

        // Simulate a stale historical cloud poll returning status = SENT and no local attachment
        val stalePoll = initial.copy(
            status = MessageStatus.SENT.name,
            attachmentUri = null,
            mediaUrl = null
        )
        msgDao.upsertMessageSafely(stalePoll)

        val afterStale = msgDao.getMessageByIdDirect("msg_test_1")
        assertNotNull(afterStale)
        assertEquals(MessageStatus.READ.name, afterStale?.status)
        assertEquals("https://ntfy.sh/file/cloud_media.jpg", afterStale?.mediaUrl)
        assertEquals("data:image/jpeg;base64,LOCAL_HIGH_RES", afterStale?.attachmentUri)
        assertEquals("READ", mergeMessageStatus("READ", "SENT"))
        assertEquals("FAILED", mergeMessageStatus("SENDING", "FAILED"))
        assertEquals("SENDING", mergeMessageStatus("FAILED", "SENDING"))
    }

    @Test
    fun `presence and lastSeen never regress from stale historical payloads`() = runBlocking {
        val userDao = database.userDao()
        val now = System.currentTimeMillis()
        val freshUser = UserEntity(
            id = "usr_1",
            username = "brutt",
            displayName = "Brutt",
            isOnline = true,
            lastSeenTimestamp = now
        )
        userDao.upsertRemoteUser(freshUser, allowPresenceUpdate = true)

        // Simulate an old message payload carrying a 2-hour-old embedded sender snapshot
        val twoHoursAgo = now - 2 * 3600 * 1000L
        val staleEmbeddedProfile = freshUser.copy(
            isOnline = false,
            lastSeenTimestamp = twoHoursAgo
        )
        userDao.upsertRemoteUser(staleEmbeddedProfile, allowPresenceUpdate = false)

        val stored = userDao.getUserByIdDirect("usr_1")
        assertNotNull(stored)
        assertTrue("Online status must not be overwritten by historical message snapshot", stored!!.isOnline)
        assertEquals("Last seen timestamp must not move backward", now, stored.lastSeenTimestamp)

        // Formatting verification
        assertEquals("ONLINE", DateTimeUtils.formatLastSeen(isOnline = true, lastSeenTimestamp = now, now = now + 10_000L))
        assertEquals("Last seen just now", DateTimeUtils.formatLastSeen(isOnline = false, lastSeenTimestamp = now, now = now + 20_000L))
        assertTrue(
            DateTimeUtils.formatLastSeen(isOnline = false, lastSeenTimestamp = now - 5 * 60_000L, now = now)
                .startsWith("Last seen 5 min ago")
        )
    }

    @Test
    fun `image encoding and decoding produces valid non-black bitmap and compact relay URI`() = runBlocking {
        val bmp = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(240, 80, 40))

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val jpegBytes = out.toByteArray()

        val compactDataUri = ImageUtils.createCompactRelayDataUri(jpegBytes)
        assertNotNull(compactDataUri)
        assertTrue(compactDataUri!!.startsWith("data:image/jpeg;base64,"))
        assertTrue("Compact relay URI must fit inside 4KB relay payload limit", compactDataUri.length < 2800)

        val decoded = ImageUtils.loadBitmapFromSource(context, compactDataUri)
        assertNotNull("Decoded bitmap must not be null", decoded)
        assertTrue(decoded!!.width > 0 && decoded.height > 0)

        val centerPixel = decoded.getPixel(decoded.width / 2, decoded.height / 2)
        assertTrue("Decoded image pixel must not be pure black", Color.red(centerPixel) > 150)
    }
}

