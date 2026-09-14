package com.qtotp.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.qtotp.mobile.data.AutoLockDelay
import com.qtotp.mobile.data.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    entryCount: Int,
    biometricReason: String?,
    onBack: () -> Unit,
    onAutoLock: (AutoLockDelay) -> Unit,
    onHideCodes: (Boolean) -> Unit,
    onBlockScreenshots: (Boolean) -> Unit,
    onEnableBiometrics: () -> Unit,
    onDisableBiometrics: () -> Unit,
    onChangePassword: (current: String, new: String, confirm: String) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var passwordDialog by remember { mutableStateOf(false) }
    var importConfirm by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = qtOtpTopAppBarColors(),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Locking")
            AutoLockDelay.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAutoLock(option) }
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.autoLock == option,
                        onClick = { onAutoLock(option) },
                    )
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(
                "The vault locks when the app leaves the screen, and its key is wiped from memory.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("Privacy")
            SwitchRow(
                title = "Biometric unlock",
                subtitle = biometricReason
                    ?: "Reopen the vault with a fingerprint or face instead of typing the password.",
                checked = settings.biometricEnabled,
                enabled = biometricReason == null,
                onCheckedChange = { on -> if (on) onEnableBiometrics() else onDisableBiometrics() },
            )
            SwitchRow(
                title = "Hide codes until tapped",
                subtitle = "Codes stay masked in the list; tap one to reveal it.",
                checked = settings.hideCodes,
                onCheckedChange = onHideCodes,
            )
            SwitchRow(
                title = "Block screenshots",
                subtitle = "Keeps codes out of screenshots and the app switcher preview.",
                checked = settings.blockScreenshots,
                onCheckedChange = onBlockScreenshots,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("Vault")
            Text(
                "$entryCount ${if (entryCount == 1) "code" else "codes"} in this vault.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.padding(horizontal = 20.dp)) {
                Button(onClick = { passwordDialog = true }) { Text("Change password") }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.padding(horizontal = 20.dp)) {
                Button(onClick = onExport) { Text("Export a copy") }
            }
            Text(
                "Writes the encrypted vault file as it stands. It stays encrypted with " +
                    "your password, so it is safe to keep in cloud storage and can be " +
                    "opened by the desktop app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.padding(horizontal = 20.dp)) {
                Button(onClick = { importConfirm = true }) { Text("Replace with a file") }
            }
            Text(
                "Imports a vault.otpv from elsewhere, replacing the one on this device. " +
                    "Use this to pick up changes made on the desktop.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("About")
            Text(
                "Vault format: qt-otp-vault v1\n" +
                    "KDF: scrypt N=32768 r=8 p=1, 32-byte key\n" +
                    "Cipher: AES-256-GCM, authenticated header",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (passwordDialog) {
        ChangePasswordDialog(
            onDismiss = { passwordDialog = false },
            onConfirm = { current, new, confirm ->
                passwordDialog = false
                onChangePassword(current, new, confirm)
            },
        )
    }

    if (importConfirm) {
        AlertDialog(
            onDismissRequest = { importConfirm = false },
            title = { Text("Replace this vault?") },
            text = {
                Text(
                    "The vault on this device will be replaced by the file you pick, and " +
                        "any codes only stored here will be gone. Export a copy first if " +
                        "you are not sure.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        importConfirm = false
                        onImport()
                    },
                ) { Text("Pick a file") }
            },
            dismissButton = {
                TextButton(onClick = { importConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (current: String, new: String, confirm: String) -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change password") },
        text = {
            Column {
                Text(
                    "The vault is re-encrypted with a new salt. The file stays readable " +
                        "by the desktop app, which will ask for the new password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = current,
                    onValueChange = { current = it },
                    label = { Text("Current password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = new,
                    onValueChange = { new = it },
                    label = { Text("New password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("New password again") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(current, new, confirm) },
                enabled = current.isNotEmpty() && new.isNotEmpty(),
            ) { Text("Change") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
