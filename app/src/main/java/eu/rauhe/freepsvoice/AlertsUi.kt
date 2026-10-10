package eu.rauhe.freepsvoice

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(vm: AppViewModel, onDismiss: () -> Unit) {
    var quietOpen by remember { mutableStateOf(false) }
    var chooserFor by remember { mutableStateOf<Alert?>(null) }

    LaunchedEffect(vm.showSilenced) { vm.refreshAlerts() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meldungen") },
                navigationIcon = { BackButton(onDismiss) },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = vm.showSilenced,
                            onCheckedChange = { vm.showSilenced = it },
                        )
                        Text("Stumm", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { vm.refreshAlerts() }) { Text("⟳") }
                    }
                },
            )
        },
    ) { pad ->
        val groups = vm.alertGroups
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (vm.alertError.isNotEmpty()) {
                Text(vm.alertError, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (groups == null) {
                Text("Lade…", style = MaterialTheme.typography.bodyMedium)
            } else if (groups.total == 0) {
                Text("Keine Meldungen.", style = MaterialTheme.typography.titleMedium)
            } else {
                groups.urgent.forEach { AlertRow(vm, it, MaterialTheme.colorScheme.error) { chooserFor = it } }
                groups.notice.forEach { AlertRow(vm, it, MaterialTheme.colorScheme.tertiary) { chooserFor = it } }
                if (groups.quiet.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { quietOpen = !quietOpen }
                            .padding(vertical = 6.dp),
                    ) {
                        Text("⚪", modifier = Modifier.padding(end = 8.dp))
                        Text(
                            "${groups.quiet.size} leise · ${Alerts.quietSummary(groups.quiet)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(if (quietOpen) "▴" else "▾")
                    }
                    if (quietOpen) groups.quiet.forEach { AlertRow(vm, it, null) { chooserFor = it } }
                }
            }
        }
    }

    chooserFor?.let { a ->
        AlertDialog(
            onDismissRequest = { chooserFor = null },
            title = { Text(a.text) },
            text = {
                Column {
                    Alerts.SILENCE_CHOICES.forEach { d ->
                        TextButton(
                            onClick = {
                                chooserFor = null
                                vm.silence(a, d)
                            },
                        ) { Text("$d stumm") }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

/** One alert: dot, text, age, silence (tap = 15 min, long press = choice), reset. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlertRow(vm: AppViewModel, a: Alert, dot: Color?, onPickDuration: () -> Unit = {}) {
    val silenced = a.silenceMs > 0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(
                if (dot != null && !silenced)
                    dot.copy(alpha = 0.08f)
                else Color.Transparent
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            if (silenced) "🔕" else if (dot != null) "●" else "○",
            color = dot ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            a.text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .combinedClickable(
                    onClick = { vm.silence(a, Alerts.SILENCE_DEFAULT) },
                    onLongClick = onPickDuration,
                ),
        )
        if (silenced) {
            Text("noch ${Alerts.age(a.silenceMs)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { vm.resetSilence(a) }) { Text("✕") }
        } else {
            Text(Alerts.age(a.sinceLastMs), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { vm.silence(a, Alerts.SILENCE_DEFAULT) }) { Text("🔕") }
            TextButton(onClick = { vm.resetAlert(a) }) { Text("✕") }
        }
    }
}
