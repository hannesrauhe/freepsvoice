package eu.rauhe.freepsvoice

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * While the panel sleeps there is no polling, so an alarm checks for wake-worthy alerts
 * (sev ≤ 3). It wakes on every firing while such an alert is unresolved — silenced and
 * expired ones are already excluded by the server, so silencing stops the waking.
 */
class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            val settings = Settings(context)
            try {
                if (settings.kioskEnabled) AlertWatch.check(context, settings)
            } catch (e: Exception) {
                Log.w("AlertWatch", "check: ${e.message}")
            } finally {
                if (settings.kioskEnabled) AlertWatch.arm(context)
                pending.finish()
            }
        }.start()
    }
}

object AlertWatch {
    const val ACTION = "eu.rauhe.freepsvoice.ALERT_CHECK"
    private const val REQUEST = 2001
    private const val INTERVAL_MS = 5 * 60_000L

    fun arm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context) ?: return
        runCatching {
            am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + INTERVAL_MS,
                pi,
            )
        }.onFailure { Log.w("AlertWatch", "arm: ${it.message}") }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        pendingIntent(context, create = false)?.let { am.cancel(it) }
    }

    private fun pendingIntent(context: Context, create: Boolean = true): PendingIntent? =
        PendingIntent.getBroadcast(
            context, REQUEST,
            Intent(context, AlertReceiver::class.java).setAction(ACTION),
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
                PendingIntent.FLAG_IMMUTABLE,
        )

    /** Fetch directly, without the ViewModel: a receiver has no lifecycle. */
    fun check(context: Context, settings: Settings) {
        val waking = fetchWake(settings.freepsUrl) ?: return
        if (waking.isEmpty()) return
        Log.i("AlertWatch", "${waking.size} Meldungen (sev ≤ 3) aktiv — Bildschirm an")
        Kiosk.wakeNow(context)
    }

    /** Same endpoint as the UI, but only what can wake the panel. Null on any failure. */
    private fun fetchWake(baseUrl: String): List<Alert>? = runCatching {
        val con = URL("$baseUrl/alert/GetAlerts").openConnection() as HttpURLConnection
        con.connectTimeout = 5_000
        con.readTimeout = 5_000
        try {
            if (con.responseCode != 200) return null
            val body = con.inputStream.bufferedReader().use { it.readText() }
            val obj = org.json.JSONObject(body)
            obj.keys().asSequence().map { key ->
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
            }.filter { Alerts.wakes(it) }.toList()
        } finally {
            con.disconnect()
        }
    }.getOrNull()
}
