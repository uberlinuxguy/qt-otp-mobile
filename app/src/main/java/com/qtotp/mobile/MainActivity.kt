package com.qtotp.mobile

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qtotp.mobile.core.OtpEntry
import com.qtotp.mobile.ui.BiometricGate
import com.qtotp.mobile.ui.CodesScreen
import com.qtotp.mobile.ui.EntryEditorScreen
import com.qtotp.mobile.ui.Phase
import com.qtotp.mobile.ui.QtOtpTheme
import com.qtotp.mobile.ui.ScanScreen
import com.qtotp.mobile.ui.SettingsScreen
import com.qtotp.mobile.ui.SetupScreen
import com.qtotp.mobile.ui.UnlockScreen
import com.qtotp.mobile.ui.VaultViewModel

/**
 * A FragmentActivity because BiometricPrompt requires one.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QtOtpTheme {
                VaultApp()
            }
        }
    }
}

/** Which screen is on top of the unlocked vault. */
private sealed interface Route {
    object Codes : Route
    object Settings : Route
    object Scan : Route
    data class Editor(val entry: OtpEntry?) : Route
}

@Composable
private fun VaultApp() {
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val viewModel: VaultViewModel = viewModel(
        factory = VaultViewModel.factory(activity.application),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()

    val gate = remember(activity) { BiometricGate(activity) }
    val biometricReason = remember(gate) {
        (gate.availability() as? BiometricGate.Availability.Unavailable)?.reason
    }

    var route by remember { mutableStateOf<Route>(Route.Codes) }
    val snackbar = remember { SnackbarHostState() }

    // Keep codes out of screenshots and the app switcher thumbnail.
    DisposableEffect(state.settings.blockScreenshots) {
        if (state.settings.blockScreenshots) {
            activity.window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { }
    }

    // Auto-lock when the app leaves the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onBackgrounded()
                Lifecycle.Event.ON_START -> viewModel.onForegrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Returning to the locked screen should not leave a stale sub-screen behind.
    LaunchedEffect(state.phase) {
        if (state.phase != Phase.UNLOCKED) route = Route.Codes
    }

    // System back should climb back to the code list, not drop out of the app.
    BackHandler(enabled = state.phase == Phase.UNLOCKED && route != Route.Codes) {
        route = Route.Codes
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importVault) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let(viewModel::exportVault) }

    // Flagging the picker first keeps auto-lock from firing while it is open.
    val pickVaultFile = {
        viewModel.onExternalPickerLaunched()
        importLauncher.launch(VAULT_MIME_TYPES)
    }
    val saveVaultCopy = {
        viewModel.onExternalPickerLaunched()
        exportLauncher.launch(VaultStoreDefaults.EXPORT_NAME)
    }

    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    fun startBiometricUnlock() {
        viewModel.biometricUnlockCipher().fold(
            onSuccess = { cipher ->
                gate.authenticate(
                    cipher = cipher,
                    title = "Unlock your vault",
                    subtitle = "Use your fingerprint or face instead of the password",
                    onSuccess = viewModel::unlockWithBiometricCipher,
                    onError = { reason -> reason?.let(viewModel::onBiometricUnavailable) },
                )
            },
            onFailure = {
                // Almost always a changed enrollment, which permanently
                // invalidates the Keystore key; the stored wrapper is dead.
                viewModel.discardBiometricKey(
                    "Biometric unlock was turned off because this device's fingerprint or " +
                        "face data changed. Use your password, then switch it back on.",
                )
            },
        )
    }

    fun startBiometricEnrollment() {
        viewModel.biometricEnrollCipher().fold(
            onSuccess = { cipher ->
                gate.authenticate(
                    cipher = cipher,
                    title = "Turn on biometric unlock",
                    subtitle = "Confirm it is you, so the vault key can be sealed to this device",
                    onSuccess = viewModel::completeBiometricEnrollment,
                    onError = { reason -> reason?.let(viewModel::onBiometricUnavailable) },
                )
            },
            onFailure = {
                viewModel.onBiometricUnavailable("Could not set up biometric unlock on this device.")
            },
        )
    }

    // This Scaffold exists only to host the snackbar, so it takes no window
    // insets of its own: Codes, Settings, Scan and the editor each bring a
    // Scaffold with a top bar, and letting both apply the status-bar inset
    // padded those bars twice and left a band of the wrong colour above them.
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar, Modifier.safeDrawingPadding()) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        // Screens with their own Scaffold only need room for the keyboard,
        // which enableEdgeToEdge() otherwise lets cover the lower fields,
        // because it stops the window itself from resizing.
        val hosted = Modifier
            .padding(padding)
            .imePadding()
        // Setup and Unlock are plain columns, so they take the full set here.
        // safeDrawing already covers the keyboard as well as the system bars.
        val bare = Modifier
            .padding(padding)
            .safeDrawingPadding()
        when (state.phase) {
            Phase.LOADING -> Unit

            Phase.SETUP -> SetupScreen(
                busy = state.busy,
                onPickVault = pickVaultFile,
                onCreate = viewModel::createVault,
                modifier = bare,
            )

            Phase.LOCKED -> UnlockScreen(
                busy = state.busy,
                error = state.unlockError,
                biometricEnabled = state.settings.biometricEnabled && gate.isReady,
                onUnlock = viewModel::unlock,
                onBiometricUnlock = { startBiometricUnlock() },
                onImportDifferent = pickVaultFile,
                modifier = bare,
            )

            Phase.UNLOCKED -> when (val current = route) {
                Route.Codes -> CodesScreen(
                    entries = state.entries,
                    nowSeconds = now,
                    hideCodes = state.settings.hideCodes,
                    revealedEntryId = state.revealedEntryId,
                    onReveal = viewModel::revealEntry,
                    onCopy = viewModel::copyCode,
                    onEdit = { route = Route.Editor(it) },
                    onDelete = viewModel::deleteEntry,
                    onMove = viewModel::moveEntry,
                    onAddManually = { route = Route.Editor(null) },
                    onScan = { route = Route.Scan },
                    onLock = viewModel::lock,
                    onSettings = { route = Route.Settings },
                    modifier = hosted,
                )

                Route.Settings -> SettingsScreen(
                    settings = state.settings,
                    entryCount = state.entries.size,
                    biometricReason = biometricReason,
                    onBack = { route = Route.Codes },
                    onAutoLock = viewModel::setAutoLock,
                    onHideCodes = viewModel::setHideCodes,
                    onBlockScreenshots = viewModel::setBlockScreenshots,
                    onEnableBiometrics = { startBiometricEnrollment() },
                    onDisableBiometrics = viewModel::disableBiometrics,
                    onChangePassword = viewModel::changePassword,
                    onExport = saveVaultCopy,
                    onImport = pickVaultFile,
                    modifier = hosted,
                )

                Route.Scan -> ScanScreen(
                    onScanned = { entry ->
                        viewModel.addEntry(entry)
                        route = Route.Codes
                    },
                    onBack = { route = Route.Codes },
                    modifier = hosted,
                )

                is Route.Editor -> EntryEditorScreen(
                    existing = current.entry,
                    onSave = { entry ->
                        if (current.entry == null) {
                            viewModel.addEntry(entry)
                        } else {
                            viewModel.updateEntry(entry)
                        }
                        route = Route.Codes
                    },
                    onBack = { route = Route.Codes },
                    modifier = hosted,
                )
            }
        }
    }
}

/**
 * Vault files carry no registered MIME type, and pickers routinely report
 * ".otpv" as octet-stream or as nothing at all, so accept anything and let
 * the header check reject a wrong pick.
 */
private val VAULT_MIME_TYPES = arrayOf("*/*")

private object VaultStoreDefaults {
    const val EXPORT_NAME = "vault.otpv"
}
