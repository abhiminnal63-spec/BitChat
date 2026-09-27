<div align="center">

# BitChat

### Real-time messaging without a mobile number.

A modern Android messaging application built with Jetpack Compose,
Firebase and a bold brutalist interface.

<br>

<img src="https://img.shields.io/badge/ANDROID-24%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android">
<img src="https://img.shields.io/badge/KOTLIN-2.x-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin">
<img src="https://img.shields.io/badge/JETPACK%20COMPOSE-UI-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose">
<img src="https://img.shields.io/badge/FIREBASE-BACKEND-FFCA28?style=for-the-badge&logo=firebase&logoColor=black" alt="Firebase">

<br><br>

<a href="#features">Features</a>
&nbsp;•&nbsp;
<a href="#screenshots">Screenshots</a>
&nbsp;•&nbsp;
<a href="#installation">Installation</a>
&nbsp;•&nbsp;
<a href="#architecture--technology">Architecture</a>
&nbsp;•&nbsp;
<a href="#configuration">Configuration</a>
&nbsp;•&nbsp;
<a href="#contributing">Contributing</a>

<br><br>

<img
  src="https://raw.githubusercontent.com/abhiminnal63-spec/BitChat/main/assets/bitchat-poster.png"
  width="900"
  alt="BitChat application showcase"
>

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
technologies to provide a distinctive messaging experience.

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
- Chat discovery
- Modern messaging UI

---

## 🎨 Experience

- Brutalist visual design
- Bold typography
- Tactile controls
- Jetpack Compose UI
- Smooth screen transitions
- Modern Android interface
- Light brutalism canvas
- Custom visual themes

---

## 🎨 Customization

- Fluorescent Green
- Hot Pink
- Red
- Electric Blue
- Yellow
- Orange
- Avatar identity seeds

</td>

<td width="50%" valign="top">

## 🔔 Notifications

- Firebase Cloud Messaging
- Background message notifications
- Device notification integration
- Notification-aware messaging flow

---

## ☁️ Backend

- Firebase Firestore
- Real-time cloud synchronization
- Firebase Cloud Messaging
- Firestore security rules
- Cloud-backed messaging infrastructure

---

## 🤖 AI

- Gemini API integration
- Environment-based API configuration
- Google AI Studio compatible configuration

</td>

</tr>
</table>

---

# 📱 Screenshots

<div align="center">

<img
  src="https://raw.githubusercontent.com/abhiminnal63-spec/BitChat/main/assets/bitchat-poster.png"
  width="900"
  alt="BitChat screenshots"
>

</div>

The showcase presents the core BitChat experience, including the login,
chat discovery and profile/theme customization interfaces.

---

# 🧱 Design

BitChat follows a **brutalist interface philosophy** built around strong
borders, high-contrast colors, oversized typography and tactile controls.

The design intentionally avoids the conventional appearance of typical
messaging applications.

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

# 🚀 Installation

## Clone the repository

```bash
git clone https://github.com/abhiminnal63-spec/BitChat.git
cd BitChat
```

Open the project in Android Studio and allow Gradle synchronization to
complete.

---

## 🔥 Firebase Configuration

Configure your own Firebase project and Android application.

Add your Firebase configuration according to your local development setup.

Do not commit private Firebase credentials or service-account keys.

---

# 🔐 Configuration

BitChat uses environment-based configuration for sensitive API values.

The repository provides:

```text
.env.example
```

Create your local environment configuration and add your own credentials.

Example:

```env
GEMINI_API_KEY=YOUR_GEMINI_API_KEY
```

Never commit real API keys to GitHub.

---

# 🛠️ Build

Build a debug APK with:

```bash
./gradlew assembleDebug
```

The generated APK will be available under:

```text
app/build/outputs/apk/debug/
```

---

# 📦 Download

Stable public releases will be published through GitHub Releases.

<a href="https://github.com/abhiminnal63-spec/BitChat/releases">

<strong>Download BitChat →</strong>

</a>

---

# 🗂️ Project Structure

```text
BitChat/
│
├── app/
│   └── src/
│       └── main/
│
├── assets/
│   ├── bitchat-poster.png
│   └── ...
│
├── gradle/
│
├── .env.example
├── .gitignore
├── firestore.rules
├── metadata.json
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

# 🛡️ Security

Do not commit:

```text
.env
google-services.json
*.jks
*.keystore
API keys
private credentials
Firebase service-account credentials
```

Keep sensitive configuration local to your development environment.

---

# 🤝 Contributing

Contributions are welcome.

### Create a branch

```bash
git checkout -b feature/your-feature
```

Make your changes, test the application and commit your work.

```bash
git add .
git commit -m "Add your feature"
git push origin feature/your-feature
```

Then open a Pull Request.

---

# 🗺️ Roadmap

- [x] Android application
- [x] Jetpack Compose UI
- [x] Firebase integration
- [x] Firestore integration
- [x] Firebase Cloud Messaging
- [x] Brutalist interface
- [x] Custom themes
- [x] Avatar identity selection
- [ ] Stable public release
- [ ] GitHub Releases APK
- [ ] More messaging controls
- [ ] Improved onboarding
- [ ] Performance improvements
- [ ] Additional customization

---

# 📄 License

See the repository license for licensing information.

---

<div align="center">

# 💬 BitChat

### CHAT. CONNECT. DISCOVER.

**Real-time messaging without a mobile number.**

<br>

⭐ Star the repository if you like the project.

<br>

<a href="https://github.com/abhiminnal63-spec/BitChat">
GitHub Repository
</a>

</div>
