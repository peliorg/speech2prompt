# Phase 1: Project Setup & Core Infrastructure

## Goal

Set up native Android project structure within the existing `android/` folder, completely removing Flutter dependencies and establishing a pure Kotlin/Compose foundation with Hilt dependency injection.

## Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17+
- Familiarity with Kotlin and Gradle Kotlin DSL

---

## Tasks

### 1.1 Remove Flutter Dependencies

#### Files and Folders to Delete

Remove the following from `android/android/`:

```bash
# Delete Flutter-specific directories
rm -rf app/src/main/java/io/flutter/

# Delete Flutter embedding files (if present)
rm -rf app/src/main/java/io/flutter/embedding/

# Delete any generated Flutter files
rm -rf .flutter-plugins
rm -rf .flutter-plugins-dependencies
rm -f local.properties  # Will recreate without Flutter SDK path
```

#### Files to Clean (not delete)

These files need Flutter references removed:

1. **`app/src/main/AndroidManifest.xml`** - Remove Flutter activity and metadata
2. **`app/build.gradle` or `app/build.gradle.kts`** - Remove Flutter plugin
3. **`settings.gradle` or `settings.gradle.kts`** - Remove Flutter module includes
4. **`gradle.properties`** - Remove Flutter-specific properties

---

### 1.2 Configure Gradle for Native Android

#### 1.2.1 Project-level `build.gradle.kts`

Replace the entire contents of `android/android/build.gradle.kts`:

```kotlin
// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
}

// Clean task for the root project
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
```

#### 1.2.2 App-level `build.gradle.kts`

Replace the entire contents of `android/android/app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.speech2prompt"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.speech2prompt"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Kotlin
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.1.0"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Compose BOM - manages all Compose versions
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.animation:animation")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Hilt Dependency Injection
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-android-compiler:2.51.1")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Bluetooth
    implementation("androidx.bluetooth:bluetooth:1.0.0-alpha02")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("app.cash.turbine:turbine:1.2.0")

    // Android Testing
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// KSP configuration for Hilt
ksp {
    arg("dagger.fastInit", "enabled")
    arg("dagger.formatGeneratedSource", "disabled")
    arg("dagger.fullBindingGraphValidation", "WARNING")
}
```

#### 1.2.3 Settings Gradle

Replace `android/android/settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Speech2Prompt"
include(":app")
```

#### 1.2.4 Gradle Properties

Update `android/android/gradle.properties`:

```properties
# Project-wide Gradle settings

# AndroidX package structure
android.useAndroidX=true

# Kotlin code style
kotlin.code.style=official

# Enable parallel builds
org.gradle.parallel=true

# Enable caching
org.gradle.caching=true

# JVM arguments for the Gradle daemon
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8

# Enable non-transitive R classes
android.nonTransitiveRClass=true

# Enable build config generation
android.defaults.buildfeatures.buildconfig=true

# Disable Jetifier (not needed for new projects)
android.enableJetifier=false
```

#### 1.2.5 Gradle Wrapper

Ensure `android/android/gradle/wrapper/gradle-wrapper.properties` uses Gradle 8.9+:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.11.1-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

---

### 1.3 Create Package Structure

Create the following directory structure under `app/src/main/java/com/speech2prompt/`:

```
app/src/main/java/com/speech2prompt/
├── Speech2PromptApplication.kt
├── MainActivity.kt
├── core/
│   ├── bluetooth/
│   │   ├── BluetoothManager.kt
│   │   ├── BluetoothDevice.kt
│   │   ├── BluetoothState.kt
│   │   └── di/
│   │       └── BluetoothModule.kt
│   ├── permissions/
│   │   ├── PermissionManager.kt
│   │   ├── PermissionState.kt
│   │   └── di/
│   │       └── PermissionModule.kt
│   ├── datastore/
│   │   ├── PreferencesDataStore.kt
│   │   └── di/
│   │       └── DataStoreModule.kt
│   └── di/
│       └── CoreModule.kt
├── feature/
│   ├── home/
│   │   ├── HomeScreen.kt
│   │   ├── HomeViewModel.kt
│   │   └── HomeUiState.kt
│   ├── devices/
│   │   ├── DevicesScreen.kt
│   │   ├── DevicesViewModel.kt
│   │   └── DevicesUiState.kt
│   ├── recording/
│   │   ├── RecordingScreen.kt
│   │   ├── RecordingViewModel.kt
│   │   └── RecordingUiState.kt
│   └── settings/
│       ├── SettingsScreen.kt
│       ├── SettingsViewModel.kt
│       └── SettingsUiState.kt
├── navigation/
│   ├── NavGraph.kt
│   ├── NavRoutes.kt
│   └── NavHost.kt
└── ui/
    ├── theme/
    │   ├── Color.kt
    │   ├── Theme.kt
    │   ├── Type.kt
    │   └── Shape.kt
    └── components/
        ├── LoadingIndicator.kt
        ├── ErrorMessage.kt
        └── PermissionDialog.kt
```

