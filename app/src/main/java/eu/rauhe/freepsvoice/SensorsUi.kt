package eu.rauhe.freepsvoice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.res.Configuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorsScreen(vm: AppViewModel, onDismiss: () -> Unit, onEdit: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshSensors() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sensoren") },
                navigationIcon = { BackButton(onDismiss) },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Bearbeiten",
                            modifier = Modifier.size(32.dp))
                    }
                    IconButton(onClick = { vm.refreshSensors() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren",
                            modifier = Modifier.size(32.dp))
                    }
                },
            )
        },
    ) { pad ->
        val groups = vm.sensorGroups
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vm.sensorError.isNotEmpty()) {
                Text(vm.sensorError, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (groups.isEmpty() && !vm.sensorLoading) {
                Text("Keine Eigenschaften gewählt.", style = MaterialTheme.typography.titleMedium)
            } else {
                val landscape = LocalConfiguration.current.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE
                if (landscape && groups.size > 1) {
                    val half = (groups.size + 1) / 2
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) { groups.take(half).forEach { SensorGroupCard(it) } }
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) { groups.drop(half).forEach { SensorGroupCard(it) } }
                    }
                } else {
                    groups.forEach { SensorGroupCard(it) }
                }
            }
        }
    }
}

/** One device type: every sensor of the group as a row, values aligned under its property. */
@Composable
fun SensorGroupCard(group: Sensors.Group) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surfaceVariant.copy(alpha = 0.4f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(group.props.joinToString(" · "), style = MaterialTheme.typography.titleSmall,
            color = colors.primary)
        Row(Modifier.fillMaxWidth()) {
            Text("Sensor", style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant, modifier = Modifier.weight(1.6f))
            group.props.forEach {
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
        }
        group.rows.forEach { (alias, values) ->
            Row(Modifier.fillMaxWidth()) {
                Text(alias, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1.6f))
                values.forEach {
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Property picker: search plus chips ordered by how many sensors have them. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SensorPickerScreen(vm: AppViewModel, onDismiss: () -> Unit) {
    var sel by remember { mutableStateOf(vm.sensorProps) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadSensorUniverse() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Eigenschaften") },
                navigationIcon = { BackButton(onDismiss) },
                actions = {
                    IconButton(onClick = { vm.saveSensorProps(sel); onDismiss() }) {
                        Icon(Icons.Default.Check, contentDescription = "Speichern",
                            modifier = Modifier.size(32.dp))
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vm.sensorUniverseError.isNotEmpty()) {
                Text(vm.sensorUniverseError, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Suchen") }, singleLine = true,
                trailingIcon = {
                    Text("✕", modifier = Modifier
                        .clip(CircleShape)
                        .clickable { query = "" }
                        .padding(8.dp))
                },
            )
            val universe = vm.sensorUniverse
            if (universe == null && vm.sensorUniverseError.isEmpty()) {
                Text("Lade…", style = MaterialTheme.typography.bodyMedium)
            } else if (universe != null) {
                // Selected first in their own order, then the rest by sensor count.
                val rest = Sensors.filter(Sensors.editorOrder(universe), query)
                    .filter { it !in sel }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    sel.forEach { p ->
                        FilterChip(
                            selected = true,
                            onClick = { sel = sel - p },
                            label = { Text(p) },
                            trailingIcon = { Text("✕", modifier = Modifier.padding(start = 4.dp)) },
                        )
                    }
                    rest.forEach { p ->
                        FilterChip(
                            selected = false,
                            onClick = { sel = sel + p },
                            label = { Text("$p (${universe[p]})") },
                        )
                    }
                }
            }
        }
    }
}
