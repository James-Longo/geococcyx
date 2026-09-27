package com.jameslongo.truerun

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.LocationAvailability
import androidx.health.services.client.data.WarmUpConfig
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

enum class Status { IDLE, STARTING, ACTIVE, PAUSED, AUTO_PAUSED, ENDED }

data class RunState(
    val status: Status = Status.IDLE,
    val mode: String = "Run",
    val activeMs: Long = 0,
    val meters: Double = 0.0,
    val hr: Int? = null,
    /** Seconds per meter over the last ~15 s of active time; null when unknown. */
    val pace: Double? = null,
    val gpsReady: Boolean = false,
    val lapNo: Int = 1,
    val lapMs: Long = 0,
    val lapMeters: Double = 0.0,
    val lastLapMs: Long? = null,
    val lastLapMeters: Double? = null,
    val step: Step? = null,
    val stepNo: Int = 0,
    val stepCount: Int = 0,
    val stepMs: Long = 0,
    val stepMeters: Double = 0.0,
    /** -1 too fast, 0 on target, 1 too slow. */
    val paceFlag: Int = 0,
    val race: Race? = null,
    val savedFile: String? = null,
)

/** Shared between the service and the UI. */
object RunRepo {
    val state = MutableStateFlow(RunState())
    val flow: StateFlow<RunState> get() = state
}

class ExerciseService : LifecycleService() {
    private val client by lazy { HealthServices.getClient(this).exerciseClient }
    private lateinit var cfg: Config
    private lateinit var rec: Recorder
    private var workout: Workout? = null

    // Active-time checkpoint from Health Services: active duration as of a wall-clock instant.
    private var cpWall = 0L
    private var cpActive = 0L
    private var running = false
    private val paceWindow = ArrayDeque<Pair<Long, Double>>()
    private var lapStartWall = 0L
    private var lapStartActive = 0L
    private var lapStartMeters = 0.0
    private var stepIdx = 0
    private var stepStartActive = 0L
    private var stepStartMeters = 0.0
    private var offTargetSince = 0L
    private var lastPaceBuzz = 0L
    private var raceFinished = false

    private val bootWall get() = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    private fun activeNow() = if (running) cpActive + (System.currentTimeMillis() - cpWall) else cpActive

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_GO -> go()
            ACTION_CANCEL -> cancel()
            ACTION_PAUSE -> lifecycleScope.launch { client.pauseExerciseAsync().await() }
            ACTION_RESUME -> lifecycleScope.launch { client.resumeExerciseAsync().await() }
            ACTION_LAP -> lap("MANUAL")
            ACTION_END -> lifecycleScope.launch { client.endExerciseAsync().await() }
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (RunRepo.state.value.status in setOf(Status.STARTING, Status.ACTIVE, Status.PAUSED, Status.AUTO_PAUSED)) return
        cfg = Config.load(this)
        workout = cfg.workouts.firstOrNull { it.name == intent.getStringExtra(EXTRA_WORKOUT) }
        val race = cfg.races.firstOrNull { it.name == intent.getStringExtra(EXTRA_RACE) }
        val dir = File(filesDir, "runs").apply { mkdirs() }
        rec = Recorder(File(dir, "current.journal").apply { delete() })
        RunRepo.state.value = RunState(
            status = Status.STARTING,
            mode = workout?.name ?: race?.name ?: "Run",
            step = workout?.steps?.firstOrNull(),
            stepCount = workout?.steps?.size ?: 0,
            race = race,
        )
        ServiceCompat.startForeground(
            this, 1, notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        // Warm up GPS and heart rate first; the run starts on ACTION_GO.
        lifecycleScope.launch {
            client.setUpdateCallback(callback)
            client.prepareExerciseAsync(
                WarmUpConfig(ExerciseType.RUNNING, setOf(DataType.HEART_RATE_BPM, DataType.LOCATION))
            ).await()
        }
    }

    private fun go() {
        if (RunRepo.state.value.status != Status.STARTING) return
        lifecycleScope.launch {
            val caps = client.getCapabilitiesAsync().await().getExerciseTypeCapabilities(ExerciseType.RUNNING)
            val wanted = setOf(DataType.LOCATION, DataType.HEART_RATE_BPM, DataType.DISTANCE_TOTAL)
            client.startExerciseAsync(
                ExerciseConfig.builder(ExerciseType.RUNNING)
                    .setDataTypes(wanted intersect caps.supportedDataTypes)
                    .setIsAutoPauseAndResumeEnabled(cfg.autoPause && caps.supportsAutoPauseAndResume)
                    .setIsGpsEnabled(true)
                    .build()
            ).await()
            tick()
        }
    }

