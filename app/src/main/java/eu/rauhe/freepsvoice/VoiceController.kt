package eu.rauhe.freepsvoice

import org.json.JSONObject

/** One command, start to finish: STT -> pick -> execute -> reply. */
class VoiceController(
    private val settings: Settings,
    private val onStatus: (String) -> Unit,
    private val onTranscript: (String) -> Unit,
    /** What to show on screen — this device has no TTS, so the reply is text only. */
    private val onReply: (String) -> Unit,
) {
    /** [wav] = recorded audio, or null when the command was typed as [typed]. */
    fun runTurn(wav: ByteArray?, typed: String?) {
        val dry = settings.dryRun
        val key = settings.mistralKey
        if (!dry && key.isBlank()) {
            fail("Bitte zuerst den Mistral-Key in den Einstellungen setzen.")
            return
        }
        val mistral = Mistral(key, settings.mistralBase, settings.llmBase)

        val transcript = when {
            typed != null -> typed.trim()
            dry -> DRY_TRANSCRIPT
            else -> try {
                onStatus("Transkribiere…")
                mistral.transcribe(wav!!, settings.sttModel)
            } catch (e: AuthException) {
                fail(e.message ?: "API-Key ungültig.")
                return
            } catch (e: Exception) {
                fail("Ich konnte dich nicht verstehen.")
                return
            }
        }
        onTranscript(transcript)
        if (transcript.isEmpty()) {
            fail("Ich habe nichts verstanden.")
            return
        }

        // Read-only and free, so dry mode still uses the real catalogue.
        val flows = try {
            onStatus("Lade Flows…")
            Freeps(settings.freepsUrl).listFlows()
        } catch (e: Exception) {
            fail("Der Flow-Server ist nicht erreichbar.")
            return
        }

        val pick = if (dry) dryPick(flows) else {
            onStatus("Denke nach…")
            FlowPicker.pick(mistral, transcript, flows, settings.llmModel)
        }
        if (pick.flow == null) {
            fail(pick.replyOk.ifEmpty { "Dafür kenne ich keinen Flow." })
            return
        }

        if (dry) {
            onStatus("◌ ${pick.flow} — Trockentest")
            onReply("Trockentest: ${pick.flow} würde ausgeführt.")
            return
        }

        onStatus("Führe ${pick.flow} aus…")
        val ok = runCatching { Freeps(settings.freepsUrl).runFlow(pick.flow) }
            .getOrDefault(false)
        onStatus(if (ok) "✓ ${pick.flow}" else "✗ ${pick.flow}")
        onReply(if (ok) pick.replyOk.ifEmpty { "Erledigt." }
        else pick.replyError.ifEmpty { "Der Flow konnte nicht gestartet werden." })
    }

    /** Offline pick: no LLM call, just the first flow by name so the pipeline still runs. */
    private fun dryPick(flows: Map<String, JSONObject>): FlowPicker.Pick =
        FlowPicker.Pick(flows.keys.sorted().firstOrNull(), "", "")

    private fun fail(message: String) {
        onStatus(message)
        onReply(message)
    }

    companion object {
        /** Voice input cannot be transcribed in dry mode, so it stands in for a command. */
        const val DRY_TRANSCRIPT = "Trockentest"
    }
}
