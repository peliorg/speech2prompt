# Phase 8: Theme & Design System

### Goal
Implement Material3 dark theme matching Flutter design.

### Colors (presentation/theme/Color.kt)
```kotlin
// Primary brand colors
val Primary = Color(0xFF4A90D9)
val PrimaryVariant = Color(0xFF64B5F6)
val Secondary = Color(0xFF03DAC6)

// Background colors
val Background = Color(0xFF1A1A2E)
val Surface = Color(0xFF252541)
val SurfaceVariant = Color(0xFF2D2D4A)

// Status colors
val Connected = Color(0xFF4CAF50)
val Connecting = Color(0xFFFFA726)
val Disconnected = Color(0xFF9E9E9E)
val Error = Color(0xFFE94560)

// Text colors
val OnPrimary = Color.White
val OnBackground = Color.White
val OnBackgroundMuted = Color(0xB3FFFFFF) // 70% white
val OnBackgroundSubtle = Color(0x61FFFFFF) // 38% white
```

### Theme (presentation/theme/Theme.kt)
```kotlin
private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = Primary.copy(alpha = 0.3f),
    secondary = Secondary,
    background = Background,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
    error = Error,
    onBackground = OnBackground,
    onSurface = OnBackground
)

@Composable
fun Speech2PromptTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
```

### Typography (presentation/theme/Typography.kt)
```kotlin
val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp
    )
)
```

### Shapes
```kotlin
val Shapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp)
)
```

### Status Bar Configuration
```kotlin
@Composable
fun ConfigureSystemBars() {
    val systemUiController = rememberSystemUiController()
    val backgroundColor = MaterialTheme.colorScheme.background
    
    SideEffect {
        systemUiController.setSystemBarsColor(
            color = backgroundColor,
            darkIcons = false
        )
    }
}
```

### Verification
- [ ] Dark theme applies to all screens
- [ ] Colors match Flutter app design
- [ ] Status bar styled correctly (dark with light icons)
- [ ] Typography consistent across app
