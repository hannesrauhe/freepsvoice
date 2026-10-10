package eu.rauhe.freepsvoice

import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Big back arrow — the plain text button was hard to hit on this display. */
@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück",
            modifier = Modifier.size(32.dp))
    }
}

/** Read-only time field; tap opens the system time picker, ✕ clears it. */
@Composable
fun RowScope.TimeField(label: String, value: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    fun pick() {
        val parts = value.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 7
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        TimePickerDialog(context, { _, hh, mm ->
            onChange("%02d:%02d".format(hh, mm))
        }, h, m, true).show()
    }
    Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            OutlinedTextField(
                value = if (value == Kiosk.OFF) "aus" else value,
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // The text field consumes taps itself; the clickable must sit on top.
            Box(Modifier.matchParentSize().clickable(onClickLabel = label, onClick = ::pick))
        }
        Text(
            "✕",
            modifier = Modifier
                .padding(start = 4.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = "Aus") { onChange(Kiosk.OFF) }
                .padding(8.dp),
        )
    }
}

/** Dropdown of known model ids, always including the saved value so custom ids survive. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPicker(
    label: String,
    selected: String,
    options: List<String>,
    loading: Boolean,
    error: String,
    onSelect: (String) -> Unit,
    onReload: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val items = (listOf(selected) + options).filter { it.isNotEmpty() }.distinct()

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected, onValueChange = { onSelect(it) },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            supportingText = {
                val note = when {
                    loading -> "Lade Modelle…"
                    error.isNotEmpty() -> "$error (Neu laden)"
                    options.isEmpty() -> "Keine Modelle geladen — frei eintippbar"
                    else -> ""
                }
                if (note.isNotEmpty()) {
                    Text(note, modifier = Modifier.clickable(onClickLabel = null, onClick = onReload))
                }
            },
            trailingIcon = {
                Text("▾", modifier = Modifier.clickable(onClickLabel = null) { expanded = !expanded })
            },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach {
                DropdownMenuItem(
                    text = { Text(it) },
                    onClick = { onSelect(it); expanded = false },
                )
            }
        }
    }
}
