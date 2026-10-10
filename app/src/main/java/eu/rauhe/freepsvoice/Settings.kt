package eu.rauhe.freepsvoice

import android.content.Context

/** Local settings; every value here is editable from the settings screen. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun get(key: String, default: String) = prefs.getString(key, default) ?: default
    private fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()

    var mistralKey: String
        get() = get("mistral_key", "")
        set(v) = put("mistral_key", v.trim())

    var freepsUrl: String
        get() = get("freeps_url", DEFAULT_FREEPS).trimEnd('/')
        set(v) = put("freeps_url", v.trim().trimEnd('/'))

    /** Base URL for STT and the model catalogue. */
    var mistralBase: String
        get() = get("mistral_base", MISTRAL_BASE).trimEnd('/')
        set(v) = put("mistral_base", v.trim().trimEnd('/'))

    /** Chat endpoint; may be any OpenAI-compatible server. Defaults to [mistralBase]. */
    var llmBase: String
        get() = get("llm_base", "").ifEmpty { mistralBase }.trimEnd('/')
        set(v) = put("llm_base", v.trim().trimEnd('/'))

    var llmModel: String
        get() = get("llm_model", DEFAULT_LLM_MODEL)
        set(v) = put("llm_model", v.trim())

    var sttModel: String
        get() = get("stt_model", DEFAULT_STT_MODEL)
        set(v) = put("stt_model", v.trim())

    /** Test mode: no Mistral calls, no flow execution. */
    var dryRun: Boolean
        get() = prefs.getBoolean("dry_run", false)
        set(v) = prefs.edit().putBoolean("dry_run", v).apply()

    /** Wall mode: lock task + schedule; only effective once the app is device owner. */
    var kioskEnabled: Boolean
        get() = prefs.getBoolean("kiosk_enabled", false)
        set(v) = prefs.edit().putBoolean("kiosk_enabled", v).apply()

    /** "HH:mm" or Kiosk.OFF. */
    var kioskOnTime: String
        get() = get("kiosk_on", "06:00")
        set(v) = put("kiosk_on", v)

    var kioskOffTime: String
        get() = get("kiosk_off", "01:00")
        set(v) = put("kiosk_off", v)

    /** Screen sleeps when nobody is near, wakes on approach (needs proximity sensor). */
    var kioskProximity: Boolean
        get() = prefs.getBoolean("kiosk_proximity", false)
        set(v) = prefs.edit().putBoolean("kiosk_proximity", v).apply()

    /** Sensor properties shown on the sensors screen, in display order. */
    var sensorProps: List<String>
        get() = (prefs.getString("sensor_props", "") ?: "").split("\n").filter { it.isNotBlank() }
        set(v) = put("sensor_props", v.joinToString("\n"))

    companion object {
        const val DEFAULT_FREEPS = "http://raspi.fritz.box:8080"
        const val MISTRAL_BASE = "https://api.mistral.ai/v1"
        const val DEFAULT_LLM_MODEL = "ministral-14b-2512"
        const val DEFAULT_STT_MODEL = "voxtral-mini-2602"

        /** Offline stand-ins so the pickers are usable without a key. */
        val DRY_MODELS = listOf(
            ModelInfo(DEFAULT_LLM_MODEL, DEFAULT_LLM_MODEL, chat = true, transcribes = false),
            ModelInfo("ministral-8b-2512", "ministral-8b-2512", chat = true, transcribes = false),
            ModelInfo("mistral-small-latest", "mistral-small-latest", chat = true, transcribes = false),
            ModelInfo(DEFAULT_STT_MODEL, DEFAULT_STT_MODEL, chat = false, transcribes = true),
            ModelInfo("voxtral-mini-latest", "voxtral-mini-latest", chat = false, transcribes = true),
        )
    }
}
