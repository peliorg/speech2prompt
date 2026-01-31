# Dependencies - Native Android Rewrite

> Complete dependency configuration for the native Android rewrite using latest stable versions as of January 2025.

## Version Catalog (libs.versions.toml)

Create this file at `gradle/libs.versions.toml`:

```toml
[versions]
# Core
kotlin = "2.1.0"
ksp = "2.1.0-1.0.29"
agp = "8.7.3"

# Compose
composeBom = "2025.01.01"
activityCompose = "1.9.3"
navigationCompose = "2.8.5"

# AndroidX
coreKtx = "1.15.0"
lifecycle = "2.8.7"
datastore = "1.1.1"
securityCrypto = "1.0.0"

# Kotlin Libraries
coroutines = "1.10.1"
serialization = "1.8.0"

# Dependency Injection
hilt = "2.51.1"
hiltNavigationCompose = "1.2.0"

# Networking
okhttp = "4.12.0"
retrofit = "2.11.0"

# Testing
junit = "4.13.2"
turbine = "1.2.0"
truth = "1.4.4"
robolectric = "4.14"
mockk = "1.13.13"
androidxTestCore = "1.6.1"
androidxTestRunner = "1.6.2"
androidxTestRules = "1.6.1"
espresso = "3.6.1"

[libraries]
# AndroidX Core
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }

# Compose BOM and Libraries
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
androidx-compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-compose-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
androidx-compose-runtime = { group = "androidx.compose.runtime", name = "runtime" }

# Navigation
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }

# Lifecycle
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-ktx = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }

# DataStore
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }

# Security
androidx-security-crypto = { group = "androidx.security", name = "security-crypto", version.ref = "securityCrypto" }

# Kotlin Coroutines
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }

# Kotlin Serialization
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }

# Hilt
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-android-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
hilt-android-testing = { group = "com.google.dagger", name = "hilt-android-testing", version.ref = "hilt" }
androidx-hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "hiltNavigationCompose" }

# Networking
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-logging = { group = "com.squareup.okhttp3", name = "logging-interceptor", version.ref = "okhttp" }
retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
retrofit-kotlinx-serialization = { group = "com.squareup.retrofit2", name = "converter-kotlinx-serialization", version.ref = "retrofit" }

# Unit Testing
junit = { group = "junit", name = "junit", version.ref = "junit" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
truth = { group = "com.google.truth", name = "truth", version.ref = "truth" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
mockk-android = { group = "io.mockk", name = "mockk-android", version.ref = "mockk" }

# Android Instrumented Testing
androidx-test-core = { group = "androidx.test", name = "core", version.ref = "androidxTestCore" }
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "androidxTestRunner" }
androidx-test-rules = { group = "androidx.test", name = "rules", version.ref = "androidxTestRules" }
espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espresso" }

[bundles]
compose = [
    "androidx-compose-ui",
    "androidx-compose-ui-graphics",
    "androidx-compose-ui-tooling-preview",
    "androidx-compose-material3",
    "androidx-compose-material-icons-extended",
    "androidx-compose-runtime",
]
compose-debug = [
    "androidx-compose-ui-tooling",
    "androidx-compose-ui-test-manifest",
]
lifecycle = [
    "androidx-lifecycle-runtime-ktx",
    "androidx-lifecycle-runtime-compose",
    "androidx-lifecycle-viewmodel-ktx",
    "androidx-lifecycle-viewmodel-compose",
]
coroutines = [
    "kotlinx-coroutines-core",
    "kotlinx-coroutines-android",
]
networking = [
    "okhttp",
    "okhttp-logging",
    "retrofit",
    "retrofit-kotlinx-serialization",
]
testing-unit = [
    "junit",
    "truth",
    "turbine",
    "mockk",
    "kotlinx-coroutines-test",
]
testing-android = [
    "androidx-test-core",
    "androidx-test-runner",
    "androidx-test-rules",
    "espresso-core",
    "mockk-android",
    "hilt-android-testing",
    "androidx-compose-ui-test-junit4",
]

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

---

## Project-Level build.gradle.kts

```kotlin
// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// Clean task
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
```

---

## App-Level build.gradle.kts

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.speech2prompt"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.speech2prompt"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "com.speech2prompt.HiltTestRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("String", "OPENAI_BASE_URL", "\"https://api.openai.com/v1/\"")
            buildConfigField("String", "ANTHROPIC_BASE_URL", "\"https://api.anthropic.com/v1/\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "OPENAI_BASE_URL", "\"https://api.openai.com/v1/\"")
            buildConfigField("String", "ANTHROPIC_BASE_URL", "\"https://api.anthropic.com/v1/\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.bundles.compose.debug)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Lifecycle
    implementation(libs.bundles.lifecycle)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Security
    implementation(libs.androidx.security.crypto)

    // Coroutines
    implementation(libs.bundles.coroutines)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Networking
    implementation(libs.bundles.networking)

    // Unit Testing
    testImplementation(libs.bundles.testing.unit)
    testImplementation(libs.robolectric)

    // Android Instrumented Testing
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.bundles.testing.android)
    kspAndroidTest(libs.hilt.android.compiler)
}
```

---

## settings.gradle.kts

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

---

## gradle-wrapper.properties

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

## Version Compatibility Notes

### Hilt & AGP Compatibility