#### Create directories with bash:

```bash
cd android/android/app/src/main/java

# Remove old package if exists
rm -rf com/speech2prompt

# Create package structure
mkdir -p com/speech2prompt/{core/{bluetooth/di,permissions/di,datastore/di,di},feature/{home,devices,recording,settings},navigation,ui/{theme,components}}
```

#### Create placeholder files:

```bash
# Create empty placeholder files (will be implemented in later phases)
touch com/speech2prompt/core/bluetooth/{BluetoothManager.kt,BluetoothDevice.kt,BluetoothState.kt}
touch com/speech2prompt/core/bluetooth/di/BluetoothModule.kt
touch com/speech2prompt/core/permissions/{PermissionManager.kt,PermissionState.kt}
touch com/speech2prompt/core/permissions/di/PermissionModule.kt
touch com/speech2prompt/core/datastore/PreferencesDataStore.kt
touch com/speech2prompt/core/datastore/di/DataStoreModule.kt
touch com/speech2prompt/core/di/CoreModule.kt
touch com/speech2prompt/feature/home/{HomeScreen.kt,HomeViewModel.kt,HomeUiState.kt}
touch com/speech2prompt/feature/devices/{DevicesScreen.kt,DevicesViewModel.kt,DevicesUiState.kt}
touch com/speech2prompt/feature/recording/{RecordingScreen.kt,RecordingViewModel.kt,RecordingUiState.kt}
touch com/speech2prompt/feature/settings/{SettingsScreen.kt,SettingsViewModel.kt,SettingsUiState.kt}
touch com/speech2prompt/navigation/{NavGraph.kt,NavRoutes.kt,NavHost.kt}
touch com/speech2prompt/ui/theme/{Color.kt,Theme.kt,Type.kt,Shape.kt}
touch com/speech2prompt/ui/components/{LoadingIndicator.kt,ErrorMessage.kt,PermissionDialog.kt}
```

---

### 1.4 Update AndroidManifest.xml

Replace `android/android/app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Bluetooth permissions -->
    <uses-permission android:name="android.permission.BLUETOOTH" 
        android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" 
        android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" 
        android:usesPermissionFlags="neverForLocation"
        tools:targetApi="s" />

    <!-- Audio permission for recording -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />

    <!-- Location permissions (required for BLE scanning on older devices) -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

    <!-- Network permission -->
    <uses-permission android:name="android.permission.INTERNET" />

    <!-- Foreground service permissions -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

    <!-- Wake lock for keeping connection alive -->
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <!-- Bluetooth feature declarations -->
    <uses-feature
        android:name="android.hardware.bluetooth"
        android:required="true" />
    <uses-feature
        android:name="android.hardware.bluetooth_le"
        android:required="true" />
    <uses-feature
        android:name="android.hardware.microphone"
        android:required="false" />

    <application
        android:name=".Speech2PromptApplication"
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.Speech2Prompt"
        tools:targetApi="34">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@style/Theme.Speech2Prompt"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>

</manifest>
```

#### Create supporting XML files

Create `android/android/app/src/main/res/xml/data_extraction_rules.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <include domain="sharedpref" path="."/>
        <exclude domain="sharedpref" path="device_preferences.xml"/>
    </cloud-backup>
    <device-transfer>
        <include domain="sharedpref" path="."/>
        <exclude domain="sharedpref" path="device_preferences.xml"/>
    </device-transfer>
</data-extraction-rules>
```

Create `android/android/app/src/main/res/xml/backup_rules.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <include domain="sharedpref" path="."/>
    <exclude domain="sharedpref" path="device_preferences.xml"/>
</full-backup-content>
```

Create `android/android/app/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Speech2Prompt</string>
</resources>
```

