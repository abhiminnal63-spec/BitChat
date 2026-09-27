<div align="center">

<img src="assets/icon.png" width="110" alt="BitChat Logo">

# BitChat

### Real-time messaging without a mobile number.

<p>
  A modern Android messaging application built with Jetpack Compose,
  Firebase and a bold brutalist interface.
</p>

<br>

[![Android](https://img.shields.io/badge/Android-24%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-UI-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![Firebase](https://img.shields.io/badge/Firebase-Backend-FFCA28?style=for-the-badge&logo=firebase&logoColor=black)](https://firebase.google.com/)

<br><br>

<a href="#features">Features</a>
&nbsp;•&nbsp;
<a href="#screenshots">Screenshots</a>
&nbsp;•&nbsp;
<a href="#installation">Installation</a>
&nbsp;•&nbsp;
<a href="#architecture">Architecture</a>
&nbsp;•&nbsp;
<a href="#contributing">Contributing</a>

<br><br>

<img src="assets/banner.png" width="850" alt="BitChat">

</div>

---

> [!IMPORTANT]
> BitChat is an independent open-source project. It is not affiliated with,
> endorsed by, or connected to WhatsApp, Telegram, Signal, or any other
> messaging platform.

---

# 💬 BitChat

BitChat is a real-time Android messaging application designed around a simple
idea: **chat without requiring a mobile number.**

The application combines a bold brutalist visual language with modern Android
technologies to provide a fast and straightforward messaging experience.

---

# ✨ Features

<table>
<tr>

<td width="50%" valign="top">

## 💬 Messaging

- Real-time messaging
- Mobile-number-free chat
- Conversation interface
- Message delivery
- Message notifications
- Chat-based communication
- Modern messaging UI

## 🎨 Experience

- Brutalist visual design
- Bold typography
- Tactile controls
- Responsive Jetpack Compose UI
- Smooth screen transitions
- Modern Android interface
- Dark-theme-friendly design

</td>

<td width="50%" valign="top">

## 🔔 Notifications

- Firebase Cloud Messaging
- Background message notifications
- Device notification integration
- Notification-aware messaging flow

## ☁️ Backend

- Firebase Firestore
- Real-time cloud synchronization
- Firebase Cloud Messaging
- Firestore security rules
- Cloud-backed messaging infrastructure

## 🤖 AI

- Gemini API integration
- Environment-based API configuration
- AI Studio compatible configuration

</td>

</tr>
</table>

---

# 📱 Screenshots

<div align="center">

<table>
<tr>

<td align="center">
<img src="screenshots/home.png" width="250">
<br>
<b>Home</b>
</td>

<td align="center">
<img src="screenshots/chat.png" width="250">
<br>
<b>Chat</b>
</td>

<td align="center">
<img src="screenshots/profile.png" width="250">
<br>
<b>Profile</b>
</td>

</tr>

<tr>

<td align="center">
<img src="screenshots/notifications.png" width="250">
<br>
<b>Notifications</b>
</td>

<td align="center">
<img src="screenshots/settings.png" width="250">
<br>
<b>Settings</b>
</td>

<td align="center">
<img src="screenshots/about.png" width="250">
<br>
<b>About</b>
</td>

</tr>
</table>

</div>

---

# 🧱 Design

BitChat uses a **brutalist-inspired interface** built around strong visual
hierarchy, bold typography and tactile controls.

The interface intentionally avoids the typical generic messaging-app
appearance and gives BitChat its own visual identity.

---

# 🏗️ Architecture & Technology

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose |
| Design System | Material 3 |
| Database | Room |
| Cloud Database | Firebase Firestore |
| Push Notifications | Firebase Cloud Messaging |
| Networking | OkHttp |
| Image Loading | Coil |
| Async Programming | Kotlin Coroutines |
| Build System | Gradle Kotlin DSL |
| AI | Google Gemini API |
| Minimum Android | Android 7.0 / API 24 |
| Target Android | Android API 36 |

---

# 🔐 Configuration

BitChat uses environment-based configuration for sensitive API values.

Create your local environment configuration from:

```text
.env.example
```

---

# 🚀 Production Release & GitHub Releases

BITCHAT includes automated, secure release signing and GitHub Releases deployment.

### 1. Generating the Production Keystore Locally

To create your permanent production signing key (run this once on your local machine):

```bash
keytool -genkeypair -v \
  -keystore bitchat-release.jks \
  -alias bitchat \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storetype JKS
```

> ⚠️ **Important:**
> - Keep `bitchat-release.jks` in a secure location backed up outside the git repository.
> - Never commit keystore files or passwords to GitHub.

---

### 2. Building a Signed Production Release Locally

Export your signing credentials as environment variables:

```bash
export BITCHAT_KEYSTORE_PATH="/absolute/path/to/bitchat-release.jks"
export BITCHAT_KEYSTORE_PASSWORD="your-keystore-password"
export BITCHAT_KEY_ALIAS="bitchat"
export BITCHAT_KEY_PASSWORD="your-key-password"

./gradlew assembleRelease
```

The resulting signed production APK will be generated at:
```text
app/build/outputs/apk/release/BitChat-release.apk
```

---

### 3. Automated GitHub Releases via Git Tags

GitHub Actions automatically builds and publishes production releases when a version tag is pushed.

#### A. Configure GitHub Secrets in Repository Settings:

Navigate to **Settings > Secrets and variables > Actions** and create the following repository secrets:

| Secret Name | Description | Example / Format |
|---|---|---|
| `BITCHAT_KEYSTORE_BASE64` | Base64-encoded string of `bitchat-release.jks` | `cat bitchat-release.jks \| base64` |
| `BITCHAT_KEYSTORE_PASSWORD` | Password protecting the keystore | `your-secure-store-password` |
| `BITCHAT_KEY_ALIAS` | Key alias in the keystore | `bitchat` |
| `BITCHAT_KEY_PASSWORD` | Password protecting the key alias | `your-secure-key-password` |

To generate the `BITCHAT_KEYSTORE_BASE64` value on Linux/macOS:
```bash
base64 -w 0 bitchat-release.jks
# or on macOS:
base64 -i bitchat-release.jks
```

#### B. Tag and Trigger a Release:

```bash
# 1. Ensure your working tree is clean and on main
git checkout main

# 2. Create the version tag
git tag v1.0.0

# 3. Push the tag to GitHub
git push origin v1.0.0
```

#### C. What GitHub Actions Does Automatically:
1. Detects the `v*` tag push.
2. Securely decodes the production keystore into a temporary runner directory.
3. Builds the signed production APK with `./gradlew assembleRelease`.
4. Calculates the official **SHA-256 Checksum** for download integrity.
5. Names the asset consistently: `BITCHAT-v1.0.0.apk`.
6. Creates a GitHub Release tagged `v1.0.0` with release notes and attached APK.
7. Securely deletes the temporary keystore from the runner.
