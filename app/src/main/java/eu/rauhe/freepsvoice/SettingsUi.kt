package eu.rauhe.freepsvoice

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onDismiss: () -> Unit, onEditSensors: () -> Unit) {
    val context = LocalContext.current
    val s = vm.settings
    var key by remember { mutableStateOf(s.mistralKey) }
    var freeps by remember { mutableStateOf(s.freepsUrl) }
    var llmModel by remember { mutableStateOf(s.llmModel) }
    var sttModel by remember { mutableStateOf(s.sttModel) }
    var mistralBase by remember { mutableStateOf(s.mistralBase) }
    var llmBase by remember { mutableStateOf(s.llmBase) }
    var advanced by remember { mutableStateOf(false) }
    var dry by remember { mutableStateOf(s.dryRun) }
    var kiosk by remember { mutableStateOf(s.kioskEnabled) }
    var kioskOn by remember { mutableStateOf(s.kioskOnTime) }
    var kioskOff by remember { mutableStateOf(s.kioskOffTime) }
    var proximity by remember { mutableStateOf(s.kioskProximity) }
    val isOwner = Kiosk.isOwner(context)
    val keyguardSecure = remember {
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardSecure
    }

    LaunchedEffect(Unit) { vm.loadModels() }

    fun save() {
        s.mistralKey = key
        s.freepsUrl = freeps
        s.llmModel = llmModel
        s.sttModel = sttModel
        s.mistralBase = mistralBase
        s.llmBase = llmBase
        s.dryRun = dry
        s.kioskEnabled = kiosk
        s.kioskOnTime = kioskOn
        s.kioskOffTime = kioskOff
        s.kioskProximity = proximity
        vm.settingsSaved()
        Kiosk.applySchedule(context)
        (context as? MainActivity)?.updateProximity()
        val activity = context as? Activity
        if (activity != null) {
            if (kiosk) Kiosk.enable(activity) else Kiosk.disable(activity)
        }
        onDismiss()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = { BackButton(onDismiss) },
                actions = {
                    IconButton(onClick = ::save) {
                        Icon(Icons.Default.Check, contentDescription = "Speichern",
                            modifier = Modifier.size(32.dp))
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = key, onValueChange = { key = it },
                label = { Text("Mistral API-Key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(
                value = freeps, onValueChange = { freeps = it },
                label = { Text("freeps URL") }, singleLine = true,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Trockentest", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Ohne Mistral-Aufrufe, kein Flow wird ausgeführt.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = dry, onCheckedChange = { dry = it })
            }

            ModelPicker("LLM-Modell", llmModel,
                vm.models?.filter { it.chat }?.map { it.id } ?: emptyList(),
                vm.modelsLoading, vm.modelsError,
                onSelect = { llmModel = it },
                onReload = { vm.refreshModels() })

            ModelPicker("Sprach-Erkennungsmodell", sttModel,
                vm.models?.filter { it.transcribes }?.map { it.id } ?: emptyList(),
                vm.modelsLoading, vm.modelsError,
                onSelect = { sttModel = it },
                onReload = { vm.refreshModels() })

            TextButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "Weniger" else "Erweitert")
            }
            if (advanced) {
                OutlinedTextField(
                    value = mistralBase, onValueChange = { mistralBase = it },
                    label = { Text("Mistral Basis-URL") }, singleLine = true,
                )
                OutlinedTextField(
                    value = llmBase, onValueChange = { llmBase = it },
                    label = { Text("LLM Basis-URL") }, singleLine = true,
                )
            }

            HorizontalDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Sensoren", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (vm.sensorProps.isEmpty())
                            "Nichts gewählt. Eigenschaften auswählen, dann erscheint das Symbol oben."
                        else "Gezeigt: ${vm.sensorProps.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onEditSensors) { Text("Wählen") }
            }

            HorizontalDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Wandbetrieb", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (isOwner) "App bleibt vorn; Bildschirm nach Zeitplan."
                        else "Geräte-Eigentümer fehlt — siehe README (adb dpm set-owner).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = kiosk, onCheckedChange = { kiosk = it })
            }
            if (kiosk) {
                if (keyguardSecure) {
                    Text(
                        "Bildschirmsperre auf \"Ohne\" stellen, sonst erscheint beim Aufwachen der Swipe-Bildschirm.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimeField("An um", kioskOn) { kioskOn = it }
                    TimeField("Aus um", kioskOff) { kioskOff = it }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Wach, wenn Hand vor dem Sensor (kein Annähern aus Distanz)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = proximity, onCheckedChange = { proximity = it })
                }
            }

            HorizontalDivider()
            OutlinedButton(onClick = {
                vm.testConnection(key, freeps, mistralBase, dry)
            }) { Text("Verbindung testen") }
            if (vm.testResult.isNotEmpty()) {
                Text(vm.testResult, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
