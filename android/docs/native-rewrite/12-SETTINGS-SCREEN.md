# Phase 12: Settings Screen

### Goal
App configuration and preferences.

### PreferencesRepository (data/repository/PreferencesRepository.kt)
```kotlin
@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    
    // Speech settings
    var selectedLocale: String
        get() = prefs.getString("locale", "en-US") ?: "en-US"
        set(value) = prefs.edit().putString("locale", value).apply()
    
    var showPartialResults: Boolean
        get() = prefs.getBoolean("partial_results", true)
        set(value) = prefs.edit().putBoolean("partial_results", value).apply()
    
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", true)
        set(value) = prefs.edit().putBoolean("keep_screen_on", value).apply()
    
    // Voice command toggles
    var enterEnabled: Boolean
        get() = prefs.getBoolean("cmd_enter", true)
        set(value) = prefs.edit().putBoolean("cmd_enter", value).apply()
    
    var selectAllEnabled: Boolean
        get() = prefs.getBoolean("cmd_select_all", true)
        set(value) = prefs.edit().putBoolean("cmd_select_all", value).apply()
    
    var copyEnabled: Boolean
        get() = prefs.getBoolean("cmd_copy", true)
        set(value) = prefs.edit().putBoolean("cmd_copy", value).apply()
    
    var pasteEnabled: Boolean
        get() = prefs.getBoolean("cmd_paste", true)
        set(value) = prefs.edit().putBoolean("cmd_paste", value).apply()
    
    var cutEnabled: Boolean
        get() = prefs.getBoolean("cmd_cut", true)
        set(value) = prefs.edit().putBoolean("cmd_cut", value).apply()
    
    var cancelEnabled: Boolean
        get() = prefs.getBoolean("cmd_cancel", true)
        set(value) = prefs.edit().putBoolean("cmd_cancel", value).apply()
    
    // Connection settings
    var autoReconnect: Boolean
        get() = prefs.getBoolean("auto_reconnect", true)
        set(value) = prefs.edit().putBoolean("auto_reconnect", value).apply()
}
```

### SettingsViewModel (presentation/settings/SettingsViewModel.kt)
```kotlin
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val speechService: SpeechService,
    private val bleManager: BleManager,
    private val secureStorage: SecureStorageRepository
) : ViewModel() {
    
    // Speech settings
    private val _selectedLocale = MutableStateFlow(preferencesRepository.selectedLocale)
    val selectedLocale: StateFlow<String> = _selectedLocale
    
    val availableLocales = speechService.availableLocales
    
    private val _showPartialResults = MutableStateFlow(preferencesRepository.showPartialResults)
    val showPartialResults: StateFlow<Boolean> = _showPartialResults
    
    private val _keepScreenOn = MutableStateFlow(preferencesRepository.keepScreenOn)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn
    
    // Voice command states
    private val _enterEnabled = MutableStateFlow(preferencesRepository.enterEnabled)
    val enterEnabled: StateFlow<Boolean> = _enterEnabled
    // ... similar for other commands
    
    // Connection
    private val _autoReconnect = MutableStateFlow(preferencesRepository.autoReconnect)
    val autoReconnect: StateFlow<Boolean> = _autoReconnect
    
    private val _pairedDevices = MutableStateFlow<List<PairedDevice>>(emptyList())
    val pairedDevices: StateFlow<List<PairedDevice>> = _pairedDevices
    
    init {
        loadPairedDevices()
    }
    
    fun setLocale(locale: String) {
        preferencesRepository.selectedLocale = locale
        _selectedLocale.value = locale
        speechService.setLocale(locale)
    }
    
    fun setShowPartialResults(enabled: Boolean) { ... }
    fun setKeepScreenOn(enabled: Boolean) { ... }
    fun setEnterEnabled(enabled: Boolean) { ... }
    // ... other setters
    
    fun forgetPairedDevice(address: String) {
        viewModelScope.launch {
            secureStorage.removePairedDevice(address)
            loadPairedDevices()
        }
    }
    
    private fun loadPairedDevices() {
        viewModelScope.launch {
            _pairedDevices.value = secureStorage.getPairedDevices()
        }
    }
}
```

### SettingsScreen (presentation/settings/SettingsScreen.kt)
```kotlin
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val selectedLocale by viewModel.selectedLocale.collectAsStateWithLifecycle()
    val availableLocales by viewModel.availableLocales.collectAsStateWithLifecycle()
    val showPartialResults by viewModel.showPartialResults.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val enterEnabled by viewModel.enterEnabled.collectAsStateWithLifecycle()
    // ... collect other states
    val pairedDevices by viewModel.pairedDevices.collectAsStateWithLifecycle()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Speech Recognition Section
            item { SettingsHeader("Speech Recognition") }
            
            item {
                LanguageSelector(
                    selectedLocale = selectedLocale,
                    availableLocales = availableLocales,
                    onLocaleSelected = { viewModel.setLocale(it) }
                )
            }
            
            item {
                SwitchPreference(
                    title = "Show partial results",
                    subtitle = "Display text as you speak",
                    checked = showPartialResults,
                    onCheckedChange = { viewModel.setShowPartialResults(it) }
                )
            }
            
            item {
                SwitchPreference(
                    title = "Keep screen on",
                    subtitle = "Prevent screen from sleeping while listening",
                    checked = keepScreenOn,
                    onCheckedChange = { viewModel.setKeepScreenOn(it) }
                )
            }
            
            // Voice Commands Section
            item { SettingsHeader("Voice Commands") }
            
            item {
                CommandToggle(
                    title = "New line / Enter",
                    patterns = listOf("new line", "enter", "next line"),
                    enabled = enterEnabled,
                    onEnabledChange = { viewModel.setEnterEnabled(it) }
                )
            }
            // ... other command toggles
            
            // Connection Section
            item { SettingsHeader("Connection") }
            
            item {
                SwitchPreference(
                    title = "Auto-reconnect",
                    subtitle = "Automatically reconnect to last device",
                    checked = autoReconnect,
                    onCheckedChange = { viewModel.setAutoReconnect(it) }
                )
            }
            
            // Paired Devices
            if (pairedDevices.isNotEmpty()) {
                item { SettingsHeader("Paired Devices") }
                items(pairedDevices) { device ->
                    PairedDeviceItem(
                        device = device,
                        onForget = { viewModel.forgetPairedDevice(device.address) }
                    )
                }
            }
            
            // About Section
            item { SettingsHeader("About") }
            item {
                ListItem(
                    headlineContent = { Text("Version") },
                    supportingContent = { Text(BuildConfig.VERSION_NAME) }
                )
            }
        }
    }
}
```

### Verification
- [ ] Settings persist across app restarts
- [ ] Language selection changes recognition locale
- [ ] Voice command toggles affect command detection
- [ ] Keep screen on prevents sleep when listening
- [ ] Paired devices listed with forget option
- [ ] Auto-reconnect setting respected
