# Flutter to Native Android Rewrite Plan

> **Speech2Prompt** - A voice-to-keyboard bridge that captures speech on Android and transmits text to a Linux desktop via BLE, enabling hands-free typing in any application.

## Overview

This documentation outlines the complete migration plan from Flutter to native Android (Kotlin + Jetpack Compose) for the Speech2Prompt application. The rewrite aims to provide better platform integration, improved performance, and a more maintainable codebase.

---

## Table of Contents

### Planning & Architecture
- [00 - Overview](./00-OVERVIEW.md) - Application analysis, feature inventory, architecture decisions

### Implementation Phases

| Phase | Title | Estimated Time |
|-------|-------|----------------|
| [01](./01-PROJECT-SETUP.md) | Project Setup & Architecture Foundation | 0.5-1 day |
| [02](./02-CORE-MODELS.md) | Core Domain Models | 0.5 day |
| [03](./03-DEPENDENCY-INJECTION.md) | Dependency Injection with Hilt | 0.5 day |
| [04](./04-PERMISSION-SERVICE.md) | Permission Management Service | 0.5-1 day |
| [05](./05-SECURE-STORAGE.md) | Secure Storage Service | 0.5 day |
| [06](./06-BLE-SERVICE.md) | BLE Communication Service | 1.5-2 days |
| [07](./07-SPEECH-SERVICE.md) | Speech Recognition Service | 1-1.5 days |
| [08](./08-COMMAND-PROCESSOR.md) | Voice Command Processor | 0.5-1 day |
| [09](./09-ENCRYPTION.md) | AES-256-GCM Encryption Layer | 0.5-1 day |
| [10](./10-VIEWMODELS.md) | ViewModels & State Management | 1-1.5 days |
| [11](./11-UI-COMPONENTS.md) | Reusable UI Components | 1 day |
| [12](./12-SCREENS.md) | Main Application Screens | 1.5-2 days |
| [13](./13-NAVIGATION.md) | Navigation & Deep Linking | 0.5 day |
| [14](./14-TESTING.md) | Testing Strategy & Implementation | 1-2 days |
| [15](./15-MIGRATION.md) | Migration & Deployment | 0.5-1 day |

---

## Quick Start

### Prerequisites

Before starting the rewrite, ensure you have:

- **Android Studio** Hedgehog (2023.1.1) or newer
- **JDK 17** or higher
- **Android SDK 34** (target) with minimum SDK 26 (Android 8.0)
- **Kotlin 1.9.x**
- **Gradle 8.x**
- Familiarity with:
  - Kotlin Coroutines & Flow
  - Jetpack Compose
  - Hilt Dependency Injection
  - Android BLE APIs

### Getting Started

1. **Review the Overview** - Start with [00-OVERVIEW.md](./00-OVERVIEW.md) to understand the full scope
2. **Follow Phases Sequentially** - Each phase builds on the previous
3. **Test Incrementally** - Verify each component before moving forward
4. **Reference Flutter Code** - Use existing Flutter implementation as specification

---

## Technology Stack

### Core Technologies

| Category | Technology | Purpose |
|----------|------------|---------|
| **Language** | Kotlin 1.9.x | Primary development language |
| **UI Framework** | Jetpack Compose | Declarative UI toolkit |
| **DI Framework** | Hilt | Dependency injection |
| **Async** | Coroutines + Flow | Asynchronous programming |
| **Navigation** | Compose Navigation | Screen navigation |

### Android Jetpack Components

| Component | Usage |
|-----------|-------|
| **ViewModel** | UI state management with lifecycle awareness |
| **DataStore** | Preferences and settings storage |
| **Security Crypto** | Encrypted SharedPreferences |
| **Work Manager** | Background task scheduling (if needed) |

### Third-Party Libraries