| Hilt Version | AGP Requirement | Notes |
|--------------|-----------------|-------|
| 2.51.1 | AGP 8.x | **Recommended** - Stable with AGP 8.7.3 |
| 2.52-2.58 | AGP 8.x | May have edge case issues |
| 2.59+ | AGP 9.x | **Do not use** - Requires AGP 9.0 |

**Important**: Hilt 2.51.1 is the recommended stable version for AGP 8.x projects. The Hilt team made breaking changes in 2.59 that require AGP 9.0.

### Kotlin & Compose Compiler

Starting with Kotlin 2.0, the Compose Compiler is bundled with Kotlin. Use the `kotlin-compose` plugin instead of specifying a separate compose compiler version:

```kotlin
// In plugins block
alias(libs.plugins.kotlin.compose)

// No need for composeOptions.kotlinCompilerExtensionVersion
```

### KSP Version Alignment

KSP version must match your Kotlin version. For Kotlin 2.1.0:
- Use KSP `2.1.0-1.0.29`
- Format: `{kotlin-version}-{ksp-version}`

### Compose BOM 2025.01.01 Components

The January 2025 BOM includes:
- Compose UI: 1.7.6
- Compose Material3: 1.3.1
- Compose Foundation: 1.7.6
- Compose Runtime: 1.7.6

### Minimum SDK Considerations

| Feature | Min SDK |
|---------|---------|
| Security Crypto | 23 |
| DataStore | 21 |
| Compose | 21 |
| App (our target) | **26** |

We target SDK 26 to ensure modern API access and reasonable device coverage (~95%+).

---

## Migration from Flutter Dependencies

### Dependency Mapping

| Flutter Package | Native Android Replacement |
|-----------------|---------------------------|
| `flutter_riverpod` | Hilt + StateFlow |
| `http` / `dio` | Retrofit + OkHttp |
| `shared_preferences` | DataStore Preferences |
| `flutter_secure_storage` | EncryptedSharedPreferences |
| `json_serializable` | Kotlin Serialization |
| `go_router` | Navigation Compose |
| `speech_to_text` | Android SpeechRecognizer (native) |
| `audioplayers` | MediaPlayer / ExoPlayer |
| `permission_handler` | ActivityResultContracts |

### State Management Migration

**Flutter (Riverpod):**
```dart
final counterProvider = StateNotifierProvider<CounterNotifier, int>((ref) {
  return CounterNotifier();
});
```

**Native Android (Hilt + StateFlow):**
```kotlin
@HiltViewModel
class CounterViewModel @Inject constructor() : ViewModel() {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()
    
    fun increment() {
        _count.update { it + 1 }
    }
}
```

### Networking Migration

**Flutter (http/dio):**
```dart
final response = await http.post(
  Uri.parse('https://api.openai.com/v1/chat/completions'),
  headers: {'Authorization': 'Bearer $apiKey'},
  body: jsonEncode(requestBody),
);
```

**Native Android (Retrofit):**
```kotlin
interface OpenAiService {
    @POST("chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") auth: String,
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse
}
```

### Storage Migration

**Flutter (SharedPreferences + SecureStorage):**
```dart
final prefs = await SharedPreferences.getInstance();
await prefs.setString('theme', 'dark');

final secureStorage = FlutterSecureStorage();
await secureStorage.write(key: 'api_key', value: apiKey);
```

**Native Android (DataStore + EncryptedSharedPreferences):**
```kotlin
// DataStore for preferences
val Context.dataStore by preferencesDataStore(name = "settings")
suspend fun setTheme(theme: String) {
    dataStore.edit { it[THEME_KEY] = theme }
}

// EncryptedSharedPreferences for secrets
@Provides
fun provideEncryptedPrefs(@ApplicationContext context: Context): SharedPreferences {
    return EncryptedSharedPreferences.create(
        context,
        "secret_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
```

---

## ProGuard Rules (proguard-rules.pro)

```proguard
# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.speech2prompt.**$$serializer { *; }
-keepclassmembers class com.speech2prompt.** {
    *** Companion;
}
-keepclasseswithmembers class com.speech2prompt.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }
```

---

## Dependency Update Strategy

### Recommended Update Cadence

| Category | Frequency | Notes |
|----------|-----------|-------|
| Security patches | Immediate | Monitor security advisories |
| Compose BOM | Monthly | Test UI thoroughly |
| Kotlin/KSP | Quarterly | Major toolchain changes |
| AGP | With Android Studio | Align with IDE updates |
| Hilt | Carefully | Check AGP compatibility |

### Tools for Monitoring Updates

1. **Gradle Versions Plugin**:
   ```kotlin
   plugins {
       id("com.github.ben-manes.versions") version "0.51.0"
   }
   // Run: ./gradlew dependencyUpdates
   ```

2. **Dependabot** (GitHub): Configure `.github/dependabot.yml`

3. **Renovate Bot**: Alternative to Dependabot with more flexibility

---

## Verification Checklist

After setting up dependencies, verify:

- [ ] `./gradlew build` completes successfully
- [ ] `./gradlew test` runs unit tests
- [ ] `./gradlew connectedAndroidTest` runs instrumented tests
- [ ] App launches on emulator/device
- [ ] ProGuard/R8 release build works
- [ ] No duplicate class warnings
- [ ] Compose previews render in Android Studio