Create `android/android/app/src/main/res/values/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Speech2Prompt" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowLightStatusBar">true</item>
    </style>
</resources>
```

---

### 1.5 Create Application Class

Create `android/android/app/src/main/java/com/speech2prompt/Speech2PromptApplication.kt`:

```kotlin
package com.speech2prompt

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

/**
 * Main Application class for Speech2Prompt.
 * 
 * Annotated with @HiltAndroidApp to enable Hilt dependency injection
 * throughout the application. This serves as the root of the dependency
 * graph and must be declared in AndroidManifest.xml.
 */
@HiltAndroidApp
class Speech2PromptApplication : Application() {

    companion object {
        private const val TAG = "Speech2PromptApp"
    }

    override fun onCreate() {
        super.onCreate()
        
        Log.d(TAG, "Application onCreate - initializing Speech2Prompt")
        
        // Initialize any app-wide configurations here
        initializeLogging()
    }

    private fun initializeLogging() {
        // Configure logging based on build type
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Debug build - verbose logging enabled")
        }
    }
}
```

---

### 1.6 Create MainActivity

Create `android/android/app/src/main/java/com/speech2prompt/MainActivity.kt`:

```kotlin
package com.speech2prompt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.speech2prompt.ui.theme.Speech2PromptTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Main entry point Activity for Speech2Prompt.
 * 
 * Uses Jetpack Compose for the UI and is annotated with @AndroidEntryPoint
 * to enable Hilt dependency injection into the Activity.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable edge-to-edge display
        enableEdgeToEdge()
        
        setContent {
            Speech2PromptTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainContent()
                }
            }
        }
    }
}

@Composable
private fun MainContent() {
    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            WelcomeMessage()
        }
    }
}

@Composable
private fun WelcomeMessage() {
    Text(
        text = "Speech2Prompt",
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.primary
    )
}

@Preview(showBackground = true)
@Composable
private fun MainContentPreview() {
    Speech2PromptTheme {
        MainContent()
    }
}
```

---

### 1.7 Create Theme Files

Create `android/android/app/src/main/java/com/speech2prompt/ui/theme/Color.kt`:

```kotlin
package com.speech2prompt.ui.theme

import androidx.compose.ui.graphics.Color

// Primary colors
val Primary = Color(0xFF1976D2)
val PrimaryLight = Color(0xFF63A4FF)
val PrimaryDark = Color(0xFF004BA0)

// Secondary colors
val Secondary = Color(0xFF26A69A)
val SecondaryLight = Color(0xFF64D8CB)
val SecondaryDark = Color(0xFF00766C)

// Background colors
val Background = Color(0xFFFAFAFA)
val Surface = Color(0xFFFFFFFF)
val SurfaceVariant = Color(0xFFF5F5F5)

// Dark theme colors
val BackgroundDark = Color(0xFF121212)
val SurfaceDark = Color(0xFF1E1E1E)
val SurfaceVariantDark = Color(0xFF2D2D2D)

// Status colors
val Error = Color(0xFFB00020)
val ErrorDark = Color(0xFFCF6679)
val Success = Color(0xFF4CAF50)
val Warning = Color(0xFFFFC107)

// Text colors
val OnPrimary = Color(0xFFFFFFFF)
val OnSecondary = Color(0xFF000000)
val OnBackground = Color(0xFF1C1B1F)
val OnBackgroundDark = Color(0xFFE6E1E5)
val OnSurface = Color(0xFF1C1B1F)
val OnSurfaceDark = Color(0xFFE6E1E5)
```

Create `android/android/app/src/main/java/com/speech2prompt/ui/theme/Type.kt`:

```kotlin
package com.speech2prompt.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)
```

Create `android/android/app/src/main/java/com/speech2prompt/ui/theme/Shape.kt`:

```kotlin
package com.speech2prompt.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)
```

Create `android/android/app/src/main/java/com/speech2prompt/ui/theme/Theme.kt`:

```kotlin
package com.speech2prompt.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryLight,
    onPrimaryContainer = PrimaryDark,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryLight,
    onSecondaryContainer = SecondaryDark,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    error = Error,
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryLight,
    onPrimary = PrimaryDark,
    primaryContainer = Primary,
    onPrimaryContainer = OnPrimary,
    secondary = SecondaryLight,
    onSecondary = SecondaryDark,
    secondaryContainer = Secondary,
    onSecondaryContainer = OnSecondary,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    error = ErrorDark,
)

@Composable
fun Speech2PromptTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
```