| Library | Purpose |
|---------|---------|
| **Timber** | Logging |
| **Accompanist** | Compose utilities (permissions, system UI) |
| **Nordic BLE Library** | Simplified BLE operations (optional) |
| **Bouncy Castle** | Cryptographic operations |

### Testing

| Framework | Purpose |
|-----------|---------|
| **JUnit 5** | Unit testing |
| **MockK** | Kotlin mocking |
| **Turbine** | Flow testing |
| **Compose Testing** | UI testing |

---

## Estimated Timeline

| Phase Group | Phases | Duration |
|-------------|--------|----------|
| **Foundation** | 01-05 | 2.5-4 days |
| **Core Services** | 06-09 | 3.5-5.5 days |
| **UI Layer** | 10-13 | 4-5 days |
| **Finalization** | 14-15 | 1.5-3 days |
| **Total** | All | **12-18 days** |

> **Note:** Timeline assumes a single developer working full-time. Adjust based on team size and experience level.

---

## Key Architectural Decisions

### Pattern: MVVM + Unidirectional Data Flow (UDF)

```
┌─────────────────────────────────────────────────┐
│                    UI Layer                      │
│  ┌─────────┐    ┌──────────┐    ┌───────────┐  │
│  │ Screens │◄───│ ViewModels│◄───│ UI State  │  │
│  └────┬────┘    └─────┬────┘    └───────────┘  │
│       │               │                         │
│       ▼               ▼                         │
│   User Events    State Updates                  │
└───────┼───────────────┼─────────────────────────┘
        │               │
        ▼               ▼
┌─────────────────────────────────────────────────┐
│               Domain Layer                       │
│  ┌───────────┐    ┌─────────────────────────┐  │
│  │ Use Cases │◄───│ Repository Interfaces   │  │
│  └───────────┘    └─────────────────────────┘  │
└─────────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────┐
│                Data Layer                        │
│  ┌──────────────┐  ┌────────────┐  ┌─────────┐ │
│  │ Repositories │  │  Services  │  │DataStore│ │
│  └──────────────┘  └────────────┘  └─────────┘ │
└─────────────────────────────────────────────────┘
```

### Design Principles

1. **Single Source of Truth** - State flows down, events flow up
2. **Separation of Concerns** - Clear boundaries between layers
3. **Testability** - All business logic is unit testable
4. **Kotlin Idioms** - Leverage language features (sealed classes, extension functions, etc.)

---

## Project Structure Preview

```
app/
├── src/main/java/com/example/speech2prompt/
│   ├── MainActivity.kt
│   ├── Speech2PromptApplication.kt
│   │
│   ├── data/                    # Data Layer
│   │   ├── repository/
│   │   ├── local/
│   │   └── ble/
│   │
│   ├── domain/                  # Domain Layer
│   │   ├── model/
│   │   ├── repository/
│   │   └── usecase/
│   │
│   ├── di/                      # Dependency Injection
│   │   └── modules/
│   │
│   ├── service/                 # Android Services
│   │   ├── ble/
│   │   └── speech/
│   │
│   └── ui/                      # UI Layer
│       ├── components/
│       ├── screens/
│       ├── navigation/
│       └── theme/
│
├── src/test/                    # Unit Tests
└── src/androidTest/             # Instrumentation Tests
```

---

## References

- [Android Developers - App Architecture](https://developer.android.com/topic/architecture)
- [Jetpack Compose Documentation](https://developer.android.com/jetpack/compose)
- [Hilt Dependency Injection](https://developer.android.com/training/dependency-injection/hilt-android)
- [Kotlin Coroutines Guide](https://kotlinlang.org/docs/coroutines-guide.html)
- [Android BLE Guide](https://developer.android.com/guide/topics/connectivity/bluetooth/ble-overview)

---

## Contributing

When implementing phases:

1. Create a feature branch for each phase
2. Follow the Kotlin coding conventions
3. Write tests alongside implementation
4. Document any deviations from the plan
5. Update this README if scope changes

---

*Last Updated: Phase planning complete*
