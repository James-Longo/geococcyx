package com.jameslongo.geococcyx

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
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.LocationAvailability
import androidx.health.services.client.data.WarmUpConfig
import androidx.lifecycle.LifecycleService
import androidx.wear.ongoing.OngoingActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

enum class Status { IDLE, STARTING, ACTIVE, PAUSED, ENDED }

data class RunState(
    val status: Status = Status.IDLE,
    val mode: String = "Run",
    val activeMs: Long = 0,
    val meters: Double = 0.0,
    val hr: Int? = null,
    /** Seconds per meter over the last ~15 s of active time; null when unknown. */
    val pace: Double? = null,
    /** Percent slope over the last ~100 m from the barometer; null without elevation. */
    val grade: Double? = null,
    /** [pace] as its flat-ground equivalent on [grade]; equal to [pace] when the slope is unknown. */
    val gap: Double? = null,
    val gpsReady: Boolean = false,
    val lapNo: Int = 1,
    val lapMs: Long = 0,
    val lapMeters: Double = 0.0,
    val lastLapMs: Long? = null,
    val lastLapMeters: Double? = null,
    val lastLapNo: Int = 0,
    /** Wall-clock time the last lap closed; the UI shows its pace full screen for a few seconds. */
    val lastLapAt: Long? = null,
    /** Virtual pacer range, seconds per meter: fastest and slowest allowed. */
    val pacerFast: Double? = null,
    val pacerSlow: Double? = null,
    /** The pacer's last finished quarter split; null until the first one. */
    val pacerSplit: SplitPacer.Split? = null,
    /** Wall-clock time of the last out-of-range split; the UI shows it full screen for a few seconds. */
    val pacerAlertAt: Long? = null,
    val step: Step? = null,
    val stepNo: Int = 0,
    val stepCount: Int = 0,
    val stepMs: Long = 0,
    val stepMeters: Double = 0.0,
    /** -1 too fast, 0 on target, 1 too slow. */
    val paceFlag: Int = 0,
    val race: Race? = null,
    /** Set for a track-intervals session; [step] / [stepNo] say which interval it's in. */
    val intervals: Intervals? = null,
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
    private var grade = Grade()
    private var lapStartWall = 0L
    private var lapStartActive = 0L
    private var lapStartMeters = 0.0
    private var stepIdx = 0
    private var stepStartActive = 0L
    private var stepStartMeters = 0.0
    private var offTargetSince = 0L
    private var lastPaceBuzz = 0L
    private var raceFinished = false
    private var pacer: SplitPacer? = null
    private var heldSplit: SplitPacer.Split? = null
    private var recovering = false
    /** Set once [finish] starts writing the run, so nothing more is added to [rec] while it's read. */
    private var saving = false

    private val bootWall get() = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    private fun activeNow() = if (running) cpActive + (System.currentTimeMillis() - cpWall) else cpActive

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_RECOVER -> recover()
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
        if (RunRepo.state.value.status in setOf(Status.STARTING, Status.ACTIVE, Status.PAUSED)) return
        cfg = Config.load(this)
        val intervals = if (intent.getBooleanExtra(EXTRA_INTERVALS, false)) Intervals.load(this) else null
        workout = intervals?.workout() ?: cfg.workouts.firstOrNull { it.name == intent.getStringExtra(EXTRA_WORKOUT) }
        val race = cfg.races.firstOrNull { it.name == intent.getStringExtra(EXTRA_RACE) }
        val pacerFast = intent.getDoubleExtra(EXTRA_PACER_FAST, 0.0).takeIf { it > 0 }
        val pacerSlow = intent.getDoubleExtra(EXTRA_PACER_SLOW, 0.0).takeIf { it > 0 }
        // No workout means auto-laps, so the quarters can end with them.
        pacer = if (pacerFast != null && pacerSlow != null) SplitPacer(pacerFast, pacerSlow, cfg.metersPerUnit / 4, cfg.autoLapMeters) else null
        heldSplit = null
        grade = Grade()
        val dir = File(filesDir, "runs").apply { mkdirs() }
        saving = false
        rec = Recorder(File(dir, "current.journal").apply { delete() })
        rec.logMeta(workout?.name ?: race?.name ?: "Run", workout?.name, race?.name)
        RunRepo.state.value = RunState(
            status = Status.STARTING,
            pacerFast = pacerFast, pacerSlow = pacerSlow,
            mode = workout?.name ?: race?.name ?: "Run",
            step = workout?.steps?.firstOrNull(),
            stepCount = workout?.steps?.size ?: 0,
            race = race,
            intervals = intervals,
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
            val wanted = setOf(
                DataType.LOCATION, DataType.HEART_RATE_BPM, DataType.DISTANCE_TOTAL,
                DataType.STEPS_PER_MINUTE, DataType.STRIDE_LENGTH, DataType.ABSOLUTE_ELEVATION,
            )
            client.startExerciseAsync(
                ExerciseConfig.builder(ExerciseType.RUNNING)
                    .setDataTypes(wanted intersect caps.supportedDataTypes)
                    // Pausing is always by hand (the crown); never automatic.
                    .setIsAutoPauseAndResumeEnabled(false)
                    .setIsGpsEnabled(true)
                    .build()
            ).await()
            tick()
        }
    }

    /**
     * The app was killed mid-run. If Health Services still has our exercise going, pick it
     * back up where the journal left off; otherwise save what the journal holds.
     */
    // Lint flags these documented ExerciseTrackedStatus constants as library-internal in 1.1.0.
    @android.annotation.SuppressLint("RestrictedApi", "WrongConstant")
    private fun recover() {
        val journal = File(File(filesDir, "runs"), "current.journal")
        if (!journal.exists() || RunRepo.state.value.status != Status.IDLE) return
        cfg = Config.load(this)
        val (r, info) = Recorder.replay(journal)
        // Killed during warm-up: nothing was recorded.
        if (r.events.none { it.type == "START" }) { journal.delete(); stopSelf(); return }
        rec = r
        saving = false
        // Intervals aren't in config.json; the setup they started with is still in prefs.
        val intervals = if (info.workout == Intervals.NAME) Intervals.load(this) else null
        workout = intervals?.workout() ?: cfg.workouts.firstOrNull { it.name == info.workout }
        lapStartWall = info.lapEndWall; lapStartActive = info.lapEndActive; lapStartMeters = info.lapEndMeters
        stepIdx = info.stepIdx; stepStartActive = info.stepStartActive; stepStartMeters = info.stepStartMeters
        RunRepo.state.value = RunState(
            status = Status.ACTIVE, mode = info.mode,
            step = workout?.steps?.getOrNull(stepIdx), stepNo = stepIdx, stepCount = workout?.steps?.size ?: 0,
            race = cfg.races.firstOrNull { it.name == info.race }, meters = info.lapEndMeters,
            intervals = intervals,
        )
        lifecycleScope.launch {
            val tracked = client.getCurrentExerciseInfoAsync().await().exerciseTrackedStatus
            if (tracked == ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS) {
                ServiceCompat.startForeground(
                    this@ExerciseService, 1, notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
                recovering = true
                client.setUpdateCallback(callback)
                tick()
            } else {
                val end = maxOf(rec.locs.lastOrNull()?.t ?: 0, rec.hr.lastOrNull()?.first ?: 0, rec.start)
                // Already ended, so just save it: no foreground service, which Android refuses (location type)
                // unless the app is on screen. Health Services' total is gone; carry on from the last lap's total with GPS for the rest.
                finish(rec.activeFromEvents(end), lapStartMeters + rec.gpsMeters(lapStartWall), end)
            }
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
        if (saving) return
        val boot = Instant.ofEpochMilli(bootWall)
        val m = u.latestMetrics
        // Each sample keeps its own sensor timestamp, never the delivery time.
        m.getData(DataType.LOCATION).forEach { p ->
            val v = p.value
            rec.addLoc(Loc(p.getTimeInstant(boot).toEpochMilli(), v.latitude, v.longitude, Recorder.altitude(v.altitude)))
        }
        m.getData(DataType.HEART_RATE_BPM).forEach { p -> rec.addHr(p.getTimeInstant(boot).toEpochMilli(), p.value.toInt()) }
        m.getData(DataType.STEPS_PER_MINUTE).forEach { p -> rec.addCadence(p.getTimeInstant(boot).toEpochMilli(), p.value.toDouble()) }
        m.getData(DataType.STRIDE_LENGTH).forEach { p -> rec.addStride(p.getTimeInstant(boot).toEpochMilli(), p.value) }
        m.getData(DataType.ABSOLUTE_ELEVATION).forEach { p -> rec.addElevation(p.getTimeInstant(boot).toEpochMilli(), p.value) }
        val total = m.getData(DataType.DISTANCE_TOTAL)?.total
        u.activeDurationCheckpoint?.let {
            cpWall = it.time.toEpochMilli()
            cpActive = it.activeDuration.toMillis()
        }
        val prev = RunRepo.state.value.status
        val status = when (u.exerciseStateInfo.state) {
            ExerciseState.ACTIVE -> Status.ACTIVE
            ExerciseState.USER_PAUSED -> Status.PAUSED
            ExerciseState.ENDED -> Status.ENDED
            else -> prev
        }
        running = status == Status.ACTIVE
        if (status != prev) onStatus(prev, status)
        update { it.copy(status = status, meters = total ?: it.meters, hr = rec.hr.lastOrNull()?.second ?: it.hr) }
    }

    private fun onStatus(prev: Status, now: Status) {
        // After a crash the first update only re-syncs state; its events are already in the journal.
        if (recovering) { recovering = false; if (now == Status.ENDED) finish(); return }
        when {
            prev == Status.STARTING && now == Status.ACTIVE -> {
                rec.addEvent("START")
                lapStartWall = System.currentTimeMillis()
                buzz(LONG)
            }
            now == Status.PAUSED -> { rec.addEvent("PAUSE"); buzz(SHORT) }
            prev == Status.PAUSED && now == Status.ACTIVE -> { rec.addEvent("RESUME"); buzz(SHORT) }
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
            if (running) rec.recentElevation()?.let { grade.add(s.meters, it) }
            val pct = grade.percent
            val gap = pace?.let { Grade.adjust(it, pct) }

            val wo = workout
            if (wo != null && stepIdx < wo.steps.size) {
                val step = wo.steps[stepIdx]
                val done = (step.meters != null && s.meters - stepStartMeters >= step.meters) ||
                    (step.seconds != null && act - stepStartActive >= step.seconds * 1000)
                if (done) advanceStep(if (step.meters != null) "DISTANCE" else "TIME") else checkPace(step, pace, act)
            } else if (wo == null && s.meters - lapStartMeters >= cfg.autoLapMeters) {
                lap("DISTANCE")
            }
            if (running) checkPacer(act, s.meters, pct)
            s.race?.let { r -> if (!raceFinished && s.meters >= r.meters) { raceFinished = true; buzz(TRIPLE) } }

            update {
                it.copy(
                    activeMs = act, pace = pace, grade = pct, gap = gap,
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

    /**
     * Virtual pacer: at each quarter split, judges that split's grade-adjusted pace against the range
     * (so a climb isn't "too slow" and a descent isn't "too fast"). Silent when it's in range.
     */
    private fun checkPacer(act: Long, meters: Double, percent: Double?) {
        pacer?.tick(act, meters, percent)?.let { onSplit(it) }
        val held = heldSplit ?: return
        // A mile split keeps its buzz and screen (its last quarter ends with it); the warning waits its turn.
        val now = System.currentTimeMillis()
        if (now - (RunRepo.state.value.lastLapAt ?: 0) < LAP_QUIET_MS) return
        heldSplit = null
        buzz(if (held.zone < 0) TOO_FAST else TOO_SLOW)
        update { it.copy(pacerSplit = held, pacerAlertAt = now) }
    }

    /** An out-of-range split is held for [checkPacer] to announce; an in-range one is just noted. */
    private fun onSplit(split: SplitPacer.Split) {
        if (split.zone != 0) heldSplit = split else update { it.copy(pacerSplit = split) }
    }

    private fun advanceStep(trigger: String) {
        val wo = workout ?: return
        lap(trigger, fromStep = true)
        stepIdx++
        stepStartActive = activeNow()
        stepStartMeters = RunRepo.state.value.meters
        rec.logStep(stepIdx, stepStartActive, stepStartMeters)
        offTargetSince = 0
        val iv = RunRepo.state.value.intervals
        buzz(when {
            iv == null -> if (stepIdx >= wo.steps.size) TRIPLE else LONG
            iv.kind(stepIdx) == Intervals.Kind.ON -> LONG
            iv.kind(stepIdx) == Intervals.Kind.REST -> DOUBLE
            else -> TRIPLE
        })
        update { it.copy(step = wo.steps.getOrNull(stepIdx), stepNo = stepIdx, paceFlag = 0) }
    }

    private fun lap(trigger: String, fromStep: Boolean = false) {
        val s = RunRepo.state.value
        if (s.status !in setOf(Status.ACTIVE, Status.PAUSED)) return
        // In intervals, crown up only moves on from an open interval, and cool down is the last one.
        s.intervals?.let { iv ->
            if (!fromStep && (s.step?.untilLap != true || iv.isLast(stepIdx))) return
        }
        // In a workout, the lap button ends a lap-terminated step.
        if (!fromStep && workout?.steps?.getOrNull(stepIdx)?.untilLap == true) return advanceStep(trigger)
        val now = System.currentTimeMillis()
        val act = activeNow()
        rec.addLap(Lap(lapStartWall, now, act - lapStartActive, s.meters - lapStartMeters, trigger, rec.avgHr(lapStartWall, now)), act, s.meters)
        update { it.copy(lastLapMs = act - lapStartActive, lastLapMeters = s.meters - lapStartMeters, lastLapNo = rec.laps.size, lastLapAt = now) }
        pacer?.lap(act, s.meters)?.let { onSplit(it) }
        lapStartWall = now; lapStartActive = act; lapStartMeters = s.meters
        if (!fromStep) buzz(if (trigger == "DISTANCE") MILE else SHORT)
    }

    private fun finish(activeMs: Long = cpActive, meters: Double = RunRepo.state.value.meters, now: Long = System.currentTimeMillis()) {
        if (saving) return
        saving = true
        rec.addEvent("STOP", now)
        cpActive = activeMs
        update { it.copy(meters = meters) }
        val s = RunRepo.state.value
        if (now - lapStartWall > 2000 || rec.laps.isEmpty()) {
            rec.addLap(Lap(lapStartWall, now, cpActive - lapStartActive, s.meters - lapStartMeters, "MANUAL", rec.avgHr(lapStartWall, now)), cpActive, s.meters)
        }
        val r = rec
        val base = File(File(filesDir, "runs"), Recorder.iso(r.start).replace(":", ""))
        val meta = org.json.JSONObject().put("name", s.mode).put("activeMs", cpActive).put("meters", s.meters)
            // Start time and the watch's (network-set) time zone, so the run is named in local time wherever it uploads.
            .put("start", r.start).put("tz", java.util.TimeZone.getDefault().id).toString()
        // Ended now: no more laps, and the tick loop stops.
        update { it.copy(status = Status.ENDED, activeMs = cpActive) }
        lifecycleScope.launch {
            // A long run's TCX takes a while to build; on the main thread the watch stopped responding
            // to touches, and closing it from the "not responding" dialog lost the save.
            withContext(Dispatchers.IO) {
                File("$base.tcx").writeText(r.toTcx())
                File("$base.meta.json").writeText(meta)
            }
            // Every finished run is uploaded; SyncWorker deletes it from the watch after 30 days.
            SyncWorker.enqueue(this@ExerciseService)
            buzz(LONG)
            // A new run may have started from the summary while this one was being written; leave it alone.
            if (rec !== r) return@launch
            r.journalFile.delete()
            update { it.copy(savedFile = base.name) }
            ServiceCompat.stopForeground(this@ExerciseService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Back out of the warm-up without recording anything. */
    private fun cancel() {
        lifecycleScope.launch {
            // Detach first so the ENDED update doesn't save an empty run.
            runCatching { client.clearUpdateCallbackAsync(callback).await() }
            runCatching { client.endExerciseAsync().await() }
            File(File(filesDir, "runs"), "current.journal").delete()
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
        val builder = NotificationCompat.Builder(this, "run")
            .setSmallIcon(R.drawable.ic_roadrunner).setContentTitle(getString(R.string.app_name)).setContentText("Recording")
            .setOngoing(true).setContentIntent(open).setCategory(NotificationCompat.CATEGORY_WORKOUT)
        // Shows a run chip on the watch face that jumps back into the app.
        OngoingActivity.Builder(this, 1, builder)
            .setStaticIcon(R.drawable.ic_roadrunner).setTouchIntent(open)
            .setStatus(androidx.wear.ongoing.Status.Builder().addTemplate("Run").build())
            .build().apply(this)
        return builder.build()
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_GO = "go"
        const val ACTION_RECOVER = "recover"
        const val ACTION_CANCEL = "cancel"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_LAP = "lap"
        const val ACTION_END = "end"
        const val EXTRA_WORKOUT = "workout"
        const val EXTRA_RACE = "race"
        /** Start the intervals saved by [Intervals.save]. */
        const val EXTRA_INTERVALS = "intervals"
        const val EXTRA_PACER_FAST = "pacerFast"
        const val EXTRA_PACER_SLOW = "pacerSlow"
        private val SHORT = longArrayOf(0, 150)
        private val DOUBLE = longArrayOf(0, 120, 120, 120)
        private val TRIPLE = longArrayOf(0, 200, 150, 200, 150, 200)
        private val LONG = longArrayOf(0, 500)
        private val MILE = longArrayOf(0, 400, 150, 400)
        /** A quarter warning landing on a lap waits this long: the lap's flash (5 s) plus a beat. */
        private const val LAP_QUIET_MS = 7_000L
        // Pacer, only for a split out of range: quick pulses when too fast, one long pull when too slow.
        private val TOO_FAST = longArrayOf(0, 100, 80, 100, 80, 100, 80, 100)
        private val TOO_SLOW = longArrayOf(0, 900)

        fun send(ctx: Context, action: String, extras: Intent.() -> Unit = {}) {
            val i = Intent(ctx, ExerciseService::class.java).setAction(action).apply(extras)
            // Recovery goes foreground only if the run is still going (see [recover]), so it mustn't promise to.
            if (action == ACTION_START) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
