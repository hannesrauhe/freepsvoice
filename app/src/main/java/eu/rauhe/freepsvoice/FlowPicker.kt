package eu.rauhe.freepsvoice

import org.json.JSONObject

/** LLM flow picker — system prompt and rules ported verbatim from the Python app. */
object FlowPicker {
    // trimMargin() is a call, so this cannot be a const val.
    private val SYSTEM = """
        |Du bist die Sprachsteuerung eines Smart-Home-Roboters.
        |Der Nutzer hat ein Kommando gesprochen (transkribiert, kann Fehler enthalten).
        |Wähle GENAU EINEN Flow aus der angegebenen Liste, der das Kommando ausführt.
        |Regeln:
        |- Antworte ausschließlich mit einem JSON-Objekt ohne Markdown: {"flow": "<Name>", "reply_ok": "<kurz>", "reply_error": "<kurz>"}
        |- "flow" ist der exakte Key aus der Liste. Passt kein Flow, dann null.
        |- Flows mit Kind 'helper' sind interne Bausteine und werden NIE gewählt.
        |- Cron-/Event-Flows (minutely, hourly, mqtt, alert, influx, cron) sind Hintergrundautomatisierungen und nur wählen, wenn der Nutzer sie explizit meint.
        |- "reply_ok" ist ein kurzer deutscher Satz (max 10 Wörter) als Bestätigung, dass der Flow ausgeführt wurde — bzw. eine knappe Absage, wenn kein Flow passt. Passt kein Flow, ist reply_error irrelevant (leerer String).
        |- "reply_error" ist ein kurzer deutscher Satz (max 10 Wörter) für den Fall, dass der Flow fehlgeschlagen ist (z.B. 'Das hat mit dem Flow nicht geklappt.').
        |- Keine Anführungszeichen, keine Emojis.
        |- Verstanden?
    """.trimMargin()

    fun catalogue(flows: Map<String, JSONObject>): String =
        flows.toSortedMap().entries.joinToString("\n") { (name, meta) ->
            val kind = meta.optString("Kind").ifEmpty { "?" }
            val desc = meta.optString("Description").trim().split(Regex("\\s+")).joinToString(" ")
            "$name | $kind | $desc"
        }

    fun userPrompt(transcript: String, flows: Map<String, JSONObject>) =
        "Verfügbare Flows (name | kind | beschreibung):\n${catalogue(flows)}\n\nGesprochenes Kommando: $transcript"

    /** Parse the model's JSON, tolerating code fences and surrounding prose. */
    fun extractJson(content: String): JSONObject? {
        var text = content.trim()
        if (text.startsWith("```")) {
            text = text.trim('`')
            if (text.lowercase().startsWith("json")) text = text.substring(4)
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }

    data class Pick(val flow: String?, val replyOk: String, val replyError: String)

    /** Any transport/parse failure yields an apology so the caller always shows something. */
    fun pick(mistral: Mistral, transcript: String, flows: Map<String, JSONObject>,
             model: String): Pick {
        val content = try {
            mistral.chat(SYSTEM, userPrompt(transcript, flows), model)
        } catch (e: AuthException) {
            return Pick(null, e.message ?: "API-Key abgelaufen.", "")
        } catch (e: Exception) {
            return Pick(null, "Ich habe die Verbindung nicht hingekriegt.", "")
        }
        val data = extractJson(content)
            ?: return Pick(null, "Ich habe dich nicht verstanden.", "")
        val name = data.optString("flow").takeIf { it.isNotEmpty() && it != "null" }
        val replyOk = (data.optString("reply_ok").ifEmpty { data.optString("reply") }).trim()
        val replyError = data.optString("reply_error").trim()
        if (name != null && name !in flows) return Pick(null, replyOk.ifEmpty { "Dafür kenne ich keinen Flow." }, "")
        return Pick(name, replyOk, replyError)
    }
}
