package eu.rauhe.freepsvoice

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** freeps flow-builder client: catalogue + fire a flow. Plain HTTP on the LAN. */
class Freeps(private val baseUrl: String) {
    private val http = OkHttpClient.Builder()
        // Slow flows (e.g. ones driving hardware) must be able to finish.
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * User-invocable flows only: the server filters by kind, so helper and deactivated
     * never reach us. A flow without a kind counts as manual there, and is included.
     */
    fun listFlows(): Map<String, JSONObject> {
        val url = "$baseUrl/flowbuilder/ListFlows?Kind=manual&Kind=event"
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string() ?: ""
            if (!res.isSuccessful) error("ListFlows HTTP ${res.code}")
            val obj = JSONObject(text)
            return obj.keys().asSequence().associateWith { obj.getJSONObject(it) }
        }
    }

    fun runFlow(name: String): Boolean {
        // URLEncoder is form encoding: '+' must become %20 to survive as a path segment.
        val encoded = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val req = Request.Builder().url("$baseUrl/flow/$encoded")
            .post(ByteArray(0).toRequestBody(null)).build()
        http.newCall(req).execute().use { return it.isSuccessful }
    }

    /** The Mistral key kept in the freeps config ([mistral] section). */
    fun getMistralKey(): String {
        val url = "$baseUrl/config/GetKey?SectionName=mistral&Key=APIKey"
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string() ?: ""
            if (!res.isSuccessful) error("GetKey HTTP ${res.code}")
            return text.trim()
        }
    }
}
