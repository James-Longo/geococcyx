package com.jameslongo.truerun

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.wear.ambient.AmbientLifecycleObserver
import com.jameslongo.truerun.ui.App

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
        setContent { App(this) }
    }

    fun missingPermissions(): Array<String> {
        val heart = if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS
        return arrayOf(
            heart, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
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
}
