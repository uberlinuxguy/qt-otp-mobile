package com.qtotp.mobile.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qtotp.mobile.core.OtpEntry
import com.qtotp.mobile.core.Totp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodesScreen(
    entries: List<OtpEntry>,
    nowSeconds: Double,
    hideCodes: Boolean,
    revealedEntryId: String?,
    onReveal: (String?) -> Unit,
    onCopy: (OtpEntry, String) -> Unit,
    onEdit: (OtpEntry) -> Unit,
    onDelete: (OtpEntry) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onAddManually: () -> Unit,
    onScan: () -> Unit,
    onLock: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var addMenuOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<OtpEntry?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                // The mark stands in for the app name, and it is a tall shape,
                // so the bar is raised past the default 64dp to give it room.
                title = { VaultMark(height = 56.dp, contentDescription = "qt-otp") },
                expandedHeight = 72.dp,
                colors = qtOtpTopAppBarColors(),
                actions = {
                    IconButton(onClick = onLock) {
                        Icon(Icons.Filled.Lock, contentDescription = "Lock vault")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { addMenuOpen = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add") },
                )
                DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Scan a QR code") },
                        leadingIcon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                        onClick = {
                            addMenuOpen = false
                            onScan()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Enter a secret") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            addMenuOpen = false
                            onAddManually()
                        },
                    )
                }
            }
        },
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyState(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    val index = entries.indexOf(entry)
                    CodeRow(
                        entry = entry,
                        nowSeconds = nowSeconds,
                        masked = hideCodes && revealedEntryId != entry.id,
                        canMoveUp = index > 0,
                        canMoveDown = index < entries.lastIndex,
                        onReveal = { onReveal(entry.id) },
                        onCopy = { code -> onCopy(entry, code) },
                        onEdit = { onEdit(entry) },
                        onDelete = { pendingDelete = entry },
                        onMoveUp = { onMove(index, index - 1) },
                        onMoveDown = { onMove(index, index + 1) },
                    )
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this code?") },
            text = {
                Text(
                    "${entry.label} will be removed from the vault. If this is your only " +
                        "second factor for that account, you may lose access to it.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(entry)
                        pendingDelete = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No codes yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Use Add to scan a QR code or type a secret in by hand.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CodeRow(
    entry: OtpEntry,
    nowSeconds: Double,
    masked: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onReveal: () -> Unit,
    onCopy: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    // A secret that no longer decodes should not take the whole list down.
    val code = remember(entry, (nowSeconds / entry.period).toLong()) {
        runCatching { entry.code(nowSeconds) }.getOrNull()
    }
    val remaining = entry.remaining(nowSeconds)
    val fraction = (remaining / entry.period).toFloat().coerceIn(0f, 1f)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                when {
                    code == null -> Unit
                    masked -> onReveal()
                    else -> onCopy(code)
                }
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                when {
                    code == null -> Text(
                        "Unreadable secret",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    masked -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "• • • • • •",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 26.sp,
                            letterSpacing = 2.sp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.Visibility,
                            contentDescription = "Reveal code",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> Text(
                        Totp.formatCode(code),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        fontSize = 28.sp,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (entry.notes.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        entry.notes.lineSequence().first(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            CountdownRing(fraction = fraction, remaining = remaining)

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    if (canMoveUp) {
                        DropdownMenuItem(
                            text = { Text("Move up") },
                            leadingIcon = { Icon(Icons.Filled.ArrowUpward, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onMoveUp()
                            },
                        )
                    }
                    if (canMoveDown) {
                        DropdownMenuItem(
                            text = { Text("Move down") },
                            leadingIcon = { Icon(Icons.Filled.ArrowDownward, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onMoveDown()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/** Seconds left in the step, as a ring that empties and turns warm near the end. */
@Composable
private fun CountdownRing(fraction: Float, remaining: Double) {
    val animated by animateFloatAsState(targetValue = fraction, label = "countdown")
    val warning = remaining <= 5
    val ringColor = if (warning) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    Box(
        modifier = Modifier
            .size(42.dp)
            .drawBehind {
                val stroke = 4.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke),
                )
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            remaining.toInt().coerceAtLeast(0).toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = ringColor,
        )
    }
}
