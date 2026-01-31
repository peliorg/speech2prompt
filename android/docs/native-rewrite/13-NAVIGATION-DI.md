# Phase 13: Navigation & Dependency Injection Setup

### Goal
Set up Hilt DI and Compose Navigation.

### Navigation Routes (presentation/navigation/Screen.kt)
```kotlin
sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Connection : Screen("connection")
    object Settings : Screen("settings")
    object SpeechTest : Screen("speech_test")
    object BluetoothTest : Screen("bluetooth_test")
}
```

### AppNavigation (presentation/navigation/AppNavigation.kt)
```kotlin
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Home.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToConnection = { navController.navigate(Screen.Connection.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
            )
        }
        
        composable(Screen.Connection.route) {
            ConnectionScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable(Screen.SpeechTest.route) {
            SpeechTestScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable(Screen.BluetoothTest.route) {
            BluetoothTestScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
```

### Hilt Modules

#### AppModule (di/AppModule.kt)
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context = context
}
```

#### RepositoryModule (di/RepositoryModule.kt)
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    
    @Provides
    @Singleton
    fun provideSecureStorageRepository(
        @ApplicationContext context: Context
    ): SecureStorageRepository = SecureStorageRepository(context)
    
    @Provides
    @Singleton
    fun providePreferencesRepository(
        @ApplicationContext context: Context
    ): PreferencesRepository = PreferencesRepository(context)
}
```

#### ServiceModule (di/ServiceModule.kt)
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {
    
    @Provides
    @Singleton
    fun provideBleManager(
        @ApplicationContext context: Context,
        secureStorage: SecureStorageRepository
    ): BleManager = BleManager(context, secureStorage)
    
    @Provides
    @Singleton
    fun provideSpeechService(
        @ApplicationContext context: Context
    ): SpeechService = SpeechService(context)
    
    @Provides
    @Singleton
    fun providePermissionManager(
        @ApplicationContext context: Context
    ): PermissionManager = PermissionManager(context)
    
    @Provides
    fun provideCommandProcessor(
        bleManager: BleManager
    ): CommandProcessor = CommandProcessor { message ->
        bleManager.sendMessage(message)
    }
}
```

### Application Class (Speech2PromptApplication.kt)
```kotlin
@HiltAndroidApp
class Speech2PromptApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize any global configs
    }
}
```

### MainActivity (MainActivity.kt)
```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            Speech2PromptTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost()
                }
            }
        }
    }
}
```

### Verification
- [ ] App launches without DI errors
- [ ] Navigation between all screens works
- [ ] Back button navigates correctly
- [ ] ViewModels receive injected dependencies
- [ ] Singletons are truly single instances
- [ ] No memory leaks from retained ViewModels
