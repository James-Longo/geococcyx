package com.jameslongo.geococcyx

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.ViewConfigurationCompat
import androidx.wear.ambient.AmbientLifecycleObserver
import com.jameslongo.geococcyx.ui.App

class MainActivity : ComponentActivity() {
    val ambient = mutableStateOf(false)

    private val ambientObserver = AmbientLifecycleObserver(this, object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) { ambient.value = true }
        override fun onExitAmbient() { ambient.value = false }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Stay on screen (dimmed) through the run instead of dropping to the watch face.
        lifecycle.addObserver(ambientObserver)
        if (RunRepo.flow.value.status == Status.IDLE && java.io.File(filesDir, "runs/current.journal").exists()) {
            ExerciseService.send(this, ExerciseService.ACTION_RECOVER)
        }
        // Catch up on any run that hasn't uploaded yet (offline at the time, or saved before sign-in).
        SyncWorker.enqueue(this)
        setContent { App(this) }
    }

    fun missingPermissions(): Array<String> {
        val heart = if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS
        return arrayOf(
            heart, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }

    private var twistPx = 0f
    private var twistAt = 0L
    private var lastToggle = 0L

    /**
     * Pixel Watch keeps both button presses for the system, so a firm twist of the crown
     * pauses or resumes instead: [TWIST_PX] of turning within [TWIST_WINDOW_MS] of starting to turn.
     */
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val st = RunRepo.flow.value.status
        val running = st == Status.ACTIVE || st == Status.PAUSED || st == Status.AUTO_PAUSED
        if (!running || ev.action != MotionEvent.ACTION_SCROLL || !ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            return super.dispatchGenericMotionEvent(ev)
        }
        val px = -ev.getAxisValue(MotionEvent.AXIS_SCROLL) *
            ViewConfigurationCompat.getScaledVerticalScrollFactor(android.view.ViewConfiguration.get(this), this)
        val now = ev.eventTime
        // Only turning packed into TWIST_WINDOW_MS counts, so a slow roll never adds up to a pause.
        if (now - twistAt > TWIST_WINDOW_MS) { twistAt = now; twistPx = 0f }
        twistPx += kotlin.math.abs(px)
        if (twistPx >= TWIST_PX && now - lastToggle > TWIST_COOLDOWN_MS) {
            twistPx = 0f
            lastToggle = now
            ExerciseService.send(this, if (st == Status.ACTIVE) ExerciseService.ACTION_PAUSE else ExerciseService.ACTION_RESUME)
        }
        return true
    }

    /** Hardware buttons mark a lap during a run: easier than the screen when sweaty or racing. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val stem = keyCode == KeyEvent.KEYCODE_STEM_PRIMARY || keyCode == KeyEvent.KEYCODE_STEM_1 ||
            keyCode == KeyEvent.KEYCODE_STEM_2 || keyCode == KeyEvent.KEYCODE_STEM_3
        val st = RunRepo.flow.value.status
        if (stem && (st == Status.ACTIVE || st == Status.PAUSED || st == Status.AUTO_PAUSED)) {
            ExerciseService.send(this, ExerciseService.ACTION_LAP)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val TWIST_PX = 400f
        private const val TWIST_WINDOW_MS = 500L
        private const val TWIST_COOLDOWN_MS = 1500L
    }
}
