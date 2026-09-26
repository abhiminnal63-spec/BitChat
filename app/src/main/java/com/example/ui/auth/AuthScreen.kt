package com.example.ui.auth

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BrutalistAvatar
import com.example.ui.theme.BrutalistBlack
import com.example.ui.theme.BrutalistButton
import com.example.ui.theme.BrutalistCard
import com.example.ui.theme.BrutalistTheme
import com.example.ui.theme.EasappAccent
import com.example.ui.theme.EasappSecondary
import com.example.ui.theme.SharpCorner

@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    onAuthSuccess: () -> Unit
) {
    val colors = BrutalistTheme.colors
    val state by viewModel.uiState.collectAsState()
    val registeredUsers by viewModel.registeredUsers.collectAsState()
    val focusManager = LocalFocusManager.current
    var passwordVisible by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Brutalist Hero Branding
            item {
                BrutalistCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = colors.accent,
                    borderColor = colors.border,
                    shadowOffset = 4.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "EASAPP",
                                fontWeight = FontWeight.Black,
                                fontSize = 32.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = (-1).sp,
                                color = colors.accentOn
                            )
                        }
                    }
                }
            }

            // Mode Selector Tabs (LOGIN / REGISTER)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isLogin = !state.isRegisterMode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (isLogin) colors.accent else colors.cardBackground,
                                SharpCorner
                            )
                            .border(2.dp, colors.border, SharpCorner)
                            .clickable {
                                if (state.isRegisterMode) viewModel.toggleMode()
                            }
                            .padding(vertical = 12.dp)
                            .testTag("tab_login"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "[ 01. LOGIN ]",
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = if (isLogin) colors.accentOn else colors.textPrimary
                        )
                    }

                    val isRegister = state.isRegisterMode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (isRegister) colors.accent else colors.cardBackground,
                                SharpCorner
                            )
                            .border(2.dp, colors.border, SharpCorner)
                            .clickable {
                                if (!state.isRegisterMode) viewModel.toggleMode()
                            }
                            .padding(vertical = 12.dp)
                            .testTag("tab_register"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "[ 02. REGISTER ]",
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = if (isRegister) colors.accentOn else colors.textPrimary
                        )
                    }
                }
            }

            // Form Box
            item {
                BrutalistCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = colors.cardBackground,
                    borderColor = colors.border,
                    shadowOffset = 4.dp
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = if (state.isRegisterMode) "CREATE NEW ACCOUNT" else "AUTHENTICATE CREDENTIALS",
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = colors.textPrimary
                        )

                        // Username Field
                        Column {
                            Text(
                                text = "USERNAME (@)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = state.username,
                                onValueChange = { viewModel.onUsernameChange(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_username"),
                                singleLine = true,
                                placeholder = {
                                    Text("e.g. alice, bob, alex", fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = colors.textSecondary)
                                },
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
                                    keyboardType = KeyboardType.Ascii,
                                    imeAction = if (state.isRegisterMode) ImeAction.Next else ImeAction.Next
                                ),
                                keyboardActions = KeyboardActions(
                                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                                )
                            )
                        }

                        // Display Name Field (Register only)
                        if (state.isRegisterMode) {
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
                                    value = state.displayName,
                                    onValueChange = { viewModel.onDisplayNameChange(it) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("input_display_name"),
                                    singleLine = true,
                                    placeholder = {
                                        Text("e.g. Alice Smith", fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = colors.textSecondary)
                                    },
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
                                        imeAction = ImeAction.Next
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                                    )
                                )
                            }
                        }

                        // Password Field
                        Column {
                            Text(
                                text = "PASSWORD",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = state.password,
                                onValueChange = { viewModel.onPasswordChange(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_password"),
                                singleLine = true,
                                placeholder = {
                                    Text("••••••••", fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = colors.textSecondary)
                                },
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = "Toggle password visibility",
                                            tint = colors.textPrimary
                                        )
                                    }
                                },
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
                                    keyboardType = KeyboardType.Password,
                                    imeAction = if (state.isRegisterMode) ImeAction.Next else ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                                    onDone = {
                                        focusManager.clearFocus()
                                        viewModel.submit(onAuthSuccess)
                                    }
                                )
                            )
                        }

                        // Confirm Password (Register only)
                        if (state.isRegisterMode) {
                            Column {
                                Text(
                                    text = "CONFIRM PASSWORD",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.textPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = state.confirmPassword,
                                    onValueChange = { viewModel.onConfirmPasswordChange(it) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("input_confirm_password"),
                                    singleLine = true,
                                    placeholder = {
                                        Text("••••••••", fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = colors.textSecondary)
                                    },
                                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
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
                                        keyboardType = KeyboardType.Password,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            focusManager.clearFocus()
                                            viewModel.submit(onAuthSuccess)
                                        }
                                    )
                                )
                            }
                        }

                        // Error Banner if present
                        if (state.errorMessage != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (colors.isDark) Color(0xFF4A1C1C) else Color(0xFFFFEBEE), SharpCorner)
                                    .border(2.dp, EasappSecondary, SharpCorner)
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = "ERROR: ${state.errorMessage}",
                                    color = if (colors.isDark) Color(0xFFFF8A80) else EasappSecondary,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Submit Button
                        if (state.isLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = colors.textPrimary)
                            }
                        } else {
                            BrutalistButton(
                                text = if (state.isRegisterMode) "CONFIRM REGISTRATION ->" else "AUTHORIZE & ENTER ->",
                                onClick = {
                                    focusManager.clearFocus()
                                    viewModel.submit(onAuthSuccess)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = colors.accent,
                                textColor = colors.accentOn,
                                leadingIcon = Icons.Default.ArrowForward,
                                testTag = "button_submit_auth"
                            )
                        }
                    }
                }
            }

            // Quick tester hints & Existing Registered Accounts on Device
            if (registeredUsers.isNotEmpty()) {
                item {
                    BrutalistCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = colors.cardBackground,
                        borderColor = colors.border,
                        shadowOffset = 3.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "REGISTERED ACCOUNTS (${registeredUsers.size})",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = "TAP TO FILL",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = EasappSecondary
                                )
                            }
                            Text(
                                text = "Accounts registered on this device. Tap any account to instantly fill username:",
                                fontSize = 11.sp,
                                color = colors.textSecondary,
                                fontFamily = FontFamily.Monospace
                            )

                            registeredUsers.forEach { user ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.5.dp, colors.border, SharpCorner)
                                        .background(colors.inputBackground, SharpCorner)
                                        .clickable { viewModel.quickSelectUser(user) }
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    BrutalistAvatar(
                                        seedOrName = user.displayName,
                                        avatarId = user.avatarSeed,
                                        size = 32.dp,
                                        isOnline = user.isOnline
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = user.displayName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = colors.textPrimary
                                        )
                                        Text(
                                            text = "@${user.username}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = colors.textSecondary
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .background(colors.accent, SharpCorner)
                                            .border(1.dp, colors.border, SharpCorner)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "SELECT",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = FontFamily.Monospace,
                                            color = colors.accentOn
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
