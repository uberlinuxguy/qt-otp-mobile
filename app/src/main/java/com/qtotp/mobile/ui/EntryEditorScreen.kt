package com.qtotp.mobile.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.qtotp.mobile.core.OtpEntry
import com.qtotp.mobile.core.Totp

/**
 * Add or edit one entry.
 *
 * The same field set as the desktop app's entry dialog, so anything created
 * here is expressible there and vice versa.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditorScreen(
    existing: OtpEntry?,
    onSave: (OtpEntry) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var issuer by remember { mutableStateOf(existing?.issuer ?: "") }
    var account by remember { mutableStateOf(existing?.account ?: "") }
    var secret by remember { mutableStateOf(existing?.secret ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var digits by remember { mutableStateOf(existing?.digits ?: Totp.DEFAULT_DIGITS) }
    var period by remember { mutableStateOf((existing?.period ?: Totp.DEFAULT_PERIOD).toString()) }
    var algorithm by remember { mutableStateOf(existing?.algorithm ?: Totp.DEFAULT_ALGORITHM) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add a code" else "Edit code") },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            OutlinedTextField(
                value = issuer,
                onValueChange = { issuer = it },
                label = { Text("Issuer") },
                placeholder = { Text("GitHub") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = account,
                onValueChange = { account = it },
                label = { Text("Account") },
                placeholder = { Text("you@example.com") },
                singleLine = true,
                supportingText = { Text("An issuer or an account is required") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text("Secret") },
                placeholder = { Text("JBSWY3DPEHPK3PXP") },
                supportingText = {
                    Text("Base32. Spaces, dashes and lower case are fine.")
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))
            Text("Digits", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Totp.ALLOWED_DIGITS.forEach { option ->
                    FilterChip(
                        selected = digits == option,
                        onClick = { digits = option },
                        label = { Text(option.toString()) },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Algorithm", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Totp.SUPPORTED_ALGORITHMS.forEach { option ->
                    FilterChip(
                        selected = algorithm == option,
                        onClick = { algorithm = option },
                        label = { Text(option) },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = period,
                onValueChange = { period = it.filter(Char::isDigit).take(4) },
                label = { Text("Period (seconds)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("Almost always 30") },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    // Build through the validating factory so the same rules
                    // the desktop app enforces apply before anything is saved.
                    val result = runCatching {
                        OtpEntry.create(
                            issuer = issuer,
                            account = account,
                            secret = secret,
                            digits = digits,
                            period = period.toIntOrNull() ?: Totp.DEFAULT_PERIOD,
                            algorithm = algorithm,
                            notes = notes,
                            id = existing?.id ?: OtpEntry.newId(),
                            createdAt = existing?.createdAt ?: (System.currentTimeMillis() / 1000.0),
                        )
                    }
                    result.fold(
                        onSuccess = {
                            error = null
                            onSave(it)
                        },
                        onFailure = { failure ->
                            error = failure.message ?: "Those details cannot be saved"
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (existing == null) "Add code" else "Save changes")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
