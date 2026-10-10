package eu.rauhe.freepsvoice

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.util.Log
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Calendar

/** Device-admin receiver; only ever active as the (adb-provisioned) device owner. */
class KioskAdmin : DeviceAdminReceiver()

/** Fires the scheduled alarms and the boot wake. */
class KioskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Kiosk.WAKE_ACTION -> Kiosk.wakeNow(context)
            Kiosk.SLEEP_ACTION -> Kiosk.dpm(context)?.lockNow()
            Intent.ACTION_BOOT_COMPLETED -> if (Settings(context).kioskEnabled) {
                Kiosk.wakeNow(context)
            }
        }
        // One-shot alarms: re-arm both for the next day after every firing.
        if (Settings(context).kioskEnabled) Kiosk.applySchedule(context)
    }
}

/**
 * Wall-panel mode. Needs device owner, which cannot be requested at runtime:
 *   adb shell dpm set-device-owner eu.rauhe.freepsvoice.wall/eu.rauhe.freepsvoice.KioskAdmin
 * (works only while the device has no accounts). That enables lock task;
 * the BOOT_COMPLETED receiver brings the app up again after a boot.
 */
object Kiosk {
    const val WAKE_ACTION = "eu.rauhe.freepsvoice.WAKE"
    const val SLEEP_ACTION = "eu.rauhe.freepsvoice.SLEEP"
    private const val TAG = "Kiosk"

    /** The DevicePolicyManager, but only while we are device owner. */
    fun dpm(context: Context): DevicePolicyManager? {
        val d = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return if (d.isDeviceOwnerApp(context.packageName)) d else null
    }

    fun isOwner(context: Context) = dpm(context) != null

    /** Called from MainActivity: trap the app on screen (device owner only). */
    fun enable(activity: Activity) {
        immersive(activity, true)
        val d = dpm(activity) ?: return
        val admin = ComponentName(activity, KioskAdmin::class.java)
        runCatching { d.setLockTaskPackages(admin, arrayOf(activity.packageName)) }
            .onFailure { Log.w(TAG, "setLockTaskPackages: ${it.message}") }
        runCatching { activity.startLockTask() }
            .onFailure { Log.w(TAG, "startLockTask: ${it.message}") }
        // No PIN on a wall panel: sleep = screen off, wake = straight back into the app.
        // Returns false (no exception) if the device has a screen lock — then the
        // swipe screen stays on wake; say so loudly.
        val keyguardOff = runCatching { d.setKeyguardDisabled(admin, true) }.getOrDefault(false)
        if (!keyguardOff) Log.w(TAG, "keyguard bleibt aktiv: Bildschirmsperre auf 'Ohne' setzen")
        // Lock-task hides the status-bar icons but keeps its space: an empty black strip.
        runCatching { d.setStatusBarDisabled(admin, true) }
            .onFailure { Log.w(TAG, "setStatusBarDisabled: ${it.message}") }
    }

    /**
     * setStatusBarDisabled only switches the bar off, it does not give its height back.
     * Hiding the insets is what makes the app really fullscreen; a swipe still reveals
     * the bars briefly, which is also how the settings stay reachable.
     */
    private fun immersive(activity: Activity, on: Boolean) {
        val c = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (on) c.hide(WindowInsetsCompat.Type.systemBars())
        else c.show(WindowInsetsCompat.Type.systemBars())
    }

    /** Kiosk off: leave lock task, restore keyguard, status bar and system bars. */
    fun disable(activity: Activity) {
        immersive(activity, false)
        runCatching { activity.stopLockTask() }
        dpm(activity)?.let { d ->
            val admin = ComponentName(activity, KioskAdmin::class.java)
            runCatching { d.setStatusBarDisabled(admin, false) }
            runCatching { d.setKeyguardDisabled(admin, false) }
        }
        cancelSchedule(activity)
    }

    const val OFF = "aus"

    private fun parseHm(hhmm: String): Int? = hhmm.split(":").let {
        val h = it.getOrNull(0)?.toIntOrNull() ?: return null
        val m = it.getOrNull(1)?.toIntOrNull() ?: return null
        h * 60 + m
    }

    /** True while inside the on/off window (may wrap past midnight). */
    fun inWindow(context: Context): Boolean {
        val s = Settings(context)
        if (!s.kioskEnabled) return true
        val now = Calendar.getInstance().let {
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        }
        val on = parseHm(s.kioskOnTime) ?: 0
        val off = parseHm(s.kioskOffTime) ?: 24 * 60
        return if (on <= off) now in on until off else now >= on || now < off
    }

    /** (Re)arm the daily on/off alarms from the saved times; OFF disables that end. */
    fun applySchedule(context: Context) {
        cancelSchedule(context)
        val s = Settings(context)
        if (!s.kioskEnabled) return
        if (s.kioskOnTime != OFF) schedule(context, s.kioskOnTime, WAKE_ACTION, 1001)
        if (s.kioskOffTime != OFF) schedule(context, s.kioskOffTime, SLEEP_ACTION, 1002)
    }

    private fun schedule(context: Context, hhmm: String, action: String, code: Int) {
        val parts = hhmm.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h.coerceIn(0, 23))
            set(Calendar.MINUTE, m.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val pi = PendingIntent.getBroadcast(
            context, code, Intent(context, KioskReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi) }
            .onFailure { Log.w(TAG, "alarm: ${it.message}") }
    }

    private fun cancelSchedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for ((action, code) in listOf(WAKE_ACTION to 1001, SLEEP_ACTION to 1002)) {
            PendingIntent.getBroadcast(
                context, code, Intent(context, KioskReceiver::class.java).setAction(action),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let { am.cancel(it) }
        }
    }

    /** Alarm fired: screen on, app in front. The persistent app is always running anyway. */
    fun wakeNow(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "freepsvoice:wake",
        ).acquire(15_000L)
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }
}

/**
 * Screen-off when idle, wake on a hand at the sensor. The idle countdown is driven
 * by SCREEN_ON (not the sensor): the proximity sensor is on-change and stays silent
 * on a static mount, so a "far" event never arrives to start the timer. A held
 * proximity wake lock is NOT usable here: while held, the power button can't wake.
 */
class ProximityWake(private val context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sleep = Runnable {
        Log.d("Kiosk", "idle timeout -> screen off")
        Kiosk.dpm(context)?.lockNow()
    }
    private val idleSleepMs = 5 * 60 * 1000L
    private var registered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> userActive()
                Intent.ACTION_SCREEN_OFF -> handler.removeCallbacks(sleep)
            }
        }
    }

    val available get() = sensor != null

    fun register() {
        registered = true
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        context.registerReceiver(screenReceiver, filter)
        userActive()
    }

    fun unregister() {
        registered = false
        sm.unregisterListener(this)
        runCatching { context.unregisterReceiver(screenReceiver) }
        handler.removeCallbacks(sleep)
    }

    /** Restart the idle countdown; called on screen-on and on any user interaction. */
    fun userActive() {
        if (!registered) return
        handler.removeCallbacks(sleep)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive && Kiosk.inWindow(context)) {
            Log.d("Kiosk", "idle sleep in ${idleSleepMs / 1000}s")
            handler.postDelayed(sleep, idleSleepMs)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val near = event.values[0] < (sensor?.maximumRange ?: 5f)
        if (near) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            // Night blackout wins: no approach-wake outside the schedule window.
            if (!pm.isInteractive && Kiosk.inWindow(context)) Kiosk.wakeNow(context)
            userActive()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
