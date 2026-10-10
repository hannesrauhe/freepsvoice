package eu.rauhe.freepsvoice

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Rejected credentials — usually a short-lived token that has expired. */
class AuthException(message: String) : Exception(message)

/** One entry of the Mistral model catalogue. */
data class ModelInfo(
    val id: String,
    val name: String,
    val chat: Boolean,
    val transcribes: Boolean,
)

/** Mistral API: transcription, chat and model catalogue. The key never leaves the device. */
class Mistral(
    private val apiKey: String,
    private val mistralBase: String = Settings.MISTRAL_BASE,
    private val llmBase: String = mistralBase,
) {
    private val http = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    fun transcribe(wav: ByteArray, model: String): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "command.wav",
                wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("language", "de")
            .build()
        return execute("$mistralBase/audio/transcriptions", body) {
            JSONObject(it).optString("text").trim()
        }
    }

    /** Returns the raw content of the first choice (expected: a JSON object). */
    fun chat(system: String, user: String, model: String): String {
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        return execute("$llmBase/chat/completions", payload.toString().toRequestBody(JSON)) {
            JSONObject(it)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        }
    }

    /** Model catalogue, deprecated entries dropped. */
    fun listModels(): List<ModelInfo> = execute("$mistralBase/models", null) { text ->
        val data = JSONObject(text).getJSONArray("data")
        (0 until data.length()).map { data.getJSONObject(it) }
            .filter { it.isNull("deprecation") }
            .map { o ->
                val cap = o.optJSONObject("capabilities") ?: JSONObject()
                ModelInfo(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    chat = cap.optBoolean("completion_chat"),
                    transcribes = cap.optBoolean("audio_transcription"),
                )
            }
            .sortedBy { it.id }
    }

    /** A null [body] means GET. Throws with a short server excerpt on a non-2xx answer. */
    private fun <T> execute(url: String, body: RequestBody?, parse: (String) -> T): T {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $apiKey")
            .let { if (body == null) it.get() else it.post(body) }
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string() ?: ""
            if (res.code == 401) throw AuthException(expiryMessage(text))
            if (!res.isSuccessful) error("HTTP ${res.code}: ${text.take(200)}")
            return parse(text)
        }
    }

    /** Turn a 401 body into a German hint, keeping the expiry date if the server sent one. */
    private fun expiryMessage(body: String): String {
        val detail = runCatching { JSONObject(body).optString("detail") }.getOrDefault("")
        val date = Regex("expired on ([0-9]{4}-[0-9]{2}-[0-9]{2})").find(detail)?.groupValues?.get(1)
        return if (date != null) "API-Key abgelaufen am $date — neuer Key nötig."
        else "API-Key abgelehnt (401) — Key prüfen."
    }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}
