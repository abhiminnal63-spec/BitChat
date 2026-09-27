package com.example.ui.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.MessageEntity
import com.example.data.model.MessageStatus
import com.example.ui.theme.BrutalistAvatar
import com.example.ui.theme.BrutalistBlack
import com.example.ui.theme.BrutalistButton
import com.example.ui.theme.BrutalistCard
import com.example.ui.theme.BrutalistTheme
import com.example.ui.theme.BrutalistTypingIndicator
import com.example.ui.theme.BrutalistWhite
import com.example.ui.theme.EasappAccent
import com.example.ui.theme.EasappMediaImage
import com.example.ui.theme.EasappOnlineGreen
import com.example.ui.theme.EasappReadBlue
import com.example.ui.theme.EasappSecondary
import com.example.ui.theme.SharpCorner
import com.example.ui.theme.ShootingStarFontFamily
import com.example.util.DateTimeUtils
import com.example.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
    onSwitchedToOtherUser: (newConvId: String, newOtherUserId: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = BrutalistTheme.colors
    val myId by viewModel.currentUserId.collectAsState()
    val otherUser by viewModel.otherUser.collectAsState()
    val isProfileLoading by viewModel.isProfileLoading.collectAsState()
    val isProfileUnavailable by viewModel.isProfileUnavailable.collectAsState()
    val canSwitchToOtherUser by viewModel.canSwitchToOtherUser.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val isOtherTyping by viewModel.isOtherUserTyping.collectAsState()
    val listState = rememberLazyListState()
    var isPeerProfileModalOpen by remember { mutableStateOf(false) }

    val nowTick by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(15_000L)
            value = System.currentTimeMillis()
        }
    }

    // Activity Result Launcher for Photo Picker (Android PickVisualMedia)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val encodedImage = withContext(Dispatchers.IO) {
                    ImageUtils.compressAndEncodeImage(context, uri)
                } ?: uri.toString()
                viewModel.setAttachment(
                    uri = encodedImage,
                    name = "photo_${System.currentTimeMillis()}.jpg",
                    size = encodedImage.length.toLong(),
                    type = "image"
                )
            }
        }
    }

    // Auto mark as read when visible and clear active conversation state when minimized or locked
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        viewModel.onEnterScreen()
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START,
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    viewModel.onEnterScreen()
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    viewModel.onExitScreen()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onExitScreen()
        }
    }

    BackHandler {
        viewModel.onExitScreen()
        onNavigateBack()
    }

    // Scroll to latest message on change
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Compact Brutalist Chat Header
            BrutalistCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = colors.cardBackground,
                borderColor = colors.border,
                shadowOffset = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back button
                    IconButton(
                        onClick = {
                            viewModel.onExitScreen()
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("chat_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.textPrimary
                        )
                    }

                    val hasRealProfile = otherUser != null && otherUser?.displayName?.isNotBlank() == true
                    val peerEffectiveOnline = DateTimeUtils.isEffectivelyOnline(
                        isOnline = otherUser?.isOnline == true,
                        lastSeenTimestamp = otherUser?.lastSeenTimestamp ?: 0L,
                        now = nowTick
                    )

                    val headerDisplayName = when {
                        hasRealProfile -> otherUser!!.displayName
                        isProfileUnavailable -> "Profile unavailable"
                        else -> "Loading profile..."
                    }

                    // Recipient Avatar + Name (clickable to view peer profile if loaded, or retry if unavailable)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                if (hasRealProfile) {
                                    isPeerProfileModalOpen = true
                                } else if (isProfileUnavailable) {
                                    viewModel.retryFetchProfile()
                                }
                            }
                            .testTag("chat_header_peer_profile_button"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BrutalistAvatar(
                            seedOrName = if (hasRealProfile) otherUser!!.displayName else "?",
                            avatarId = if (hasRealProfile) otherUser?.avatarSeed else null,
                            size = 38.dp,
                            isOnline = if (hasRealProfile) peerEffectiveOnline else false
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        // Name + Status with Real-time Firestore/Relay Presence & Last Seen Time
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = headerDisplayName,
                                fontWeight = FontWeight.Black,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (hasRealProfile) {
                                AnimatedContent(
                                    targetState = isOtherTyping,
                                    transitionSpec = {
                                        fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(140))
                                    },
                                    label = "header_typing_status"
                                ) { typingActive ->
                                    if (typingActive) {
                                        val infiniteTransition = rememberInfiniteTransition(label = "header_typing_pulse")
                                        val dotAlpha by infiniteTransition.animateFloat(
                                            initialValue = 0.35f,
                                            targetValue = 1f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(450),
                                                repeatMode = RepeatMode.Reverse
                                            ),
                                            label = "typing_dot_alpha"
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.testTag("chat_header_typing_indicator")
                                        ) {
                                            Text(
                                                text = "●",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Black,
                                                fontFamily = FontFamily.Monospace,
                                                color = EasappOnlineGreen.copy(alpha = dotAlpha)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "typing...",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Black,
                                                fontFamily = FontFamily.Monospace,
                                                color = EasappOnlineGreen
                                            )
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val lastSeenText = DateTimeUtils.formatLastSeen(
                                                isOnline = peerEffectiveOnline,
                                                lastSeenTimestamp = otherUser?.lastSeenTimestamp ?: 0L,
                                                now = nowTick
                                            ).uppercase()
                                            Text(
                                                text = "• $lastSeenText",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                color = if (peerEffectiveOnline) EasappOnlineGreen else colors.textSecondary
                                            )
                                        }
                                    }
                                }
                            } else if (isProfileUnavailable) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "• Reconnecting...",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = colors.textSecondary
                                    )
                                }
                            }
                        }
                    }

                    // Quick Switch Action only if the other account is also authenticated on this device
                    if (canSwitchToOtherUser) {
                        Box(
                            modifier = Modifier
                                .background(colors.accent, SharpCorner)
                                .border(1.5.dp, colors.border, SharpCorner)
                                .clickable {
                                    viewModel.switchUserToOther { newConvId, newOtherId ->
                                        onSwitchedToOtherUser(newConvId, newOtherId)
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                                .testTag("button_switch_to_other"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.SwapHoriz,
                                    contentDescription = null,
                                    tint = colors.accentOn,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "REPLY AS",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.accentOn
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    Text(
                        text = "BITCHAT",
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp,
                        fontFamily = ShootingStarFontFamily,
                        color = colors.textPrimary,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .testTag("chat_header_bitchat_branding")
                    )
                }
            }

            // Message History Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (messages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BrutalistCard(
                            backgroundColor = colors.cardBackground,
                            borderColor = colors.border,
                            shadowOffset = 3.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "[ CONVERSATION INITIALIZED ]",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.textPrimary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Send the first message to @${otherUser?.username ?: "recipient"}. Real-time protocol is active.",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.textSecondary,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(messages, key = { _, msg -> msg.id }) { index, message ->
                            // Date header check
                            val showDateHeader = if (index == 0) {
                                true
                            } else {
                                val prev = messages[index - 1]
                                !isSameDay(prev.timestamp, message.timestamp)
                            }

                            if (showDateHeader) {
                                DateSeparatorItem(dateText = formatDateHeader(message.timestamp))
                            }

                            val isOutgoing = message.senderId == myId
                            MessageBubbleItem(
                                message = message,
                                isOutgoing = isOutgoing,
                                onImageClick = { uri ->
                                    viewModel.setPreviewImage(uri)
                                },
                                onShareImage = { uri ->
                                    ImageUtils.shareImage(context, uri, "Shared via BITCHAT")
                                },
                                onRetryMessage = { msgId ->
                                    viewModel.retryMessage(msgId)
                                },
                                modifier = Modifier.animateItem()
                            )
                        }

                        // Space at bottom for typing or composer
                        item {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }

                // Ephemeral Typing Indicator Overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 8.dp)
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isOtherTyping
                    ) {
                        BrutalistTypingIndicator(
                            username = otherUser?.displayName ?: "THEY"
                        )
                    }
                }
            }

            // Optional Quick Emoji Drawer
            androidx.compose.animation.AnimatedVisibility(visible = uiState.isEmojiPickerOpen) {
                QuickEmojiDrawer(
                    onSelectEmoji = { emoji ->
                        viewModel.appendEmoji(emoji)
                    }
                )
            }

            // Optional Attachment Preview
            if (uiState.selectedAttachmentUri != null) {
                Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    AttachmentDraftPreview(
                        uri = uiState.selectedAttachmentUri!!,
                        name = uiState.selectedAttachmentName,
                        onRemove = { viewModel.clearAttachment() }
                    )
                }
            }

            // Mobile Message Composer
            BrutalistCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = colors.cardBackground,
                borderColor = colors.border,
                shadowOffset = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    // Attachment / Photo Picker Button
                    IconButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .size(42.dp)
                            .testTag("button_attach_media")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = "Attach image",
                            tint = colors.textPrimary
                        )
                    }

                    // Emoji toggle button
                    IconButton(
                        onClick = { viewModel.toggleEmojiPicker() },
                        modifier = Modifier
                            .size(42.dp)
                            .testTag("button_emoji_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Default.EmojiEmotions,
                            contentDescription = "Emojis",
                            tint = if (uiState.isEmojiPickerOpen) colors.accent else colors.textPrimary
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Text Input
                    OutlinedTextField(
                        value = uiState.textInput,
                        onValueChange = { viewModel.onTextInputChange(it) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("message_input_field"),
                        placeholder = {
                            Text(
                                text = "MESSAGE...",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textSecondary
                            )
                        },
                        maxLines = 4,
                        shape = SharpCorner,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.border.copy(alpha = 0.5f),
                            focusedContainerColor = colors.inputBackground,
                            unfocusedContainerColor = colors.inputBackground,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            cursorColor = colors.accent
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Default
                        )
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    // Tactile Send Button
                    val canSend = uiState.textInput.isNotBlank() || uiState.selectedAttachmentUri != null
                    val sendBgColor by animateColorAsState(
                        targetValue = if (canSend) colors.accent else colors.inputBackground,
                        animationSpec = tween(durationMillis = 200),
                        label = "send_bg_color"
                    )
                    val sendScale by animateFloatAsState(
                        targetValue = if (canSend) 1f else 0.94f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "send_scale"
                    )
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .graphicsLayer {
                                scaleX = sendScale
                                scaleY = sendScale
                            }
                            .background(sendBgColor, SharpCorner)
                            .border(2.dp, colors.border, SharpCorner)
                            .clickable(enabled = canSend) {
                                viewModel.sendMessage()
                            }
                            .testTag("button_send_message"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (canSend) colors.accentOn else colors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Full Screen Image Viewer Modal with Image Sharing Option
        if (uiState.previewImageUri != null) {
            Dialog(onDismissRequest = { viewModel.setPreviewImage(null) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.cardBackground, SharpCorner)
                        .border(3.dp, colors.accent, SharpCorner)
                        .padding(12.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "IMAGE ATTACHMENT",
                                color = colors.accent,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                            IconButton(onClick = { viewModel.setPreviewImage(null) }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = colors.textPrimary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        EasappMediaImage(
                            model = uiState.previewImageUri,
                            contentDescription = "Full preview",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        // Tactile Share Image Button
                        BrutalistButton(
                            text = "SHARE IMAGE TO APPS ->",
                            onClick = {
                                ImageUtils.shareImage(context, uiState.previewImageUri!!, "Shared via BITCHAT")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = colors.accent,
                            textColor = colors.accentOn,
                            borderColor = colors.border,
                            leadingIcon = Icons.Default.Share,
                            shadowOffset = 2.dp,
                            testTag = "button_share_full_image"
                        )
                    }
                }
            }
        }

        // Peer User Profile View Modal
        if (isPeerProfileModalOpen && otherUser != null) {
            val peer = otherUser!!
            val peerOnline = DateTimeUtils.isEffectivelyOnline(
                isOnline = peer.isOnline,
                lastSeenTimestamp = peer.lastSeenTimestamp,
                now = nowTick
            )
            Dialog(onDismissRequest = { isPeerProfileModalOpen = false }) {
                BrutalistCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chat_peer_profile_dialog"),
                    backgroundColor = colors.cardBackground,
                    borderColor = colors.border,
                    borderWidth = 3.dp,
                    shadowOffset = 4.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "USER PROFILE // IDENTITY",
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = colors.textPrimary
                            )
                            IconButton(onClick = { isPeerProfileModalOpen = false }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close profile",
                                    tint = colors.textPrimary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Box(
                            modifier = Modifier
                                .background(colors.accent, SharpCorner)
                                .border(2.5.dp, BrutalistBlack, SharpCorner)
                                .padding(4.dp)
                        ) {
                            BrutalistAvatar(
                                seedOrName = peer.displayName,
                                avatarId = peer.avatarSeed,
                                size = 88.dp,
                                isOnline = peerOnline,
                                showOnlineBadge = true
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = peer.displayName,
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "@${peer.username}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.textSecondary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        val statusText = DateTimeUtils.formatLastSeen(
                            isOnline = peerOnline,
                            lastSeenTimestamp = peer.lastSeenTimestamp,
                            now = nowTick
                        )
                        Box(
                            modifier = Modifier
                                .background(
                                    if (peerOnline) EasappOnlineGreen else colors.inputBackground,
                                    SharpCorner
                                )
                                .border(1.5.dp, BrutalistBlack, SharpCorner)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = statusText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = if (peerOnline) BrutalistBlack else colors.textPrimary
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = peer.statusMessage.ifBlank { "Available on BITCHAT" },
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.textPrimary
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        BrutalistButton(
                            text = "CLOSE PROFILE",
                            onClick = { isPeerProfileModalOpen = false },
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = colors.accent,
                            textColor = colors.accentOn,
                            shadowOffset = 2.dp
                        )
                    }
                }
            }
        }
    }
}

fun isSameDay(t1: Long, t2: Long): Boolean {
    val fmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    return fmt.format(Date(t1)) == fmt.format(Date(t2))
}

fun formatDateHeader(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val fmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    val dateStr = fmt.format(Date(timestamp))
    val todayStr = fmt.format(Date(now))
    val yesterdayStr = fmt.format(Date(now - 24 * 60 * 60 * 1000L))

    return when (dateStr) {
        todayStr -> "TODAY"
        yesterdayStr -> "YESTERDAY"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}
