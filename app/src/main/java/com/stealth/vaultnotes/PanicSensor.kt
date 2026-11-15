package com.stealth.vaultnotes

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

class PanicSensor(
    context: Context,
    private val onPanicTriggered: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastTriggerElapsed = 0L
    private var wasFaceUp = false
    private var faceDownReadings = 0
    private var firstShakeElapsed = 0L
    private var shakeCount = 0
    private var isShakePulseActive = false

    fun start() {
        wasFaceUp = false
        faceDownReadings = 0
        shakeCount = 0
        firstShakeElapsed = 0L
        isShakePulseActive = false
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        wasFaceUp = false
        faceDownReadings = 0
        shakeCount = 0
        firstShakeElapsed = 0L
        isShakePulseActive = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerElapsed < 1200L) return

        val gX = x / SensorManager.GRAVITY_EARTH
        val gY = y / SensorManager.GRAVITY_EARTH
        val gZ = z / SensorManager.GRAVITY_EARTH
        val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)

        // رصد أن الهاتف كان في وضع معتدل أولاً (حتى لا يغلق أثناء الاستلقاء على السرير)
        if (z > 2.5f) {
            wasFaceUp = true
            faceDownReadings = 0
        }

        // 1. Flip Face Down: يتطلب انتقالاً فعلياً من وضع معتدل إلى مقلوب بالكامل لـ 3 قراءات متتالية
        if (wasFaceUp && z < -8.8f && abs(x) < 3.5f && abs(y) < 3.5f && gForce in 0.8f..1.25f) {
            faceDownReadings++
            if (faceDownReadings >= 3) {
                lastTriggerElapsed = now
                wasFaceUp = false
                faceDownReadings = 0
                onPanicTriggered()
                return
            }
        } else if (z > -7.0f) {
            faceDownReadings = 0
        }

        // 2. Hard Shake: count distinct high-acceleration pulses, not repeated sensor samples
        // from one prolonged movement. Re-arm only after acceleration settles.
        if (gForce < 1.8f) {
            isShakePulseActive = false
        } else if (gForce > 2.65f && !isShakePulseActive) {
            isShakePulseActive = true
            if (firstShakeElapsed == 0L || now - firstShakeElapsed > 550L) {
                firstShakeElapsed = now
                shakeCount = 1
            } else if (now - firstShakeElapsed >= 90L) {
                shakeCount++
                if (shakeCount >= 2) {
                    lastTriggerElapsed = now
                    firstShakeElapsed = 0L
                    shakeCount = 0
                    isShakePulseActive = false
                    onPanicTriggered()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}