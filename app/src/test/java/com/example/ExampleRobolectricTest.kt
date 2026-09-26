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
import com.example.data.relay.GlobalRelayEngine
import com.example.data.repository.ChatRepository
import com.example.data.repository.UserRepository
import com.example.ui.home.HomeViewModel
import com.example.ui.profile.ProfileViewModel
import com.example.util.DateTimeUtils
import com.example.util.ImageUtils
import com.example.util.ThemeManager
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun `registration, duplicate check, login resilience, and profile seed update`() = runBlocking {
        val userDao = database.userDao()
        val relayEngine = GlobalRelayEngine(
            userDao = userDao,
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao()
        )
        val userRepo = UserRepository(
            userDao = userDao,
            context = context,
            firestoreSyncManager = null,
            relayEngine = relayEngine
        )
        RealtimeManager.setNetworkConnected(true)
        val regResult = userRepo.registerUser("avatar_tester", "Avatar Tester", "pass1234")
        assertTrue("Registration must succeed without backend error", regResult.isSuccess)
        val registeredUser = regResult.getOrThrow()

        // Duplicate registration must be rejected
        val dupResult = userRepo.registerUser("avatar_tester", "Another User", "pass1234")
        assertTrue("Duplicate username must be rejected", dupResult.isFailure)

        // Logout and login with correct and incorrect passwords
        userRepo.logout()
        val wrongLogin = userRepo.loginUser("avatar_tester", "wrong_pass")
        assertTrue("Wrong password must fail", wrongLogin.isFailure)

        val validLogin = userRepo.loginUser("avatar_tester", "pass1234")
        assertTrue("Valid password login must succeed", validLogin.isSuccess)

        val themeManager = ThemeManager(context)
        val profileVm = ProfileViewModel(userRepo, themeManager)

        profileVm.onAvatarSelect("CYBER")
        assertEquals("CYBER", profileVm.uiState.value.selectedAvatar)
        userRepo.updateProfile(registeredUser.displayName, registeredUser.statusMessage, "CYBER")
        val updated = userDao.getUserByIdDirect(registeredUser.id)
        assertEquals("CYBER", updated?.avatarSeed)
        relayEngine.stop()
    }

    @Test
    fun `private user discovery and per-user chat deletion preserve peer account and history`() = runBlocking {
        val userDao = database.userDao()
        val convDao = database.conversationDao()
        val msgDao = database.messageDao()

        val relayEngine = GlobalRelayEngine(
            userDao = userDao,
            conversationDao = convDao,
            messageDao = msgDao
        )
        val userRepo = UserRepository(
            userDao = userDao,
            context = context,
            firestoreSyncManager = null,
            relayEngine = relayEngine
        )
        val chatRepo = ChatRepository(
            conversationDao = convDao,
            messageDao = msgDao,
            userDao = userDao,
            firestoreSyncManager = null,
            relayEngine = relayEngine,
            appContext = context
        )

        // Create @brutt, @abin, @avatar_tester
        val brutt = userRepo.registerUser("brutt", "Brutt", "pass1234").getOrThrow()
        val abin = userRepo.registerUser("abin", "Abin", "pass1234").getOrThrow()
        val avatarTester = userRepo.registerUser("avatar_tester", "Avatar Tester", "pass1234").getOrThrow()

        // Switch active session to @brutt
        userRepo.switchUser(brutt.id)
        assertEquals(brutt.id, userRepo.currentUserId.value)

        // 1. Discovery screen initially shows NO users when query is blank
        val initialDiscovery = userRepo.searchUsersInBackend("", brutt.id).getOrThrow()
        assertTrue("Discovery must not expose any users when search query is blank", initialDiscovery.isEmpty())
        val whitespaceDiscovery = userRepo.searchUsersInBackend("   @   ", brutt.id).getOrThrow()
        assertTrue("Discovery must not expose users for @/whitespace query", whitespaceDiscovery.isEmpty())

        // 2. Search "abin", "@abin", "ABIN" -> only @abin appears
        val searchAbinLower = userRepo.searchUsersInBackend("abin", brutt.id).getOrThrow()
        assertEquals(1, searchAbinLower.size)
        assertEquals("abin", searchAbinLower.first().usernameNormalized)

        val searchAbinAt = userRepo.searchUsersInBackend("  @abin  ", brutt.id).getOrThrow()
        assertEquals(1, searchAbinAt.size)
        assertEquals("abin", searchAbinAt.first().usernameNormalized)

        val searchAbinUpper = userRepo.searchUsersInBackend("ABIN", brutt.id).getOrThrow()
        assertEquals(1, searchAbinUpper.size)
        assertEquals("abin", searchAbinUpper.first().usernameNormalized)

        // 3. Search "avatar_tester" -> only @avatar_tester appears
        val searchAvatarTester = userRepo.searchUsersInBackend("avatar_tester", brutt.id).getOrThrow()
        assertEquals(1, searchAvatarTester.size)
        assertEquals("avatar_tester", searchAvatarTester.first().usernameNormalized)

        // 4. Search "xyz" / "@doesnotexist" -> USER NOT FOUND (empty list)
        val searchXyz = userRepo.searchUsersInBackend("xyz", brutt.id).getOrThrow()
        assertTrue("Non-existent username must return empty list", searchXyz.isEmpty())
        val searchDoesNotExist = userRepo.searchUsersInBackend("@doesnotexist", brutt.id).getOrThrow()
        assertTrue("Non-existent @username must return empty list", searchDoesNotExist.isEmpty())

        // 5. Create conversations: @brutt <-> @abin AND @abin <-> @avatar_tester
        val convBruttAbin = chatRepo.getOrCreateConversation(brutt.id, abin.id)
        chatRepo.sendMessage(convBruttAbin, brutt.id, abin.id, "Hello Abin from Brutt")
        chatRepo.sendMessage(convBruttAbin, abin.id, brutt.id, "Hey Brutt!")

        val convAbinTester = chatRepo.getOrCreateConversation(abin.id, avatarTester.id)
        chatRepo.sendMessage(convAbinTester, abin.id, avatarTester.id, "Hi Avatar Tester")

        val homeVm = HomeViewModel(userRepo, chatRepo, userDao)
        val bruttConvsBefore = chatRepo.getConversationsForUser(brutt.id).first()
        assertEquals(1, bruttConvsBefore.size)
        assertEquals(convBruttAbin, bruttConvsBefore.first().id)

        // 6. Long press @abin -> opens DELETE CHAT confirmation, Tap CANCEL -> chat remains
        val enrichedItem = com.example.ui.home.EnrichedConversation(
            conversation = bruttConvsBefore.first(),
            otherUser = abin,
            unreadCount = 0,
            isOtherUserTyping = false
        )
        homeVm.requestDeleteConversation(enrichedItem)
        assertNotNull("Confirmation dialog state must be active after long press", homeVm.uiState.value.conversationPendingDeletion)
        homeVm.cancelDeleteConversation()
        assertNull("Confirmation dialog must close on CANCEL", homeVm.uiState.value.conversationPendingDeletion)
        assertEquals(1, chatRepo.getConversationsForUser(brutt.id).first().size)

        // 7. Security check: @brutt cannot delete @abin's private conversation with @avatar_tester
        val unauthorizedDelete = chatRepo.deleteConversationForUser(convAbinTester, brutt.id)
        assertTrue("Unauthorized deletion of another user's chat must be rejected", unauthorizedDelete.isFailure)

        // 8. Long press again -> confirm DELETE for @brutt
        val deleteResult = chatRepo.deleteConversationForUser(convBruttAbin, brutt.id)
        assertTrue("Authorized per-user chat deletion must succeed", deleteResult.isSuccess)

        // @abin disappears from @brutt's CHATS immediately
        val bruttConvsAfterDelete = chatRepo.getConversationsForUser(brutt.id).first()
        assertTrue("@abin conversation must be removed from @brutt's CHATS", bruttConvsAfterDelete.isEmpty())

        // @brutt's visible messages in that conversation are now empty
        val bruttVisibleMessages = chatRepo.getMessagesForConversation(convBruttAbin, brutt.id).first()
        assertTrue("@brutt's visible chat history must be cleared", bruttVisibleMessages.isEmpty())

        // @abin's account still exists intact
        val abinAccount = userDao.getUserByIdDirect(abin.id)
        assertNotNull("@abin account must still exist", abinAccount)
        assertEquals("abin", abinAccount?.usernameNormalized)

        // @abin still sees the conversation with @brutt AND all messages
        val abinConvs = chatRepo.getConversationsForUser(abin.id).first()
        assertEquals("@abin must still see both conversations", 2, abinConvs.size)
        val abinVisibleMessages = chatRepo.getMessagesForConversation(convBruttAbin, abin.id).first()
        assertEquals("@abin must still see all 2 messages", 2, abinVisibleMessages.size)

        // @abin's other conversation with @avatar_tester remains unaffected
        val abinTesterMessages = chatRepo.getMessagesForConversation(convAbinTester, abin.id).first()
        assertEquals(1, abinTesterMessages.size)

        // 9. If @brutt starts a new conversation / sends a new message to @abin again, it becomes visible again for @brutt
        chatRepo.sendMessage(convBruttAbin, brutt.id, abin.id, "Starting fresh after delete!")
        val bruttConvsAfterNewMsg = chatRepo.getConversationsForUser(brutt.id).first()
        assertEquals("Conversation must reappear for @brutt after sending a new message", 1, bruttConvsAfterNewMsg.size)

        val bruttMessagesAfterNewMsg = chatRepo.getMessagesForConversation(convBruttAbin, brutt.id).first()
        assertEquals("Only the new message after deletion should appear for @brutt", 1, bruttMessagesAfterNewMsg.size)
        assertEquals("Starting fresh after delete!", bruttMessagesAfterNewMsg.first().content)

        // Meanwhile @abin sees all 3 messages
        val abinMessagesAfterNewMsg = chatRepo.getMessagesForConversation(convBruttAbin, abin.id).first()
        assertEquals(3, abinMessagesAfterNewMsg.size)

        chatRepo.flushPendingMessages(convBruttAbin)
        chatRepo.flushPendingMessages(convAbinTester)
        relayEngine.stop()
    }

    @Test
    fun `chat header recipient real profile loading, online status, and last seen formatting`() = runBlocking {
        val userDao = database.userDao()
        val convDao = database.conversationDao()
        val msgDao = database.messageDao()

        val relayEngine = GlobalRelayEngine(
            userDao = userDao,
            conversationDao = convDao,
            messageDao = msgDao
        )
        val userRepo = UserRepository(
            userDao = userDao,
            context = context,
            firestoreSyncManager = null,
            relayEngine = relayEngine
        )
        val chatRepo = ChatRepository(
            conversationDao = convDao,
            messageDao = msgDao,
            userDao = userDao,
            firestoreSyncManager = null,
            relayEngine = relayEngine,
            appContext = context
        )

        // 1. Create recipient @abhi with real display name "Abhinav"
        val abhi = userRepo.registerUser("abhi", "Abhinav", "pass1234").getOrThrow()
        assertEquals("Abhinav", abhi.displayName)
        assertEquals("abhi", abhi.usernameNormalized)

        // 2. Create sender @brutt
        val brutt = userRepo.registerUser("brutt_sender", "Brutt", "pass1234").getOrThrow()
        userRepo.switchUser(brutt.id)

        // 3. Open conversation with recipient UID
        val convId = chatRepo.getOrCreateConversation(brutt.id, abhi.id)
        val chatVm = com.example.ui.chat.ChatViewModel(
            conversationId = convId,
            otherUserId = abhi.id,
            chatRepository = chatRepo,
            userRepository = userRepo,
            userDao = userDao
        )

        // Verify recipient profile resolves to real display name "Abhinav" (never "Loading..." as permanent name or "User xxxxx")
        val fetchedResult = userRepo.fetchAndCacheUserById(abhi.id)
        assertTrue("Profile fetch must succeed", fetchedResult.isSuccess)
        val resolvedPeer = userDao.getUserById(abhi.id).first()
        assertNotNull("Recipient profile must be resolved in database", resolvedPeer)
        assertEquals("Abhinav", resolvedPeer?.displayName)
        assertFalse("Never generate fallback 'User ' display name", resolvedPeer?.displayName?.startsWith("User ") == true)

        // 4. Test real-time presence formatting
        val now = System.currentTimeMillis()
        val onlineText = DateTimeUtils.formatLastSeen(isOnline = true, lastSeenTimestamp = now, now = now)
        assertEquals("ONLINE", onlineText)

        // Offline today (e.g. 10 minutes ago)
        val tenMinAgo = now - 10 * 60 * 1000L
        val todayOfflineText = DateTimeUtils.formatLastSeen(isOnline = false, lastSeenTimestamp = tenMinAgo, now = now).uppercase()
        assertTrue("Must format as LAST SEEN TODAY AT [time]", todayOfflineText.startsWith("LAST SEEN TODAY AT"))

        // Offline yesterday
        val yesterday = now - 24 * 60 * 60 * 1000L
        val yesterdayOfflineText = DateTimeUtils.formatLastSeen(isOnline = false, lastSeenTimestamp = yesterday, now = now).uppercase()
        assertTrue("Must format as LAST SEEN YESTERDAY AT [time]", yesterdayOfflineText.startsWith("LAST SEEN YESTERDAY AT"))

        relayEngine.stop()
    }
}