    private val callback = object : ExerciseUpdateCallback {
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {}
        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            if (availability is LocationAvailability) update {
                it.copy(gpsReady = availability == LocationAvailability.ACQUIRED_TETHERED ||
                    availability == LocationAvailability.ACQUIRED_UNTETHERED)
            }
        }

        override fun onExerciseUpdateReceived(update: ExerciseUpdate) = onUpdate(update)
    }

    private fun onUpdate(u: ExerciseUpdate) {
        val boot = Instant.ofEpochMilli(bootWall)
        val m = u.latestMetrics
        // Each sample keeps its own sensor timestamp, never the delivery time.
        m.getData(DataType.LOCATION).forEach { p ->
            val v = p.value
            rec.addLoc(Loc(p.getTimeInstant(boot).toEpochMilli(), v.latitude, v.longitude, v.altitude.takeIf { !it.isNaN() }))
        }
        m.getData(DataType.HEART_RATE_BPM).forEach { p -> rec.addHr(p.getTimeInstant(boot).toEpochMilli(), p.value.toInt()) }
        val total = m.getData(DataType.DISTANCE_TOTAL)?.total
        u.activeDurationCheckpoint?.let {
            cpWall = it.time.toEpochMilli()
            cpActive = it.activeDuration.toMillis()
        }
        val prev = RunRepo.state.value.status
        val status = when (u.exerciseStateInfo.state) {
            ExerciseState.ACTIVE -> Status.ACTIVE
            ExerciseState.USER_PAUSED -> Status.PAUSED
            ExerciseState.AUTO_PAUSED -> Status.AUTO_PAUSED
            ExerciseState.ENDED -> Status.ENDED
            else -> prev
        }
        running = status == Status.ACTIVE
        if (status != prev) onStatus(prev, status)
        update { it.copy(status = status, meters = total ?: it.meters, hr = rec.hr.lastOrNull()?.second ?: it.hr) }
    }

    private fun onStatus(prev: Status, now: Status) {
        when {
            prev == Status.STARTING && now == Status.ACTIVE -> {
                rec.addEvent("START")
                lapStartWall = System.currentTimeMillis()
                buzz(LONG)
            }
            now == Status.PAUSED -> { rec.addEvent("PAUSE"); buzz(SHORT) }
            now == Status.AUTO_PAUSED -> { rec.addEvent("AUTO_PAUSE"); buzz(SHORT) }
            prev == Status.PAUSED && now == Status.ACTIVE -> { rec.addEvent("RESUME"); buzz(SHORT) }
            prev == Status.AUTO_PAUSED && now == Status.ACTIVE -> { rec.addEvent("AUTO_RESUME"); buzz(SHORT) }
            now == Status.ENDED -> finish()
        }
    }

    /** One-second loop: timers, rolling pace, auto-lap, workout steps, race pacing. */
    private suspend fun tick() {
        while (RunRepo.state.value.status != Status.ENDED && RunRepo.state.value.status != Status.IDLE) {
            val s = RunRepo.state.value
            val act = activeNow()
            if (running) {
                paceWindow.addLast(act to s.meters)
                while (paceWindow.size > 2 && act - paceWindow.first().first > 15_000) paceWindow.removeFirst()
            }
            val (a0, m0) = paceWindow.firstOrNull() ?: (act to s.meters)
            val pace = if (s.meters - m0 > 10 && act - a0 >= 5_000) (act - a0) / 1000.0 / (s.meters - m0) else null

            val wo = workout
            if (wo != null && stepIdx < wo.steps.size) {
                val step = wo.steps[stepIdx]
                val done = (step.meters != null && s.meters - stepStartMeters >= step.meters) ||
                    (step.seconds != null && act - stepStartActive >= step.seconds * 1000)
                if (done) advanceStep("DISTANCE") else checkPace(step, pace, act)
            } else if (wo == null && s.meters - lapStartMeters >= cfg.autoLapMeters) {
                lap("DISTANCE")
            }
            s.race?.let { r -> if (!raceFinished && s.meters >= r.meters) { raceFinished = true; buzz(TRIPLE) } }

            update {
                it.copy(
                    activeMs = act, pace = pace,
                    lapNo = rec.laps.size + 1, lapMs = act - lapStartActive, lapMeters = it.meters - lapStartMeters,
                    stepMs = act - stepStartActive, stepMeters = it.meters - stepStartMeters,
                )
            }
            delay(1000)
        }
    }

    private fun checkPace(step: Step, pace: Double?, act: Long) {
        val fast = step.paceFast ?: return
        val slow = step.paceSlow ?: return
        if (pace == null || act - stepStartActive < 10_000) return update { it.copy(paceFlag = 0) }
        val flag = when { pace < fast -> -1; pace > slow -> 1; else -> 0 }
        val now = System.currentTimeMillis()
        if (flag == 0) offTargetSince = 0 else if (offTargetSince == 0L) offTargetSince = now
        // Nudge after 8 s off target, at most every 20 s.
        if (flag != 0 && now - offTargetSince > 8_000 && now - lastPaceBuzz > 20_000) {
            buzz(if (flag < 0) DOUBLE else SHORT)
            lastPaceBuzz = now
        }
        update { it.copy(paceFlag = flag) }
    }

    private fun advanceStep(trigger: String) {
        val wo = workout ?: return
        lap(trigger, fromStep = true)
        stepIdx++
        stepStartActive = activeNow()
        stepStartMeters = RunRepo.state.value.meters
        offTargetSince = 0
        buzz(if (stepIdx >= wo.steps.size) TRIPLE else LONG)
        update { it.copy(step = wo.steps.getOrNull(stepIdx), stepNo = stepIdx, paceFlag = 0) }
    }

    private fun lap(trigger: String, fromStep: Boolean = false) {
        val s = RunRepo.state.value
        if (s.status !in setOf(Status.ACTIVE, Status.PAUSED, Status.AUTO_PAUSED)) return
        // In a workout, the lap button ends a lap-terminated step.
        if (!fromStep && workout?.steps?.getOrNull(stepIdx)?.untilLap == true) return advanceStep(trigger)
        val now = System.currentTimeMillis()
        val act = activeNow()
        rec.addLap(Lap(lapStartWall, now, act - lapStartActive, s.meters - lapStartMeters, trigger, rec.avgHr(lapStartWall, now)))
        update { it.copy(lastLapMs = act - lapStartActive, lastLapMeters = s.meters - lapStartMeters) }
        lapStartWall = now; lapStartActive = act; lapStartMeters = s.meters
        if (!fromStep) buzz(SHORT)
    }

    private fun finish() {
        val now = System.currentTimeMillis()
        rec.addEvent("STOP", now)
        val s = RunRepo.state.value
        if (now - lapStartWall > 2000 || rec.laps.isEmpty()) {
            rec.addLap(Lap(lapStartWall, now, cpActive - lapStartActive, s.meters - lapStartMeters, "MANUAL", rec.avgHr(lapStartWall, now)))
        }
        val base = File(File(filesDir, "runs"), Recorder.iso(rec.start).replace(":", ""))
        File("$base.tcx").writeText(rec.toTcx())
        File("$base.health.json").writeText(rec.toGoogleHealth(s.mode, cpActive, s.meters).toString())
        File("$base.meta.json").writeText(org.json.JSONObject().put("name", s.mode).put("activeMs", cpActive).put("meters", s.meters).toString())
        File(File(filesDir, "runs"), "current.journal").delete()
        SyncWorker.enqueue(this)
        buzz(LONG)
        update { it.copy(status = Status.ENDED, activeMs = cpActive, savedFile = base.name) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Back out of the warm-up without recording anything. */
    private fun cancel() {
        lifecycleScope.launch {
            // Detach first so the ENDED update doesn't save an empty run.
            runCatching { client.clearUpdateCallbackAsync(callback).await() }
            runCatching { client.endExerciseAsync().await() }
            RunRepo.state.value = RunState()
            ServiceCompat.stopForeground(this@ExerciseService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private inline fun update(f: (RunState) -> RunState) { RunRepo.state.value = f(RunRepo.state.value) }

    private fun buzz(pattern: LongArray) {
        val vib = (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("run", "Run in progress", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, "run")
            .setSmallIcon(R.drawable.ic_run).setContentTitle("TrueRun").setContentText("Recording")
            .setOngoing(true).setContentIntent(open).setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build()
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_GO = "go"
        const val ACTION_CANCEL = "cancel"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_LAP = "lap"
        const val ACTION_END = "end"
        const val EXTRA_WORKOUT = "workout"
        const val EXTRA_RACE = "race"
        private val SHORT = longArrayOf(0, 150)
        private val DOUBLE = longArrayOf(0, 120, 120, 120)
        private val TRIPLE = longArrayOf(0, 200, 150, 200, 150, 200)
        private val LONG = longArrayOf(0, 500)

        fun send(ctx: Context, action: String, extras: Intent.() -> Unit = {}) {
            val i = Intent(ctx, ExerciseService::class.java).setAction(action).apply(extras)
            if (action == ACTION_START) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
