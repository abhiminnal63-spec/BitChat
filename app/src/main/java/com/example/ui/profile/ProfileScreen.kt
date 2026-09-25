package com.example.ui.profile

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BrutalistAvatar
import com.example.ui.theme.BrutalistBlack
import com.example.ui.theme.BrutalistButton
import com.example.ui.theme.BrutalistCard
import com.example.ui.theme.BrutalistTheme
import com.example.ui.theme.EasappAccent
import com.example.ui.theme.EasappOnlineGreen
import com.example.ui.theme.EasappSecondary
import com.example.ui.theme.SharpCorner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onNavigateBack: () -> Unit,
    onLoggedOut: () -> Unit
) {
    val currentUser by viewModel.currentUser.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val isCloudConnected by viewModel.isCloudConnected.collectAsState()
    val isRelayConnected by viewModel.isRelayConnected.collectAsState()

    BackHandler {
        onNavigateBack()
    }

    val colors = BrutalistTheme.colors
    val avatarPresets = listOf("ALICE", "BOB", "CYBER", "NEON", "MATRIX", "EASAPP")
    val deviceTimeStr = SimpleDateFormat("h:mm a (z)", Locale.getDefault()).format(Date())

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
            // Header
            BrutalistCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = colors.cardBackground,
                shadowOffset = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("profile_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.textPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "ACCOUNT // PROFILE // SETTINGS",
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textPrimary
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Identity Card
                item {
                    BrutalistCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = colors.cardBackground,
                        shadowOffset = 3.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            BrutalistAvatar(
                                seedOrName = uiState.selectedAvatar.ifBlank { currentUser?.displayName ?: "ME" },
                                size = 72.dp,
                                isOnline = true,
                                showOnlineBadge = true
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = currentUser?.displayName ?: "User",
                                fontWeight = FontWeight.Black,
                                fontSize = 18.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "@${currentUser?.username}",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textSecondary
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = "SELECT AVATAR IDENTITY SEED:",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                avatarPresets.forEach { preset ->
                                    val isSelected = uiState.selectedAvatar == preset
                                    Box(
                                        modifier = Modifier
                                            .border(
                                                if (isSelected) 3.dp else 1.5.dp,
                                                if (isSelected) colors.accent else colors.border,
                                                SharpCorner
                                            )
                                            .clickable { viewModel.onAvatarSelect(preset) }
                                            .padding(2.dp)
                                    ) {
                                        BrutalistAvatar(
                                            seedOrName = preset,
                                            size = 36.dp,
                                            showOnlineBadge = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Appearance & Dark Mode Card
                item {
                    BrutalistCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = colors.cardBackground,
                        shadowOffset = 3.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "APPEARANCE // THEME",
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textPrimary
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isDarkMode) "DARK BRUTALISM" else "LIGHT BRUTALISM",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = colors.textPrimary
                                    )
                                    Text(
                                        text = if (isDarkMode) "Jet black canvas & high voltage neon" else "Off-white canvas & high contrast borders",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = colors.textSecondary
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .background(if (isDarkMode) EasappAccent else colors.cardBackground, SharpCorner)
                                        .border(2.dp, colors.border, SharpCorner)
                                        .clickable { viewModel.toggleDarkMode() }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .testTag("button_toggle_theme")
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (isDarkMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                                            contentDescription = null,
                                            tint = if (isDarkMode) BrutalistBlack else colors.textPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isDarkMode) "DARK: ON" else "LIGHT: ON",
                                            fontWeight = FontWeight.Black,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (isDarkMode) BrutalistBlack else colors.textPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Edit Profile Info Card
                item {
                    BrutalistCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = colors.cardBackground,
                        shadowOffset = 3.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "PROFILE INFORMATION",
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textPrimary
                            )

                            Column {
                                Text(
                                    text = "DISPLAY NAME",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.textPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = uiState.displayName,
                                    onValueChange = { viewModel.onDisplayNameChange(it) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("input_profile_name"),
                                    singleLine = true,
                                    shape = SharpCorner,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = colors.border,
                                        unfocusedBorderColor = colors.border.copy(alpha = 0.6f),
                                        focusedContainerColor = colors.inputBackground,
                                        unfocusedContainerColor = colors.inputBackground,
                                        focusedTextColor = colors.textPrimary,
                                        unfocusedTextColor = colors.textPrimary,
                                        cursorColor = colors.accent
                                    )
                                )
                            }

                            Column {
                                Text(
                                    text = "STATUS // ABOUT",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.textPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = uiState.statusMessage,
                                    onValueChange = { viewModel.onStatusMessageChange(it) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("input_profile_status"),
                                    singleLine = true,
                                    shape = SharpCorner,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = colors.border,
                                        unfocusedBorderColor = colors.border.copy(alpha = 0.6f),
                                        focusedContainerColor = colors.inputBackground,
                                        unfocusedContainerColor = colors.inputBackground,
                                        focusedTextColor = colors.textPrimary,
                                        unfocusedTextColor = colors.textPrimary,
                                        cursorColor = colors.accent
                                    )
                                )
                            }

                            if (uiState.saveSuccess) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (isDarkMode) Color(0xFF1B381E) else Color(0xFFE8F5E9), SharpCorner)
                                        .border(1.5.dp, EasappOnlineGreen, SharpCorner)
                                        .padding(8.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = EasappOnlineGreen,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "PROFILE UPDATED & SAVED ACROSS FIRESTORE / ROOM",
                                            fontWeight = FontWeight.Black,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = colors.textPrimary
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            if (uiState.isSaving) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = colors.textPrimary)
                                }
                            } else {
                                BrutalistButton(
                                    text = "SAVE PROFILE CHANGES",
                                    onClick = { viewModel.saveChanges() },
                                    modifier = Modifier.fillMaxWidth(),
                                    backgroundColor = colors.accent,
                                    textColor = colors.accentOn,
                                    shadowOffset = 2.dp,
                                    testTag = "button_save_profile"
                                )
                            }
                        }
                    }
                }

                // Cloud & Device Synchronization Card
                item {
                    BrutalistCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = colors.cardBackground,
                        shadowOffset = 3.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "CLOUD & DEVICE TELEMETRY",
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textPrimary
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isRelayConnected) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                    contentDescription = null,
                                    tint = if (isRelayConnected) EasappOnlineGreen else EasappSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isRelayConnected) "CROSS-DEVICE MESH: ONLINE // READY FOR PHONE A <-> B" else "CROSS-DEVICE MESH: CONNECTING...",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isRelayConnected) EasappOnlineGreen else colors.textSecondary
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isCloudConnected) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                    contentDescription = null,
                                    tint = if (isCloudConnected) EasappOnlineGreen else EasappSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isCloudConnected) "FIRESTORE CLOUD ENGINE: ACTIVE // LIVE SYNC" else "FIRESTORE: STANDBY (OFFLINE ROOM CACHE ACTIVE)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isCloudConnected) EasappOnlineGreen else colors.textSecondary
                                )
                            }

                            Text(
                                text = "• DEVICE TIME: $deviceTimeStr",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textSecondary
                            )
                            Text(
                                text = "• USER ID: ${currentUser?.id}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textSecondary
                            )
                        }
                    }
                }

                // Logout Button
                item {
                    BrutalistButton(
                        text = "LOGOUT ACCOUNT",
                        onClick = { viewModel.logout(onLoggedOut) },
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = if (isDarkMode) Color(0xFF4A1C1C) else Color(0xFFFFCDD2),
                        textColor = if (isDarkMode) Color(0xFFFF8A80) else BrutalistBlack,
                        borderColor = EasappSecondary,
                        leadingIcon = Icons.Default.Logout,
                        shadowOffset = 3.dp,
                        testTag = "button_profile_logout"
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
