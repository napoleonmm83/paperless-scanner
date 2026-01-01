# Paperless Scanner

Android-Client zum Scannen und Hochladen von Dokumenten zu einer selbstgehosteten Paperless-ngx Instanz.

## Features

- **Login** mit Server-URL und Credentials
- **Dokumentenscan** mit automatischer Kantenerkennung (MLKit Document Scanner)
- **Upload** zu Paperless-ngx mit optionalem Titel
- **Tag-Auswahl** aus vorhandenen Tags
- **Material 3 Design** mit dynamischen Farben

## Screenshots

```
[Login] → [Scan] → [Preview + Tags] → [Upload Success]
```

## Voraussetzungen

- Android 8.0+ (API 26)
- Google Play Services (für MLKit)
- Paperless-ngx Instanz mit API-Zugang

## Installation

### Aus Source builden

```bash
git clone <repository-url>
cd "paperless client"
./gradlew assembleDebug
```

APK befindet sich unter: `app/build/outputs/apk/debug/app-debug.apk`

### In Android Studio

1. Projekt öffnen: `File → Open → paperless client`
2. Gradle Sync abwarten
3. Run auf Gerät/Emulator mit Google Play Services

## Konfiguration

### Netzwerk

Die App erlaubt Verbindungen zu:
- HTTPS (Standard)
- HTTP für lokale Netzwerke (10.x.x.x, 192.168.x.x, 172.16.x.x, localhost)

Für selbstsignierte Zertifikate: User-CA in Android installieren.

### Paperless-ngx

Benötigte API-Endpoints:
- `POST /api/token/` - Authentifizierung
- `GET /api/tags/` - Tags abrufen
- `POST /api/documents/post_document/` - Dokument hochladen

## Tech Stack

| Komponente | Technologie |
|------------|-------------|
| Sprache | Kotlin 2.0 |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt |
| Networking | Retrofit + OkHttp |
| Scanner | MLKit Document Scanner |
| Storage | DataStore Preferences |
| Image Loading | Coil |

## Projektstruktur

```
app/src/main/java/com/paperless/scanner/
├── di/                     # Dependency Injection
│   └── AppModule.kt
├── data/
│   ├── api/               # Retrofit API
│   │   ├── PaperlessApi.kt
│   │   └── models/
│   ├── repository/        # Business Logic
│   │   ├── AuthRepository.kt
│   │   ├── DocumentRepository.kt
│   │   └── TagRepository.kt
│   └── datastore/
│       └── TokenManager.kt
├── ui/
│   ├── theme/             # Material 3 Theme
│   ├── navigation/        # Navigation Graph
│   └── screens/
│       ├── login/         # Login Screen + ViewModel
│       ├── scan/          # Scan Screen + ViewModel
│       └── upload/        # Upload Screen + ViewModel
├── MainActivity.kt
└── PaperlessApp.kt
```

## Lizenz

MIT License

## Mitwirken

1. Fork erstellen
2. Feature Branch: `git checkout -b feature/neue-funktion`
3. Commit: `git commit -m 'Add neue Funktion'`
4. Push: `git push origin feature/neue-funktion`
5. Pull Request erstellen
