package com.example.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.MessageEntity
import com.example.ui.theme.BrutalistBlack
import com.example.ui.theme.BrutalistCard
import com.example.ui.theme.BrutalistTheme
import com.example.ui.theme.BrutalistWhite
import com.example.ui.theme.EasappAccent
import com.example.ui.theme.EasappSecondary
import com.example.ui.theme.MessageStatusIndicator
import com.example.ui.theme.SharpCorner
import com.example.util.DateTimeUtils

@Composable
fun MessageBubbleItem(
    message: MessageEntity,
    isOutgoing: Boolean,
    onImageClick: (uri: String) -> Unit,
    onShareImage: (uri: String) -> Unit
) {
    val colors = BrutalistTheme.colors
    val alignment = if (isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    val bgColor = if (isOutgoing) colors.accent else colors.cardBackground
    val textColor = if (isOutgoing) colors.accentOn else colors.textPrimary
    val timeColor = if (isOutgoing) colors.accentOn.copy(alpha = 0.7f) else colors.textSecondary
    val timeStr = DateTimeUtils.formatMessageTimestamp(message.timestamp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = alignment
    ) {
        BrutalistCard(
            modifier = Modifier.widthIn(min = 100.dp, max = 290.dp),
            backgroundColor = bgColor,
            borderColor = colors.border,
            shadowOffset = 2.dp,
            testTag = if (isOutgoing) "outgoing_message_${message.id}" else "incoming_message_${message.id}"
        ) {
            Column(
                modifier = Modifier.padding(10.dp)
            ) {
                // If attachment is present
                if (!message.attachmentUri.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(BrutalistBlack, SharpCorner)
                            .border(1.5.dp, colors.border, SharpCorner)
                            .clickable { onImageClick(message.attachmentUri) }
                    ) {
                        AsyncImage(
                            model = message.attachmentUri,
                            contentDescription = "Attachment",
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Crop
                        )
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .background(BrutalistBlack, SharpCorner)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "PHOTO",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = BrutalistWhite,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                                tint = EasappAccent,
                                modifier = Modifier
                                    .size(12.dp)
                                    .clickable { onShareImage(message.attachmentUri) }
                            )
                        }
                    }
                    if (message.content.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }

                // Text Content
                if (message.content.isNotBlank()) {
                    Text(
                        text = message.content,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = textColor,
                        lineHeight = 19.sp,
                        fontFamily = FontFamily.Default
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Footer: Timestamp + Status Indicators
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = timeStr,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = timeColor
                    )
                    if (isOutgoing) {
                        Spacer(modifier = Modifier.width(6.dp))
                        MessageStatusIndicator(
                            status = message.status,
                            showLabel = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DateSeparatorItem(dateText: String) {
    val colors = BrutalistTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .background(if (colors.isDark) colors.accent else BrutalistBlack, SharpCorner)
                .border(1.dp, colors.border, SharpCorner)
                .padding(horizontal = 10.dp, vertical = 3.dp)
        ) {
            Text(
                text = dateText.uppercase(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                color = if (colors.isDark) BrutalistBlack else colors.accent,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
fun AttachmentDraftPreview(
    uri: String,
    name: String?,
    onRemove: () -> Unit
) {
    val colors = BrutalistTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.inputBackground, SharpCorner)
            .border(1.5.dp, colors.border, SharpCorner)
            .padding(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(BrutalistBlack, SharpCorner)
                    .border(1.dp, colors.border, SharpCorner)
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = "Draft attachment",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Crop
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ATTACHED PHOTO",
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.textPrimary
                )
                Text(
                    text = name ?: "image_attachment.jpg",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.textSecondary
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove attachment",
                    tint = EasappSecondary
                )
            }
        }
    }
}

@Composable
fun QuickEmojiDrawer(
    onSelectEmoji: (String) -> Unit
) {
    val colors = BrutalistTheme.colors
    val emojis = listOf("🔥", "⚡", "👍", "💬", "🚀", "💀", "🖤", "🎯", "💯", "⚠️", "🤖", "✨")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.cardBackground, SharpCorner)
            .border(1.5.dp, colors.border, SharpCorner)
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            emojis.forEach { emoji ->
                Box(
                    modifier = Modifier
                        .clickable { onSelectEmoji(emoji) }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(text = emoji, fontSize = 20.sp)
                }
            }
        }
    }
}
