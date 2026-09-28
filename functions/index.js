const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const admin = require("firebase-admin");

admin.initializeApp();
const db = admin.firestore();

/**
 * Helper to resolve recipient FCM tokens from:
 * 1. `users/{receiverId}/devices/{deviceId}` subcollection
 * 2. `users/{receiverId}.fcmToken` top-level field
 * 3. `explicitTokens` passed in the payload
 *
 * Dispatches a high-priority DATA-ONLY FCM multicast message (`android.priority = "high"`)
 * so Android ALWAYS invokes `BitchatMessagingService.onMessageReceived()` whether the app
 * is in the foreground, in the background, on the home screen, or on the lock screen.
 */
async function dispatchBitchatDataPush({
  messageId,
  conversationId,
  senderId,
  receiverId,
  senderName,
  type,
  rawText,
  mediaUrl,
  createdAt,
  explicitTokens
}) {
  if (!senderId || !receiverId || senderId === receiverId) {
    return { status: "skipped_invalid_participants", deliveredCount: 0, failureCount: 0 };
  }

  let resolvedSenderName = senderName || "";
  if (!resolvedSenderName || resolvedSenderName === "Contact") {
    try {
      const senderDoc = await db.collection("users").doc(senderId).get();
      if (senderDoc.exists) {
        const sData = senderDoc.data() || {};
        resolvedSenderName = sData.displayName || sData.username || "Contact";
      }
    } catch (_) {}
  }
  if (!resolvedSenderName) resolvedSenderName = "Contact";

  const normalizedType = (type || "text").toLowerCase();
  const notificationBody =
    normalizedType === "image" ? "📷 Photo" : (rawText || "").trim() || "New message";

  const tokenToDeviceId = new Map();
  let receiverTopLevelToken = null;

  // 1. Read top-level users/{receiverId}.fcmToken
  try {
    const receiverDoc = await db.collection("users").doc(receiverId).get();
    if (receiverDoc.exists) {
      const rData = receiverDoc.data() || {};
      if (typeof rData.fcmToken === "string" && rData.fcmToken.trim()) {
        receiverTopLevelToken = rData.fcmToken.trim();
        tokenToDeviceId.set(receiverTopLevelToken, "__TOP_LEVEL__");
      }
    }
  } catch (_) {}

  // 2. Read all registered Android devices under users/{receiverId}/devices
  try {
    const devicesSnap = await db
      .collection("users")
      .doc(receiverId)
      .collection("devices")
      .get();

    devicesSnap.forEach((doc) => {
      const d = doc.data() || {};
      if (d.fcmToken && typeof d.fcmToken === "string" && d.fcmToken.trim()) {
        tokenToDeviceId.set(d.fcmToken.trim(), doc.id);
      }
    });
  } catch (_) {}

  // 3. Merge any explicit tokens provided in the trigger payload
  if (Array.isArray(explicitTokens)) {
    explicitTokens.forEach((token) => {
      if (typeof token === "string" && token.trim() && !tokenToDeviceId.has(token.trim())) {
        tokenToDeviceId.set(token.trim(), null);
      }
    });
  }

  const tokens = Array.from(tokenToDeviceId.keys());
  if (tokens.length === 0) {
    return { status: "no_registered_devices", deliveredCount: 0, failureCount: 0 };
  }

  const collapseTag = `bitchat_conv_${conversationId}`;

  // IMPORTANT: Use a high-priority DATA-ONLY payload (no top-level `notification` object).
  // Including a `notification` block causes Android OS to intercept the message in the system tray
  // when backgrounded/locked, bypassing BitchatMessagingService.onMessageReceived(), Room DB persistence,
  // DELIVERED receipts, Direct Reply actions, and conversation deep-link extras.
  const multicastMessage = {
    tokens,
    data: {
      messageId: String(messageId),
      conversationId: String(conversationId),
      senderId: String(senderId),
      senderUid: String(senderId),
      receiverId: String(receiverId),
      senderName: String(resolvedSenderName),
      title: String(resolvedSenderName),
      body: String(notificationBody),
      type: String(normalizedType),
      text: String(rawText || ""),
      mediaUrl: String(mediaUrl || ""),
      createdAt: String(createdAt || Date.now()),
      channelId: "messages",
      collapseKey: collapseTag,
      notificationTag: collapseTag,
      groupKey: "com.example.bitchat.MESSAGE_GROUP"
    },
    android: {
      priority: "high",
      collapseKey: collapseTag,
      ttl: 86400 * 1000
    }
  };

  const response = await admin.messaging().sendEachForMulticast(multicastMessage);

  // Clean up any unregistered/expired FCM device tokens automatically
  const cleanupPromises = [];
  response.responses.forEach((res, idx) => {
    if (!res.success && res.error) {
      const code = res.error.code || "";
      if (
        code === "messaging/registration-token-not-registered" ||
        code === "messaging/invalid-registration-token" ||
        code === "messaging/invalid-argument"
      ) {
        const badToken = tokens[idx];
        const devId = tokenToDeviceId.get(badToken);
        if (devId && devId !== "__TOP_LEVEL__") {
          cleanupPromises.push(
            db
              .collection("users")
              .doc(receiverId)
              .collection("devices")
              .doc(devId)
              .delete()
          );
        }
        if (receiverTopLevelToken && badToken === receiverTopLevelToken) {
          cleanupPromises.push(
            db
              .collection("users")
              .doc(receiverId)
              .set({ fcmToken: "" }, { merge: true })
          );
        }
      }
    }
  });
  await Promise.all(cleanupPromises);

  return {
    status: "sent",
    deliveredCount: response.successCount,
    failureCount: response.failureCount
  };
}

