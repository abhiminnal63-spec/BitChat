package com.example.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.MessageStatus

val SharpCorner = RoundedCornerShape(0.dp)
val SubtleSharpCorner = RoundedCornerShape(2.dp)

@Composable
fun BrutalistButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = BrutalistTheme.colors.accent,
    textColor: Color = BrutalistTheme.colors.accentOn,
    borderColor: Color = BrutalistTheme.colors.border,
    borderWidth: Dp = 2.dp,
    shadowOffset: Dp = 3.dp,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    testTag: String = "brutalist_button"
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOffset = if (isPressed) shadowOffset / 2 else 0.dp

    Box(
        modifier = modifier
            .testTag(testTag)
            .padding(bottom = shadowOffset, end = shadowOffset)
    ) {
        // Drop shadow box
        if (enabled && shadowOffset > 0.dp) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(x = shadowOffset, y = shadowOffset)
                    .background(BrutalistTheme.colors.shadow, SharpCorner)
            )
        }

        // Foreground tactile button
        Box(
            modifier = Modifier
                .offset(x = currentOffset, y = currentOffset)
                .background(if (enabled) backgroundColor else BrutalistTheme.colors.inputBackground, SharpCorner)
                .border(borderWidth, borderColor, SharpCorner)
                .pointerInput(enabled) {
                    if (enabled) {
                        detectTapGestures(
                            onPress = {
                                isPressed = true
                                tryAwaitRelease()
                                isPressed = false
                                onClick()
                            }
                        )
                    }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = text.uppercase(),
                    color = textColor,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp,
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun BrutalistCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = BrutalistTheme.colors.cardBackground,
    borderColor: Color = BrutalistTheme.colors.border,
    borderWidth: Dp = 2.dp,
    shadowOffset: Dp = 3.dp,
    onClick: (() -> Unit)? = null,
    testTag: String = "brutalist_card",
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .testTag(testTag)
            .padding(bottom = shadowOffset, end = shadowOffset)
    ) {
        // Shadow box
        if (shadowOffset > 0.dp) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(x = shadowOffset, y = shadowOffset)
                    .background(BrutalistTheme.colors.shadow, SharpCorner)
            )
        }

        // Main card box
        Box(
            modifier = Modifier
                .background(backgroundColor, SharpCorner)
                .border(borderWidth, borderColor, SharpCorner)
                .then(
                    if (onClick != null) {
                        Modifier.clickable { onClick() }
                    } else Modifier
                )
        ) {
            content()
        }
    }
}

@Composable
fun BrutalistAvatar(
    seedOrName: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    imageUrl: String? = null,
    isOnline: Boolean = false,
    showOnlineBadge: Boolean = true
) {
    val cleanSeed = seedOrName.trim().uppercase()
    val initials = cleanSeed.split(" ")
        .mapNotNull { it.firstOrNull()?.toString() }
        .take(2)
        .joinToString("")
        .ifEmpty { cleanSeed.take(2).ifEmpty { "??" } }

    val avatarPalette = listOf(
        Color(0xFFFF5252),
        Color(0xFFFF7A00),
        Color(0xFFFFD600),
        Color(0xFF00E676),
        Color(0xFF00B0FF),
        Color(0xFF7C4DFF),
        Color(0xFFFF4081)
    )
    val colorIndex = kotlin.math.abs(cleanSeed.hashCode()) % avatarPalette.size
    val bgSolid = avatarPalette[colorIndex]

    Box(
        modifier = modifier
            .size(size)
    ) {
        // Avatar Box
        Box(
            modifier = Modifier
                .size(size)
                .background(bgSolid, SharpCorner)
                .border(2.dp, BrutalistTheme.colors.border, SharpCorner),
            contentAlignment = Alignment.Center
        ) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = "Avatar",
                    modifier = Modifier
                        .size(size)
                        .clip(SharpCorner)
                )
            } else {
                Text(
                    text = initials,
                    color = BrutalistBlack,
                    fontWeight = FontWeight.Black,
                    fontSize = (size.value * 0.42f).sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = (-1).sp
                )
            }
        }

        // Online Status Square Indicator
        if (showOnlineBadge && isOnline) {
            val badgeSize = (size.value * 0.28f).coerceAtLeast(10f).dp
            Box(
                modifier = Modifier
                    .size(badgeSize)
                    .align(Alignment.BottomEnd)
                    .background(EasappOnlineGreen, SharpCorner)
                    .border(1.5.dp, BrutalistTheme.colors.cardBackground, SharpCorner)
            )
        }
    }
}

@Composable
fun BrutalistBadge(
    count: Int,
    modifier: Modifier = Modifier,
    backgroundColor: Color = EasappAccent,
    textColor: Color = BrutalistBlack
) {
    if (count <= 0) return

    val text = if (count > 99) "99+" else count.toString()

    Box(
        modifier = modifier
            .background(backgroundColor, SharpCorner)
            .border(1.5.dp, BrutalistTheme.colors.border, SharpCorner)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun MessageStatusIndicator(
    status: String,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true
) {
    val colors = BrutalistTheme.colors
    when (status.uppercase()) {
        MessageStatus.SENDING.name -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
                if (showLabel) {
                    Text(
                        text = "SENDING",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                }
                Icon(
                    imageVector = Icons.Default.AccessTime,
                    contentDescription = "Sending",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        MessageStatus.SENT.name -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
                if (showLabel) {
                    Text(
                        text = "SENT",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                }
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Sent",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
        MessageStatus.DELIVERED.name -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
                if (showLabel) {
                    Text(
                        text = "DELIVERED",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                }
                Icon(
                    imageVector = Icons.Default.DoneAll,
                    contentDescription = "Delivered",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        MessageStatus.READ.name -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = modifier
            ) {
                if (showLabel) {
                    Text(
                        text = "SEEN",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp,
                        color = EasappReadBlue
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                }
                Icon(
                    imageVector = Icons.Default.DoneAll,
                    contentDescription = "Seen",
                    tint = EasappReadBlue,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        else -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Message",
                tint = colors.textSecondary,
                modifier = modifier.size(13.dp)
            )
        }
    }
}

@Composable
fun BrutalistTypingIndicator(
    username: String,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    val dotAlpha1 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )

    Box(
        modifier = modifier
            .background(BrutalistTheme.colors.cardBackground, SharpCorner)
            .border(1.5.dp, BrutalistTheme.colors.border, SharpCorner)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(EasappAccent.copy(alpha = dotAlpha1), CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${username.uppercase()} IS TYPING...",
                color = BrutalistTheme.colors.textPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )
        }
    }
}