---

### 1.8 Create Proguard Rules

Create `android/android/app/proguard-rules.pro`:

```proguard
# Add project specific ProGuard rules here.

# Keep Hilt-generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Keep classes annotated with @AndroidEntryPoint
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep serializable classes
-keep,includedescriptorclasses class com.speech2prompt.**$$serializer { *; }
-keepclassmembers class com.speech2prompt.** {
    *** Companion;
}
-keepclasseswithmembers class com.speech2prompt.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep Compose runtime classes
-keep class androidx.compose.** { *; }

# Remove debug logs in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
```

---

## Verification Checklist

Run these commands from the `android/android/` directory to verify the setup:

### 1. Clean the project

```bash
./gradlew clean
```

**Expected:** `BUILD SUCCESSFUL`

### 2. Build debug APK

```bash
./gradlew assembleDebug
```

**Expected:** `BUILD SUCCESSFUL` with APK generated at `app/build/outputs/apk/debug/app-debug.apk`

### 3. Run lint checks

```bash
./gradlew lint
```

**Expected:** Completes without critical errors

### 4. Verify no Flutter references

```bash
# Search for Flutter references in the codebase
grep -r "flutter" --include="*.kt" --include="*.java" --include="*.xml" --include="*.gradle*" . | grep -v "Binary"
```

**Expected:** No results (or only comments mentioning removal)

### 5. Install and launch on device/emulator

```bash
./gradlew installDebug
adb shell am start -n com.speech2prompt.debug/com.speech2prompt.MainActivity
```

**Expected:** App launches showing "Speech2Prompt" text centered on screen

### 6. Verify Hilt compilation

```bash
# Check that Hilt generated files exist
ls -la app/build/generated/ksp/debug/java/com/speech2prompt/
```

**Expected:** Contains `Hilt_Speech2PromptApplication.java` and other generated files

---

## Troubleshooting

### Common Issues

#### 1. Gradle sync fails with "Could not resolve..."

**Solution:** Ensure you have internet connection and Google/Maven repositories are accessible. Check `settings.gradle.kts` repositories block.

#### 2. Hilt compilation errors

**Solution:** 
- Ensure `@HiltAndroidApp` is on the Application class
- Ensure `@AndroidEntryPoint` is on MainActivity
- Run `./gradlew clean` and rebuild

#### 3. "Cannot find symbol: BuildConfig"

**Solution:** Ensure `buildFeatures.buildConfig = true` is in `build.gradle.kts` and rebuild.

#### 4. Compose Preview not working

**Solution:** 
- Ensure Android Studio is up to date
- Rebuild project
- Check that `debugImplementation("androidx.compose.ui:ui-tooling")` is present

#### 5. "Namespace not specified" error

**Solution:** Ensure `namespace = "com.speech2prompt"` is in the android block of `app/build.gradle.kts`

---

## Files Created/Modified Summary

| File | Action |
|------|--------|
| `build.gradle.kts` (project) | Replace |
| `build.gradle.kts` (app) | Replace |
| `settings.gradle.kts` | Replace |
| `gradle.properties` | Replace |
| `gradle-wrapper.properties` | Update |
| `AndroidManifest.xml` | Replace |
| `res/xml/data_extraction_rules.xml` | Create |
| `res/xml/backup_rules.xml` | Create |
| `res/values/strings.xml` | Create/Update |
| `res/values/themes.xml` | Create |
| `Speech2PromptApplication.kt` | Create |
| `MainActivity.kt` | Create |
| `ui/theme/Color.kt` | Create |
| `ui/theme/Type.kt` | Create |
| `ui/theme/Shape.kt` | Create |
| `ui/theme/Theme.kt` | Create |
| `proguard-rules.pro` | Create |

---

## Estimated Time: 4-6 hours

| Task | Time |
|------|------|
| Remove Flutter dependencies | 30 min |
| Configure Gradle files | 1-2 hours |
| Create package structure | 30 min |
| Update AndroidManifest | 30 min |
| Create Application & MainActivity | 30 min |
| Create theme files | 30 min |
| Testing & troubleshooting | 1-2 hours |

---

## Next Phase

Once all verification checks pass, proceed to **Phase 2: Bluetooth Infrastructure** where we'll implement the core Bluetooth scanning and connection functionality.
