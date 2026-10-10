package eu.rauhe.freepsvoice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    private val proximity by lazy { ProximityWake(this) }
    private val vm: AppViewModel by viewModels()
    private val poll = object : Runnable {
        override fun run() {
            vm.refreshAlerts()
            window.decorView.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        // Wall panel: the screen must not sleep while the app is open.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // On wake: screen on and app drawn over the keyguard, no swipe screen visible.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val s = Settings(this)
        if (s.kioskEnabled) {
            Kiosk.enable(this)
            Kiosk.applySchedule(this)
        }
        updateProximity()
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) { App() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        proximity.unregister()
    }

    /** Poll while the screen is on; while it is off, an alarm covers urgent alerts.
     *  Arm on PAUSE, not STOP: with setShowWhenLocked the activity stays STARTED
     *  when the screen sleeps, so onStop would never run. onResume (not onStart)
     *  because dialogs only pause/resume the activity. */
    override fun onResume() {
        super.onResume()
        AlertWatch.cancel(this)
        window.decorView.post(poll)
    }

    override fun onPause() {
        super.onPause()
        window.decorView.removeCallbacks(poll)
        if (Settings(this).kioskEnabled) AlertWatch.arm(this)
    }

    /** Must stay registered while paused: the screen-off pause may not disarm the wake. */
    fun updateProximity() {
        val s = Settings(this)
        if (s.kioskEnabled && s.kioskProximity && proximity.available) proximity.register()
        else proximity.unregister()
    }

    /** Any touch keeps the panel awake; the idle timer restarts. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        proximity.userActive()
    }

    companion object {
        private const val POLL_MS = 60_000L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val vm: AppViewModel = viewModel()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var showSettings by remember { mutableStateOf(false) }
    var showAlerts by remember { mutableStateOf(false) }
    var showSensors by remember { mutableStateOf(false) }
    var editSensors by remember { mutableStateOf(false) }
    var kioskOn by remember { mutableStateOf(vm.settings.kioskEnabled) }
    // Refresh after the settings screen may have changed it.
    LaunchedEffect(showSettings) { kioskOn = vm.settings.kioskEnabled }
    LaunchedEffect(Unit) { vm.refreshAlerts() }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (!granted) vm.reportStatus("Mikrofon-Berechtigung verweigert.") }

    fun enableKiosk() {
        vm.settings.kioskEnabled = true
        vm.settingsSaved()
        Kiosk.applySchedule(context)
        (context as? MainActivity)?.updateProximity()
        (context as? Activity)?.let { Kiosk.enable(it) }
        kioskOn = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("freeps voice") },
                actions = {
                    if (vm.sensorProps.isNotEmpty()) {
                        IconButton(onClick = { showSensors = true }) {
                            Icon(Icons.Default.List, contentDescription = "Sensoren",
                                modifier = Modifier.size(32.dp))
                        }
                    }
                    val groups = vm.alertGroups
                    if (groups != null && groups.total > 0) {
                        val colors = MaterialTheme.colorScheme
                        val tint = when {
                            groups.urgent.isNotEmpty() -> colors.error
                            groups.notice.isNotEmpty() -> colors.tertiary
                            else -> colors.onSurfaceVariant
                        }
                        IconButton(onClick = { showAlerts = true }) {
                            BadgedBox(
                                badge = {
                                    if (groups.urgent.isNotEmpty()) Badge { Text("${groups.urgent.size}") }
                                },
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = "Meldungen", tint = tint,
                                    modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                    if (!kioskOn && Kiosk.isOwner(context)) {
                        TextButton(onClick = ::enableKiosk) { Text("Wandbetrieb an") }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Einstellungen",
                            modifier = Modifier.size(32.dp))
                    }
                },
            )
        },
    ) { pad ->
        val talk = @Composable {
            TalkButton(
                enabled = !vm.busy,
                recording = vm.recording,
                hasPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED,
                onNeedPermission = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                onStart = {
                    focusManager.clearFocus()
                    vm.startRecording()
                },
                onStop = vm::stopRecordingAndRun,
                onCancel = vm::cancelRecording,
            )
        }
        val output = @Composable {
            if (vm.settings.dryRun) {
                Text(
                    "Trockentest — es wird nichts ausgeführt",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (vm.transcript.isNotEmpty()) {
                Text("\"${vm.transcript}\"", style = MaterialTheme.typography.titleMedium)
            }
            if (vm.reply.isNotEmpty()) {
                Text(vm.reply, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            }
            Text(vm.status, style = MaterialTheme.typography.bodyMedium)
        }
        val input = @Composable {
            var typed by remember { mutableStateOf("") }
            fun send() {
                vm.sendTyped(typed)
                typed = ""
                focusManager.clearFocus()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it },
                    placeholder = { Text("…oder tippen") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { send() }),
                    trailingIcon = {
                        Text(
                            "✕",
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(onClickLabel = "Feld leeren") {
                                    typed = ""
                                    focusManager.clearFocus()
                                }
                                .padding(8.dp),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = ::send, enabled = !vm.busy) { Text("Senden") }
            }
        }

        // Wall panel is landscape: button beside the text fits the short height.
        // Portrait keeps the stacked layout.
        val landscape = LocalConfiguration.current.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        val urgent = vm.alertGroups?.urgent ?: emptyList()
        Column(modifier = Modifier.fillMaxSize().padding(pad)) {
            if (urgent.isNotEmpty()) {
                Text(
                    """⚠ ${urgent.first().text} (${urgent.size})""",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable { showAlerts = true }
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        Box(modifier = Modifier.fillMaxSize().weight(1f).padding(24.dp)) {
            // Sibling behind the content: a tap on empty space dismisses the keyboard;
            // taps on widgets never reach it.
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { focusManager.clearFocus() },
                    ),
            )
            if (landscape) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    talk()
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    ) { output(); input() }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) { talk(); output(); input() }
            }
        }
        }
    }

    if (showSettings) SettingsScreen(
        vm,
        onDismiss = { showSettings = false },
        onEditSensors = { editSensors = true },
    )
    if (showAlerts) AlertsScreen(vm) { showAlerts = false }
    if (showSensors) SensorsScreen(vm, onDismiss = { showSensors = false }, onEdit = { editSensors = true })
    if (editSensors) SensorPickerScreen(vm) { editSensors = false }
}

/**
 * Push-to-talk: press and hold to record, release to send. Dragging off the
 * button before releasing discards the recording.
 */
@Composable
fun TalkButton(
    enabled: Boolean,
    recording: Boolean,
    hasPermission: Boolean,
    onNeedPermission: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val recordingState = rememberUpdatedState(recording)
    val permissionState = rememberUpdatedState(hasPermission)
    Box(
        modifier = Modifier
            .size(180.dp)
            .clip(CircleShape)
            .background(if (recording) colors.errorContainer else colors.primaryContainer)
            .then(
                if (!enabled) Modifier
                else Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        if (!permissionState.value) {
                            onNeedPermission()
                            return@awaitEachGesture
                        }
                        if (recordingState.value) return@awaitEachGesture
                        onStart()
                        if (waitForUpOrCancellation() == null) onCancel() else onStop()
                    }
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            when {
                recording -> "Loslassen"
                enabled -> "Halten\nund sprechen"
                else -> "…"
            },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = if (recording) colors.onErrorContainer else colors.onPrimaryContainer,
        )
    }
}
