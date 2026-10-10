package eu.rauhe.freepsvoice

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** A freeps alert as the alert connector returns it. Durations arrive as nanoseconds. */
data class Alert(
    val name: String,
    val category: String,
    val desc: String,
    val severity: Int,
    val counter: Int,
    val sinceLastMs: Long,
    val silenceMs: Long,
) {
    val fullName: String get() = if (category.isEmpty()) name else "$category.$name"
    val text: String get() = desc.ifBlank { fullName }
}

class AlertsClient(private val baseUrl: String) {
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    /** Active alerts; silenced ones only when asked for. Expired are never requested. */
    fun get(includeSilenced: Boolean): List<Alert> {
        val url = "$baseUrl/alert/GetAlerts" +
            if (includeSilenced) "?includeSilenced=true" else ""
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { res ->
            val body = res.body?.string() ?: ""
            if (!res.isSuccessful) error("GetAlerts HTTP ${res.code}")
            val obj = JSONObject(body)
            return obj.keys().asSequence().map { key ->
                val o = obj.getJSONObject(key)
                Alert(
                    name = o.optString("Name"),
                    category = o.optString("Category"),
                    desc = o.optString("Desc"),
                    severity = o.optInt("Severity", 5),
                    counter = o.optInt("Counter"),
                    sinceLastMs = o.optLong("DurationSinceLast") / 1_000_000,
                    silenceMs = o.optLong("SilenceDuration") / 1_000_000,
                )
            }.toList()
        }
    }

    fun silence(a: Alert, duration: String) = post("SilenceAlert", a, "SilenceDuration=$duration")
    fun reset(a: Alert) = post("ResetAlert", a)
    fun resetSilence(a: Alert) = post("ResetSilence", a)

    private fun post(op: String, a: Alert, extra: String = ""): Boolean {
        val q = "Name=${enc(a.name)}&Category=${enc(a.category)}" + if (extra.isEmpty()) "" else "&$extra"
        val req = Request.Builder().url("$baseUrl/Alert/$op?$q")
            .post(ByteArray(0).toRequestBody(null)).build()
        http.newCall(req).execute().use { return it.isSuccessful }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/** Severity tiers and the pure display logic, kept free of Android so it can be tested. */
object Alerts {
    /** Needs action now: fix it or silence it. */
    const val URGENT_MAX = 2
    /** Worth a glance, no immediate action. */
    const val NOTICE_MAX = 3

    const val SILENCE_DEFAULT = "15m"
    val SILENCE_CHOICES = listOf("5m", "15m", "1h", "12h", "24h")

    fun isUrgent(a: Alert) = a.severity <= URGENT_MAX
    fun isNotice(a: Alert) = a.severity > URGENT_MAX && a.severity <= NOTICE_MAX
    fun isQuiet(a: Alert) = a.severity > NOTICE_MAX

    /** Sev 1-3 wake the sleeping panel until fixed or silenced; 4-5 are noise. */
    fun wakes(a: Alert) = a.severity <= NOTICE_MAX

    data class Groups(
        val urgent: List<Alert>,
        val notice: List<Alert>,
        val quiet: List<Alert>,
    ) {
        val total get() = urgent.size + notice.size + quiet.size
    }

    fun group(alerts: List<Alert>): Groups {
        fun sorted(list: List<Alert>) = list.sortedWith(compareBy({ it.severity }, { it.fullName }))
        return Groups(
            urgent = sorted(alerts.filter { isUrgent(it) }),
            notice = sorted(alerts.filter { isNotice(it) }),
            quiet = sorted(alerts.filter { isQuiet(it) }),
        )
    }

    /** Device ids carry numbers ("BatteryLow09295 0994096"); dropping them clusters the noise. */
    fun label(a: Alert): String =
        a.name.replace(Regex("[0-9]+"), "").trim().ifBlank { a.name }

    /** "Akku niedrig x2" style summary for the collapsed low-severity group. */
    fun quietSummary(quiet: List<Alert>): String =
        quiet.groupBy { label(it) }
            .entries.sortedByDescending { it.value.size }
            .joinToString(" · ") { (lab, list) ->
                if (list.size > 1) "$lab x${list.size}" else lab
            }

    fun age(ms: Long): String = when {
        ms < 60_000 -> "gerade eben"
        ms < 3_600_000 -> "${ms / 60_000} Min"
        ms < 86_400_000 -> "${ms / 3_600_000} Std"
        else -> "${ms / 86_400_000} Tage"
    }
}
