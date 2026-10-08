package app.parkedvideo.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.CarInfo
import androidx.car.app.hardware.info.Speed
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlin.math.abs

/**
 * Decides whether the car is parked.
 *
 * The car's own speed is used when Android Auto provides it; otherwise the
 * phone's GPS speed. With neither, the state is UNKNOWN and video stays
 * blocked: this fails closed rather than risk playing video while driving.
 */
class ParkingGuard(
    private val carContext: CarContext,
    lifecycle: Lifecycle,
) : DefaultLifecycleObserver {

    enum class State { PARKED, MOVING, UNKNOWN }

    var state = State.UNKNOWN
        private set

    /** Called with the current state on assignment, then on every change. */
    var onStateChanged: ((State) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(state)
        }

    private val handler = Handler(Looper.getMainLooper())
    private var carInfo: CarInfo? = null
    private var locationManager: LocationManager? = null

    // The car only reports speed when it changes, so its last value never goes stale.
    private var carSpeed: Float? = null
    private var gpsSpeed: Float? = null
    private var gpsSpeedAt = 0L
    private var parkedSince = 0L

    private val speedListener = OnCarDataAvailableListener<Speed> { speed ->
        val value = speed.rawSpeedMetersPerSecond
        if (value.status == CarValue.STATUS_SUCCESS) {
            value.value?.let { carSpeed = abs(it) }
        }
    }

    // All four methods are overridden because they only gained default
    // implementations in newer Android versions.
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.hasSpeed()) {
                gpsSpeed = location.speed
                gpsSpeedAt = SystemClock.elapsedRealtime()
            }
        }

        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        startCarSpeed()
        startGps()
        handler.post(tick)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        handler.removeCallbacks(tick)
        carInfo?.removeSpeedListener(speedListener)
        locationManager?.removeUpdates(locationListener)
    }

    private fun startCarSpeed() {
        try {
            val info = carContext.getCarService(CarHardwareManager::class.java).carInfo
            info.addSpeedListener(ContextCompat.getMainExecutor(carContext), speedListener)
            carInfo = info
        } catch (e: Exception) {
            Log.w(TAG, "Car speed unavailable; falling back to phone GPS", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        val permission = ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION)
        if (permission != PackageManager.PERMISSION_GRANTED) return
        val manager = carContext.getSystemService(LocationManager::class.java) ?: return
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0f, locationListener, Looper.getMainLooper())
            locationManager = manager
        } catch (e: Exception) {
            Log.w(TAG, "GPS unavailable", e)
        }
    }

    private fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        val car = carSpeed
        val gps = gpsSpeed.takeIf { now - gpsSpeedAt < GPS_STALE_MS }
        val raw = when {
            car != null -> if (car > CAR_MOVING_MPS) State.MOVING else State.PARKED
            gps != null -> if (gps > GPS_MOVING_MPS) State.MOVING else State.PARKED
            else -> State.UNKNOWN
        }

        // Only unblock after the car has been stopped for a few seconds, so
        // stop-and-go traffic doesn't flash video on and off.
        val next = if (raw == State.PARKED) {
            if (parkedSince == 0L) parkedSince = now
            if (now - parkedSince >= PARK_CONFIRM_MS) State.PARKED else state
        } else {
            parkedSince = 0L
            raw
        }

        if (next != state) {
            state = next
            onStateChanged?.invoke(next)
        }
    }

    private companion object {
        const val TAG = "ParkingGuard"
        const val TICK_MS = 1_000L
        const val GPS_INTERVAL_MS = 1_000L
        const val GPS_STALE_MS = 5_000L
        const val PARK_CONFIRM_MS = 3_000L
        // The car reports an exact 0 when stopped; GPS jitters, so it gets more slack (~7 km/h).
        const val CAR_MOVING_MPS = 0.5f
        const val GPS_MOVING_MPS = 2.0f
    }
}
