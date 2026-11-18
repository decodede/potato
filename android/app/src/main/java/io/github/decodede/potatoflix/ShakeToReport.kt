package io.github.decodede.potatoflix

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Phones: a firm shake (three quick jolts) opens "Report a glitch" for the screen showing. Off on TVs, and with
 * Settings → About → Shake to report a glitch.
 */
class ShakeToReport(private val activity: Activity) : SensorEventListener {
    private val sensors = activity.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val jolts = ArrayDeque<Long>()
    private var lastReport = 0L

    fun start() {
        if (App.isTv || !Settings.shakeToReport) return
        val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() { sensors?.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        val now = android.os.SystemClock.elapsedRealtime()
        if (shake(g, now)) {
            lastReport = now
            if (GlitchReport.draft == null) GlitchReport.start(activity)
        }
    }

    /** True on the third jolt over 2.7 g within 1.2 s (at most once every 5 s). */
    internal fun shake(g: Float, now: Long): Boolean {
        if (g < 2.7f || now - lastReport < 5000) return false
        if (jolts.lastOrNull()?.let { now - it < 150 } == true) return false // the same jolt, still going
        jolts.addLast(now)
        while (jolts.isNotEmpty() && now - jolts.first() > 1200) jolts.removeFirst()
        if (jolts.size >= 3) { jolts.clear(); return true }
        return false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
