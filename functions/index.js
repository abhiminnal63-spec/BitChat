const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const admin = require("firebase-admin");

admin.initializeApp();
const db = admin.firestore();

/**
 * Triggered when a push notification document is queued in `push_notifications/{messageId}`
 * after the message has already been permanently stored in Firestore.
 * Retrieves all registered devices under `users/{receiverId}/devices/{deviceId}`
 * and dispatches a high-priority FCM push notification so Android displays it
 * even when BITCHAT is minimized, on the lock screen, or completely closed.
 */
exports.sendBitchatMessagePush = onDocumentCreated(
  "push_notifications/{messageId}",
  async (event) => {
    const snap = event.data;
    if (!snap) return;

    const payload = snap.data() || {};
    const messageId = payload.messageId || event.params.messageId;
    const conversationId = payload.conversationId || "";
    const senderId = payload.senderId || "";
    const receiverId = payload.receiverId || "";
    const type = (payload.type || "text").toLowerCase();
    const rawText = payload.text || "";

    if (!senderId || !receiverId || senderId === receiverId) {
      return;
    }

    // Resolve sender display name from Firestore users/{senderId} if needed
    let senderName = payload.senderName || "";
    if (!senderName || senderName === "Contact") {
      try {
        const senderDoc = await db.collection("users").doc(senderId).get();
        if (senderDoc.exists) {
          const sData = senderDoc.data() || {};
          senderName = sData.displayName || sData.username || "Contact";
        }
      } catch (_) {}
    }
    if (!senderName) senderName = "Contact";

    const notificationBody =
      type === "image" ? "📷 Photo" : rawText.trim() || "New message";

    // Retrieve all registered Android devices for the recipient: users/{receiverId}/devices
    const devicesSnap = await db
      .collection("users")
      .doc(receiverId)
      .collection("devices")
      .get();

    const tokenToDeviceId = new Map();
    devicesSnap.forEach((doc) => {
      const d = doc.data() || {};
      if (d.fcmToken && typeof d.fcmToken === "string" && d.fcmToken.trim()) {
        tokenToDeviceId.set(d.fcmToken.trim(), doc.id);
      }
    });

    if (Array.isArray(payload.fcmTokens)) {
      payload.fcmTokens.forEach((token) => {
        if (typeof token === "string" && token.trim() && !tokenToDeviceId.has(token.trim())) {
          tokenToDeviceId.set(token.trim(), null);
        }
      });
    }

    const tokens = Array.from(tokenToDeviceId.keys());
    if (tokens.length === 0) {
      await snap.ref.set({ status: "no_registered_devices" }, { merge: true });
      return;
    }

    const collapseTag = `bitchat_conv_${conversationId}`;
    const multicastMessage = {
      tokens,
      data: {
        messageId: String(messageId),
        conversationId: String(conversationId),
        senderId: String(senderId),
        senderUid: String(senderId),
        receiverId: String(receiverId),
        senderName: String(senderName),
        type: String(type),
        text: String(rawText),
        mediaUrl: String(payload.mediaUrl || ""),
        createdAt: String(payload.createdAt || Date.now()),
        channelId: "messages",
        groupKey: "com.example.bitchat.MESSAGE_GROUP"
      },
      notification: {
        title: senderName,
        body: notificationBody
      },
      android: {
        priority: "high",
        collapseKey: collapseTag,
        notification: {
          channelId: "messages",
          tag: collapseTag,
          icon: "ic_stat_bitchat",
          priority: "max",
          defaultSound: true,
          defaultVibrateTimings: true,
          visibility: "public"
        }
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
          if (devId) {
            cleanupPromises.push(
              db
                .collection("users")
                .doc(receiverId)
                .collection("devices")
                .doc(devId)
                .delete()
            );
          }
        }
      }
    });
    await Promise.all(cleanupPromises);

    await snap.ref.set(
      {
        status: "sent",
        deliveredCount: response.successCount,
        failureCount: response.failureCount,
        processedAt: Date.now()
      },
      { merge: true }
    );
  }
);