/**
 * Trigger 1: Explicit push queue (`push_notifications/{messageId}`).
 */
exports.sendBitchatMessagePush = onDocumentCreated(
  "push_notifications/{messageId}",
  async (event) => {
    const snap = event.data;
    if (!snap) return;

    const payload = snap.data() || {};
    if (payload.status === "sent") return;

    const messageId = payload.messageId || event.params.messageId;
    const result = await dispatchBitchatDataPush({
      messageId,
      conversationId: payload.conversationId || "",
      senderId: payload.senderId || "",
      receiverId: payload.receiverId || "",
      senderName: payload.senderName || "",
      type: payload.type || "text",
      rawText: payload.text || "",
      mediaUrl: payload.mediaUrl || "",
      createdAt: payload.createdAt || Date.now(),
      explicitTokens: payload.fcmTokens || []
    });

    await snap.ref.set(
      {
        status: result.status,
        deliveredCount: result.deliveredCount,
        failureCount: result.failureCount,
        processedAt: Date.now()
      },
      { merge: true }
    );
  }
);

/**
 * Trigger 2: Automatic fallback on `conversations/{conversationId}/messages/{messageId}`
 * so even if the sender goes offline before enqueuing `push_notifications/{messageId}`,
 * Firestore server-side creation still dispatches the FCM push notification once.
 */
exports.onConversationMessageCreated = onDocumentCreated(
  "conversations/{conversationId}/messages/{messageId}",
  async (event) => {
    const snap = event.data;
    if (!snap) return;

    const msg = snap.data() || {};
    const messageId = msg.messageId || msg.id || event.params.messageId;
    const conversationId = msg.conversationId || event.params.conversationId;
    const senderId = msg.senderId || "";
    const receiverId = msg.receiverId || msg.recipientId || "";

    if (!senderId || !receiverId || senderId === receiverId) return;

    const pushRef = db.collection("push_notifications").doc(messageId);
    const existingPush = await pushRef.get();
    if (existingPush.exists && existingPush.data()?.status === "sent") {
      return;
    }

    const result = await dispatchBitchatDataPush({
      messageId,
      conversationId,
      senderId,
      receiverId,
      senderName: msg.senderName || "",
      type: msg.type || "text",
      rawText: msg.text || msg.content || "",
      mediaUrl: msg.mediaUrl || "",
      createdAt: msg.createdAt || msg.timestamp || Date.now(),
      explicitTokens: []
    });

    await pushRef.set(
      {
        messageId,
        conversationId,
        senderId,
        receiverId,
        status: result.status,
        deliveredCount: result.deliveredCount,
        failureCount: result.failureCount,
        processedAt: Date.now()
      },
      { merge: true }
    );
  }
);
