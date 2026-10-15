package eu.rauhe.freepsvoice

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Wall flavor: periodic tick while the UI is closed. Never wakes the screen. */
class MetricsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                Metrics.tick(context, Settings(context))
            } catch (e: Exception) {
                Log.w("Metrics", "tick: ${e.message}")
            } finally {
                Metrics.arm(context)
                pending.finish()
            }
        }.start()
    }
}

/** Device telemetry pushed to freeps as sensor properties, one POST per cycle. */
object Metrics {
    const val ACTION = "eu.rauhe.freepsvoice.METRICS"
    const val CATEGORY = "freepsvoice"
    private const val TAG = "Metrics"
    private const val REQUEST = 2002
    private const val INTERVAL_MS = 5 * 60_000L

    /** Readable label; written as the freeps alias property "name". */
    fun displayName(settings: Settings) = settings.deviceName.ifBlank { defaultName() }

    fun defaultName() = Build.MODEL + if (wallMode) " Wall" else ""

    /**
     * Sensor key in freeps: stable, independent of the display name. ANDROID_ID keeps two
     * identical models apart; it is per signing key, so debug and release builds differ.
     */
    fun deviceId(context: Context): String {
        val id = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID,
        ).lowercase().take(6)
        return slug(Build.MODEL) + (if (wallMode) "_wall_" else "_phone_") + id
    }

    fun slug(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    fun tick(context: Context, settings: Settings) {
        runCatching {
            Freeps(settings.freepsUrl)
                .sendDeviceMetrics(CATEGORY, deviceId(context), collect(context, settings))
        }.onFailure { Log.w(TAG, "tick: ${it.message}") }
    }

    /** uptime is monotonic: it doubles as the heartbeat and resets on reboot. */
    fun collect(context: Context, settings: Settings): Map<String, Any> {
        val p = mutableMapOf<String, Any>(
            "name" to displayName(settings),
            "model" to Build.MODEL,
            "version" to BuildConfig.VERSION_NAME,
            "uptime" to SystemClock.elapsedRealtime() / 1000,
        )
        localIp()?.let { p["ip"] = it }
        battery(context)?.let { (level, charging) ->
            p["battery"] = level
            p["charging"] = charging
        }
        if (!wallMode) return p
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        p["screenOn"] = pm.isInteractive
        p["kiosk"] = settings.kioskEnabled
        p["deviceOwner"] = Kiosk.isOwner(context)
        if (settings.lastTouchMs > 0) p["lastTouch"] = settings.lastTouchMs / 1000
        light(context)?.let { p["light"] = it }
        thermal()?.let { (batt, max) ->
            batt?.let { p["tempC"] = it }
            max?.let { p["tempMaxC"] = it }
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.let {
            p["memAvailMB"] = it.availMem / 1048576
        }
        readLong("/sys/class/power_supply/battery/current_now")?.let {
            p["batteryMa"] = it / 1000.0
        }
        return p
    }

    fun arm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context) ?: return
        runCatching {
            am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + INTERVAL_MS,
                pi,
            )
        }.onFailure { Log.w(TAG, "arm: ${it.message}") }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        pendingIntent(context, create = false)?.let { am.cancel(it) }
    }

    private fun pendingIntent(context: Context, create: Boolean = true): PendingIntent? =
        PendingIntent.getBroadcast(
            context, REQUEST,
            Intent(context, MetricsReceiver::class.java).setAction(ACTION),
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
                PendingIntent.FLAG_IMMUTABLE,
        )

    /** Sticky broadcast: the current battery state, no permission needed. */
    private fun battery(context: Context): Pair<Int, Boolean>? {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0) return null
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return level * 100 / scale to charging
    }

    /** On-change sensor: registering delivers the current value right away. */
    private fun light(context: Context): Double? {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return null
        val latch = CountDownLatch(1)
        var lux = -1f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                lux = e.values[0]
                latch.countDown()
            }

            override fun onAccuracyChanged(s: Sensor, a: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        runCatching { latch.await(3, TimeUnit.SECONDS) }
        sm.unregisterListener(listener)
        return if (lux >= 0) (lux * 10).roundToInt() / 10.0 else null
    }

    /** Battery zone + hottest CPU zone; some zones report millidegrees. */
    private fun thermal(): Pair<Double?, Double?>? {
        val zones = File("/sys/class/thermal").listFiles() ?: return null
        var batt: Double? = null
        var max: Float? = null
        for (z in zones) {
            val type = runCatching { File(z, "type").readText().trim() }.getOrNull() ?: continue
            val raw = readLong(File(z, "temp").path)?.toFloat() ?: continue
            val c = if (raw > 1000) raw / 1000 else raw
            if (type == "battery") batt = (c * 10).roundToInt() / 10.0
            if (type.startsWith("tsens_tz_sensor")) max = maxOf(max ?: c, c)
        }
        return batt to max?.let { (it * 10).roundToInt() / 10.0 }
    }

    private fun readLong(path: String): Long? =
        runCatching { File(path).readText().trim().toLongOrNull() }.getOrNull()

    /** Site-local IPv4, wlan0 first. */
    private fun localIp(): String? = NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .sortedBy { it.name != "wlan0" }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }?.hostAddress
}
