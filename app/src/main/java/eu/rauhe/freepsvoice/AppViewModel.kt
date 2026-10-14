package eu.rauhe.freepsvoice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(app: android.app.Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    val recorder = Recorder()

    var status by mutableStateOf("")
        private set
    var transcript by mutableStateOf("")
        private set
    var reply by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var recording by mutableStateOf(false)
        private set

    /** Shared across screens so opening settings twice does not re-fetch. */
    var models by mutableStateOf<List<ModelInfo>?>(null)
        private set
    var modelsLoading by mutableStateOf(false)
        private set
    var modelsError by mutableStateOf("")
        private set
    var freepsStatus by mutableStateOf("")
    var mistralStatus by mutableStateOf("")
        private set

    init {
        // Fetch early: the picker is then ready when the settings screen opens.
        loadModels()
    }

    fun reportStatus(message: String) {
        status = message
    }

    fun loadModels() {
        if (modelsLoading) return
        if (settings.dryRun) {
            models = Settings.DRY_MODELS
            modelsError = ""
            return
        }
        if (settings.mistralKey.isBlank()) {
            modelsError = "Erst den API-Key setzen."
            return
        }
        if (models != null) return
        modelsLoading = true
        modelsError = ""
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { Mistral(settings.mistralKey, settings.mistralBase).listModels() }
                .onSuccess { models = it }
                .onFailure { modelsError = it.message ?: "Modelle nicht abrufbar." }
            modelsLoading = false
        }
    }

    fun refreshModels() {
        models = null
        loadModels()
    }

    /** Called after the settings screen saved: the key or base URL may have changed. */
    fun settingsSaved() {
        refreshModels()
    }

    var alertGroups by mutableStateOf<Alerts.Groups?>(null)
        private set
    var alertError by mutableStateOf("")
        private set
    var alertsBusy by mutableStateOf(false)
        private set
    /** Silenced alerts are hidden unless the user asks for them. */
    var showSilenced by mutableStateOf(false)

    /** Fetch alerts for the badge and the alerts screen. */
    fun refreshAlerts() {
        viewModelScope.launch(Dispatchers.IO) {
            val alerts = runCatching { AlertsClient(settings.freepsUrl).get(showSilenced) }
            alerts.fold(
                onSuccess = {
                    alertGroups = Alerts.group(it)
                    alertError = ""
                },
                onFailure = { alertError = it.message ?: "keine Verbindung" },
            )
        }
    }

    fun silence(a: Alert, duration: String) = alertAction { it.silence(a, duration) }
    fun resetAlert(a: Alert) = alertAction { it.reset(a) }
    fun resetSilence(a: Alert) = alertAction { it.resetSilence(a) }

    /** Optimistic: the row updates at once, the refresh corrects any failure. */
    private fun alertAction(block: (AlertsClient) -> Boolean) {
        if (alertsBusy) return
        alertsBusy = true
        viewModelScope.launch(Dispatchers.IO) {
            val client = AlertsClient(settings.freepsUrl)
            if (!runCatching { block(client) }.getOrDefault(false)) alertError = "Das hat nicht geklappt."
            runCatching { client.get(showSilenced) }.getOrNull()?.let { alertGroups = Alerts.group(it) }
            alertsBusy = false
        }
    }

    var sensorGroups by mutableStateOf<List<Sensors.Group>>(emptyList())
        private set
    var sensorError by mutableStateOf("")
        private set
    var sensorLoading by mutableStateOf(false)
        private set
    /** Property names with sensor counts, for the picker. */
    var sensorUniverse by mutableStateOf<Map<String, Int>?>(null)
        private set
    var sensorUniverseError by mutableStateOf("")
        private set

    /** Selected properties, so the top bar can hide the icon until one is picked. */
    var sensorProps by mutableStateOf(settings.sensorProps)
        private set

    fun refreshSensors() {
        val props = sensorProps
        sensorGroups = Sensors.groups(props, emptyMap())
        if (props.isEmpty()) return
        sensorLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { SensorsClient(settings.freepsUrl).values(props) }
                .fold(
                    onSuccess = { sensorGroups = Sensors.groups(props, it); sensorError = "" },
                    onFailure = { sensorError = it.message ?: "keine Verbindung" },
                )
            sensorLoading = false
        }
    }

    /** Only needed while the picker is open; 226 properties, 73 KB, ~30 ms. */
    fun loadSensorUniverse() {
        if (sensorUniverse != null) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { SensorsClient(settings.freepsUrl).universe() }
                .fold(
                    onSuccess = { sensorUniverse = it; sensorUniverseError = "" },
                    onFailure = { sensorUniverseError = it.message ?: "keine Verbindung" },
                )
        }
    }

    fun saveSensorProps(props: List<String>) {
        settings.sensorProps = props
        sensorProps = props
        refreshSensors()
    }

    /** Checks the values currently in the settings fields, not the saved ones. */
    fun testConnection(key: String, freepsUrl: String, mistralBase: String, dry: Boolean) {
        freepsStatus = "Prüfe…"
        mistralStatus = if (dry) "übersprungen (Trockentest)" else "Prüfe…"
        viewModelScope.launch(Dispatchers.IO) {
            val flows = runCatching { Freeps(freepsUrl.trim().trimEnd('/')).listFlows().size }
                .fold({ "OK: ${it} Flows" }, { it.message ?: "Fehler" })
            val mistral = when {
                dry -> "übersprungen (Trockentest)"
                key.isBlank() -> "kein Key"
                else -> runCatching {
                    Mistral(key.trim(), mistralBase.trim().trimEnd('/')).listModels().size
                }.fold({ "OK: ${it} Modelle" }, { it.message ?: "Fehler" })
            }
            withContext(Dispatchers.Main) {
                freepsStatus = flows
                mistralStatus = mistral
            }
        }
    }

    /** Pulls the Mistral key from the freeps server, fills the field and tests both connections. */
    fun fetchKey(freepsUrl: String, mistralBase: String, dry: Boolean, onKey: (String) -> Unit) {
        freepsStatus = "Hole Key…"
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching { Freeps(freepsUrl.trim().trimEnd('/')).getMistralKey() }
                .mapCatching { it.ifBlank { error("freeps hat keinen Mistral-Key") } }
            withContext(Dispatchers.Main) {
                res.fold(
                    onSuccess = {
                        onKey(it)
                        testConnection(it, freepsUrl, mistralBase, dry)
                    },
                    onFailure = { freepsStatus = "freeps: ${it.message ?: "Fehler"}" },
                )
            }
        }
    }

    /**
     * Pushes the key from the settings field to the freeps config, so the other
     * devices can fetch it. The key is checked against Mistral first, unless the
     * dry run skips it — a broken key should not overwrite a working server key.
     */
    fun pushKey(key: String, freepsUrl: String, mistralBase: String, dry: Boolean) {
        if (key.isBlank()) {
            mistralStatus = "kein Key"
            return
        }
        if (dry) {
            pushToFreeps(key, freepsUrl, validated = false)
            return
        }
        mistralStatus = "Prüfe Key…"
        viewModelScope.launch(Dispatchers.IO) {
            val valid = runCatching {
                Mistral(key.trim(), mistralBase.trim().trimEnd('/')).listModels().size
            }
            withContext(Dispatchers.Main) {
                valid.fold(
                    onSuccess = {
                        mistralStatus = "OK: ${it} Modelle"
                        pushToFreeps(key, freepsUrl, validated = true)
                    },
                    onFailure = { mistralStatus = "Key ungültig: ${it.message ?: "Fehler"}" },
                )
            }
        }
    }

    private fun pushToFreeps(key: String, freepsUrl: String, validated: Boolean) {
        freepsStatus = "Speichere Key…"
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching { Freeps(freepsUrl.trim().trimEnd('/')).setMistralKey(key.trim()) }
            withContext(Dispatchers.Main) {
                freepsStatus = res.fold(
                    onSuccess = { if (validated) "Key gespeichert." else "Key gespeichert (ungeprüft)." },
                    onFailure = { "freeps: ${it.message ?: "Fehler"}" },
                )
            }
        }
    }

    fun startRecording() {
        if (busy) return
        runCatching { recorder.start() }
            .onFailure { status = it.message ?: "Mikrofonfehler" }
            .onSuccess { recording = true }
    }

    fun stopRecordingAndRun() {
        if (!recording) return
        recording = false
        val wav = recorder.stop()
        if (wav.size < MIN_WAV_BYTES) {
            status = "Zu kurz — Taste gedrückt halten."
            return
        }
        runTurn(wav, null)
    }

    /** Gesture cancelled (finger slid off): discard the recording. */
    fun cancelRecording() {
        if (!recording) return
        recording = false
        recorder.stop()
    }

    fun sendTyped(text: String) {
        if (text.isBlank() || busy) return
        runTurn(null, text)
    }

    private fun runTurn(wav: ByteArray?, typed: String?) {
        busy = true
        transcript = ""
        reply = ""
        viewModelScope.launch(Dispatchers.IO) {
            try {
                VoiceController(settings,
                    onStatus = { status = it },
                    onTranscript = { transcript = it },
                    onReply = { reply = it }
                ).runTurn(wav, typed)
            } finally {
                busy = false
            }
        }
    }

    companion object {
        /** Shorter than a quarter second is a mis-tap, not a command. */
        private const val MIN_WAV_BYTES = 44 + Wav.SAMPLE_RATE / 2
    }
}
