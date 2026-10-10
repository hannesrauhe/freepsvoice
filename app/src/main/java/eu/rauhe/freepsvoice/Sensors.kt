package eu.rauhe.freepsvoice

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SensorsClient(private val baseUrl: String) {
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    /** All sensors having any of [props], keyed by alias. One request for the whole screen. */
    fun values(props: List<String>): Map<String, Map<String, Any?>> {
        if (props.isEmpty()) return emptyMap()
        val q = props.joinToString("&") { "SensorPropertyName=${enc(it)}" }
        val req = Request.Builder().url("$baseUrl/sensor/GetSensorPropertiesByAlias?$q").get().build()
        http.newCall(req).execute().use { res ->
            val body = res.body?.string() ?: ""
            if (!res.isSuccessful) error("GetSensorPropertiesByAlias HTTP ${res.code}")
            val obj = JSONObject(body)
            return obj.keys().asSequence().associateWith { alias ->
                val o = obj.getJSONObject(alias)
                o.keys().asSequence().associateWith { o.opt(it) as Any? }
            }.toMap()
        }
    }

    /** Every property known to the server, with the number of sensors that have it. */
    fun universe(): Map<String, Int> {
        val req = Request.Builder().url("$baseUrl/sensor/GetSensorsPerProperty").get().build()
        http.newCall(req).execute().use { res ->
            val body = res.body?.string() ?: ""
            if (!res.isSuccessful) error("GetSensorsPerProperty HTTP ${res.code}")
            val obj = JSONObject(body)
            return obj.keys().asSequence().associateWith { obj.getJSONArray(it).length() }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/** Pure display logic for the sensors screen, kept Android-free so it can be tested. */
object Sensors {
    /** Sensors reporting the same properties — the same kind of device. */
    data class Group(val props: List<String>, val rows: List<Pair<String, List<String>>>)

    /**
     * Group by the properties a sensor actually reports, so thermostats and plugs each get one
     * card with their values side by side instead of a card per property.
     */
    fun groups(props: List<String>, data: Map<String, Map<String, Any?>>): List<Group> =
        data.entries
            .map { (alias, values) ->
                val held = props.filter { values.containsKey(it) }
                held to (alias to held.map { render(values[it]) })
            }
            .filter { it.first.isNotEmpty() }
            .groupBy({ it.first }, { it.second })
            .map { (held, rows) -> Group(held, rows.sortedBy { it.first.lowercase() }) }
            .sortedWith(compareByDescending<Group> { it.rows.size }
                .thenBy { it.props.joinToString(" ").lowercase() })

    /** Booleans are switches here, and whole numbers should not read as floats. */
    fun render(v: Any?): String = when (v) {
        null -> "–"
        JSONObject.NULL -> "–"
        is Boolean -> if (v) "An" else "Aus"
        is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        is JSONObject -> v.keys().asSequence().joinToString(" ") { "${it}: ${render(v.opt(it))}" }
        else -> v.toString()
    }

    /** Editor order: most widely used first, so the noise identifies itself. */
    fun editorOrder(universe: Map<String, Int>): List<String> =
        universe.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
            .thenBy { it.key.lowercase() }).map { it.key }

    /** Search over property names, case-insensitive. */
    fun filter(names: List<String>, query: String): List<String> =
        if (query.isBlank()) names
        else names.filter { it.contains(query.trim(), ignoreCase = true) }
}
