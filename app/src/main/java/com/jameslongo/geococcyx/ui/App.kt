package com.jameslongo.geococcyx.ui

import android.view.WindowManager
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.jameslongo.geococcyx.R
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.jameslongo.geococcyx.Config
import com.jameslongo.geococcyx.ExerciseService
import com.jameslongo.geococcyx.Intervals
import com.jameslongo.geococcyx.MainActivity
import com.jameslongo.geococcyx.RunRepo
import com.jameslongo.geococcyx.RunState
import com.jameslongo.geococcyx.Status
import com.jameslongo.geococcyx.field
import com.jameslongo.geococcyx.fmtDist
import com.jameslongo.geococcyx.fmtDur
import com.jameslongo.geococcyx.fmtPace

private enum class Menu { HOME, INTERVALS, PACER }

@Composable
fun App(act: MainActivity) {
    val s by RunRepo.flow.collectAsState()
    var cfg by remember { mutableStateOf(Config.load(act)) }
    var menu by remember { mutableStateOf(Menu.HOME) }
    var missing by remember { mutableStateOf(act.missingPermissions()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = act.missingPermissions()
    }
    LaunchedEffect(Unit) { if (missing.isNotEmpty()) ask.launch(missing) }
    fun start(intervals: Boolean = false, pacer: Pair<Double, Double>? = null) {
        if (missing.isNotEmpty()) { ask.launch(missing); return }
        ExerciseService.send(act, ExerciseService.ACTION_START) {
            if (intervals) putExtra(ExerciseService.EXTRA_INTERVALS, true)
            pacer?.let { putExtra(ExerciseService.EXTRA_PACER_FAST, it.first); putExtra(ExerciseService.EXTRA_PACER_SLOW, it.second) }
        }
    }
    // A tap on the tile: do what its button's chip would, unless a run is already on.
    val launch = act.launch.value
    LaunchedEffect(launch) {
        if (launch == null) return@LaunchedEffect
        act.launch.value = null
        if (s.status != Status.IDLE) return@LaunchedEffect
        when (launch) {
            MainActivity.LAUNCH_RUN -> { menu = Menu.HOME; start() }
            MainActivity.LAUNCH_INTERVALS -> menu = Menu.INTERVALS
            MainActivity.LAUNCH_PACER -> menu = Menu.PACER
        }
    }

    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (s.status) {
                Status.STARTING, Status.ACTIVE, Status.PAUSED ->
                    RunScreen(act, s, cfg, act.ambient.value)
                Status.ENDED -> Summary(s, cfg) { RunRepo.state.value = RunState(); cfg = Config.load(act) }
                Status.IDLE -> {
                    BackHandler(menu != Menu.HOME) { menu = Menu.HOME }
                    when (menu) {
                        Menu.HOME -> ChipList(stringResource(R.string.app_name), buildList {
                            add("Run" to { start() })
                            add("Intervals ›" to { menu = Menu.INTERVALS })
                            add("Pacer ›" to { menu = Menu.PACER })
                            if (missing.isNotEmpty()) add("Grant permissions" to { ask.launch(missing) })
                        })
                        Menu.PACER -> PacerSetup(act, cfg) { fast, slow -> menu = Menu.HOME; start(pacer = fast to slow) }
                        Menu.INTERVALS -> IntervalsSetup(act) { menu = Menu.HOME; start(intervals = true) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipList(title: String, items: List<Pair<String, () -> Unit>>) {
    ScalingLazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        item { Text(title, color = ACCENT, fontWeight = FontWeight.Bold) }
        items(items) { (label, onClick) ->
            Chip(
                label = { Text(label) }, onClick = onClick, modifier = Modifier.fillMaxWidth(),
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}

@Composable
private fun RunScreen(act: MainActivity, s: RunState, cfg: Config, ambient: Boolean) {
    // Screen stays on for the whole run rather than timing out to ambient or the watch face.
    DisposableEffect(Unit) {
        act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    if (s.status == Status.STARTING) {
        BackHandler { ExerciseService.send(act, ExerciseService.ACTION_CANCEL) }
        ReadyPage(s.gpsReady) { ExerciseService.send(act, ExerciseService.ACTION_GO) }
        return
    }
    // One data screen; touches (sweat, rain, a sleeve) do nothing. The crown twist pauses and resumes.
    // While paused, holding the screen for END_HOLD_MS ends the run, with a ring filling around the edge.
    val canEnd = s.status == Status.PAUSED
    val hold = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var holdJob by remember { mutableStateOf<Job?>(null) }
    val haptic = LocalHapticFeedback.current
    // A lap just closed or the pacer fired: it fills the screen for a few seconds (the newer one wins).
    var flash by remember { mutableStateOf(false) }
    val iv = s.intervals
    LaunchedEffect(s.lastLapAt) {
        val left = (s.lastLapAt ?: 0) + (if (iv != null) REP_FLASH_MS else LAP_FLASH_MS) - System.currentTimeMillis()
        if (left > 0) { flash = true; kotlinx.coroutines.delay(left); flash = false }
    }
    var pacerFlash by remember { mutableStateOf(false) }
    LaunchedEffect(s.pacerAlertAt) {
        val left = (s.pacerAlertAt ?: 0) + PACER_FLASH_MS - System.currentTimeMillis()
        if (left > 0) { pacerFlash = true; kotlinx.coroutines.delay(left); pacerFlash = false }
    }
    val pacerOnTop = pacerFlash && (!flash || (s.pacerAlertAt ?: 0) > (s.lastLapAt ?: 0))
    // Resumed mid-hold (crown twist): drop the hold.
    LaunchedEffect(canEnd) { if (!canEnd) { holdJob?.cancel(); hold.snapTo(0f) } }
    // A swipe back mid-run would leave the app.
    BackHandler {}
    Box(
        Modifier.fillMaxSize().pointerInput(canEnd) {
            if (!canEnd) return@pointerInput
            awaitEachGesture {
                awaitFirstDown()
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                val job = scope.launch {
                    hold.animateTo(1f, tween(END_HOLD_MS, easing = LinearEasing))
                    ExerciseService.send(act, ExerciseService.ACTION_END)
                }
                holdJob = job
                waitForUpOrCancellation()
                if (job.isActive) { job.cancel(); scope.launch { hold.animateTo(0f, tween(200)) } }
            }
        },
    ) {
        when {
            iv != null -> when {
                ambient -> IntervalPage(s, iv, dim = true)
                canEnd -> IntervalPausedPage(s, iv)
                // Only an ON rep closing shows its time: going into a rest or the cool down.
                flash && iv.kind(s.stepNo) in setOf(Intervals.Kind.REST, Intervals.Kind.COOL_DOWN) -> RepFlash(s, iv)
                else -> IntervalPage(s, iv, dim = false)
            }
            pacerOnTop -> PacerFlash(s, cfg)
            flash -> LapFlash(s, cfg)
            ambient -> DataPage(cfg.fields, s, cfg, dim = true)
            canEnd -> PausedPage(s, cfg)
            // A workout or race shows its own screen: step targets or the gap to goal.
            s.step != null -> StepPage(s, cfg)
            s.race != null -> RacePage(s, cfg)
            else -> DataPage(cfg.fields, s, cfg, dim = false)
        }
        if (canEnd && !ambient && (iv != null || (!flash && !pacerFlash))) {
            val dx = rememberDx()
            Canvas(Modifier.fillMaxSize()) {
                val w = dx.dp(12).toPx()
                drawArc(
                    Color.White, startAngle = -90f, sweepAngle = 360f * hold.value, useCenter = false,
                    topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round),
                )
            }
        }
    }
}

private const val END_HOLD_MS = 3000
private const val LAP_FLASH_MS = 5000L
private const val REP_FLASH_MS = 10_000L
private const val PACER_FLASH_MS = 3000L
private val TOO_FAST_BG = Color(0xFF3AD1F2)
private val TOO_SLOW_BG = Color(0xFFFF7A1A)

/**
 * Pacer alert, only for a quarter split out of range: the whole screen says which way,
 * with that split's (grade-adjusted) pace dead center.
 */
@Composable
private fun PacerFlash(s: RunState, cfg: Config) {
    val dx = rememberDx()
    val split = s.pacerSplit ?: return
    val (bg, name) = if (split.zone < 0) TOO_FAST_BG to "TOO FAST" else TOO_SLOW_BG to "TOO SLOW"
    val pace = fmtPace(split.pace, cfg.metersPerUnit)
    Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
        Text(pace, style = number(dx, fit(pace, 128, 4), Color.Black))
        Text(name, style = label(dx, 26, Color.Black, spacing = 0.2f), modifier = Modifier.offset(y = -dx.dp(78)))
        val what = "¼ ${cfg.unitLabel.uppercase()}" + if (split.graded) " · GRADE ADJ" else ""
        Text(what, style = label(dx, 16, Color.Black, spacing = 0.12f), modifier = Modifier.offset(y = dx.dp(78)))
    }
}

/**
 * Pacer setup: tap FASTEST or SLOWEST to pick it, then turn the crown (or tap − / +) to set it
 * in 5 s steps. The last range is remembered.
 */
@Composable
private fun PacerSetup(act: MainActivity, cfg: Config, start: (fast: Double, slow: Double) -> Unit) {
    val dx = rememberDx()
    val prefs = remember { act.getSharedPreferences("pacer", 0) }
    var fast by remember { mutableIntStateOf(prefs.getInt("fast", 480)) }
    var slow by remember { mutableIntStateOf(prefs.getInt("slow", 510)) }
    var editing by remember { mutableIntStateOf(0) }
    fun step(by: Int) {
        // Fastest stays at least 5 s quicker than slowest.
        if (editing == 0) fast = (fast + by).coerceIn(180, slow - 5) else slow = (slow + by).coerceIn(fast + 5, 1200)
    }
    val focus = remember { FocusRequester() }
    var scrolled by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier.fillMaxSize().onRotaryScrollEvent {
            scrolled += it.verticalScrollPixels
            while (scrolled > PACER_CROWN_PX) { step(5); scrolled -= PACER_CROWN_PX }
            while (scrolled < -PACER_CROWN_PX) { step(-5); scrolled += PACER_CROWN_PX }
            true
        }.focusRequester(focus).focusable(),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("PACER /${cfg.unitLabel.uppercase()}", style = label(dx, 14, ACCENT, spacing = 0.18f))
        Spacer(Modifier.height(dx.dp(8)))
        PaceRow(dx, "FASTEST", fast, editing == 0, { editing = 0 }) { editing = 0; step(it) }
        Spacer(Modifier.height(dx.dp(10)))
        PaceRow(dx, "SLOWEST", slow, editing == 1, { editing = 1 }) { editing = 1; step(it) }
        Spacer(Modifier.height(dx.dp(14)))
        Box(
            Modifier.size(dx.dp(200), dx.dp(72)).clip(RoundedCornerShape(dx.dp(10))).background(ACCENT).clickable {
                prefs.edit().putInt("fast", fast).putInt("slow", slow).apply()
                start(fast / cfg.metersPerUnit, slow / cfg.metersPerUnit)
            },
            contentAlignment = Alignment.Center,
        ) { Text("START", style = label(dx, 22, Color.Black, spacing = 0.1f)) }
    }
}

private const val PACER_CROWN_PX = 60f

@Composable
private fun PaceRow(dx: Dx, name: String, sec: Int, selected: Boolean, select: () -> Unit, step: (Int) -> Unit) {
    val value = "%d:%02d".format(sec / 60, sec % 60)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name, style = label(dx, 12, if (selected) ACCENT else Color(0xFF9A9A9A)))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dx.dp(10))) {
            StepButton(dx, "−") { step(-5) }
            Box(
                Modifier.width(dx.dp(150)).clip(RoundedCornerShape(dx.dp(10)))
                    .border(dx.dp(2), if (selected) ACCENT else Color.Transparent, RoundedCornerShape(dx.dp(10)))
                    .clickable(onClick = select).padding(vertical = dx.dp(4)),
                contentAlignment = Alignment.Center,
            ) { Text(value, style = number(dx, fit(value, 64, 4), if (selected) ACCENT else Color.White)) }
            StepButton(dx, "+") { step(5) }
        }
    }
}

@Composable
private fun StepButton(dx: Dx, sign: String, onClick: () -> Unit) = Box(
    Modifier.size(dx.dp(72)).clip(RoundedCornerShape(50)).background(Color(0xFF2A2A2A)).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
) { Text(sign, style = number(dx, 40, Color.White)) }

/**
 * Intervals setup: tap REPS, ON or REST to pick it, then turn the crown to set it. ON and REST go
 * in 15 s steps; below 0:15 they're OPEN (move on with the crown). The last setup is remembered.
 */
@Composable
private fun IntervalsSetup(act: MainActivity, start: () -> Unit) {
    val dx = rememberDx()
    val saved = remember { Intervals.load(act) }
    var reps by remember { mutableIntStateOf(saved.reps) }
    var on by remember { mutableIntStateOf(saved.onSec) }
    var rest by remember { mutableIntStateOf(saved.restSec) }
    var editing by remember { mutableIntStateOf(0) }
    fun step(by: Int) = when (editing) {
        0 -> reps = (reps + by).coerceIn(1, 30)
        1 -> on = (on + 15 * by).coerceIn(0, 1200)
        else -> rest = (rest + 15 * by).coerceIn(0, 1200)
    }
    val focus = remember { FocusRequester() }
    var scrolled by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier.fillMaxSize().onRotaryScrollEvent {
            scrolled += it.verticalScrollPixels
            while (scrolled > PACER_CROWN_PX) { step(1); scrolled -= PACER_CROWN_PX }
            while (scrolled < -PACER_CROWN_PX) { step(-1); scrolled += PACER_CROWN_PX }
            true
        }.focusRequester(focus).focusable(),
        verticalArrangement = Arrangement.spacedBy(dx.dp(8), Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("INTERVALS", style = label(dx, 14, ACCENT, spacing = 0.18f))
        SetupRow(dx, "REPS", reps.toString(), editing == 0) { editing = 0 }
        SetupRow(dx, "ON", fmtOpen(on), editing == 1) { editing = 1 }
        SetupRow(dx, "REST", fmtOpen(rest), editing == 2) { editing = 2 }
        Box(
            Modifier.padding(top = dx.dp(6)).size(dx.dp(200), dx.dp(64)).clip(RoundedCornerShape(dx.dp(10))).background(ACCENT).clickable {
                Intervals(reps, on, rest).save(act)
                start()
            },
            contentAlignment = Alignment.Center,
        ) { Text("START", style = label(dx, 22, Color.Black, spacing = 0.1f)) }
    }
}

private fun fmtOpen(sec: Int) = if (sec == 0) "OPEN" else "%d:%02d".format(sec / 60, sec % 60)

@Composable
private fun SetupRow(dx: Dx, name: String, value: String, selected: Boolean, select: () -> Unit) {
    val open = value == "OPEN"
    Row(
        Modifier.size(dx.dp(290), dx.dp(66)).clip(RoundedCornerShape(dx.dp(12)))
            .border(dx.dp(2), if (selected) ACCENT else Color(0xFF262626), RoundedCornerShape(dx.dp(12)))
            .clickable(onClick = select).padding(horizontal = dx.dp(18)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(name, style = label(dx, 14, if (selected) ACCENT else GRAY))
        Text(
            value,
            style = if (open) number(dx, 30, Color(0xFF6A6A6A)) else number(dx, 46, if (selected) ACCENT else Color.White),
        )
    }
}

/**
 * One interval: its time counting up dead center, its name just above. ON fills the screen with
 * the accent so it reads at a glance; a REST says how many reps are left. A timed interval
 * fills a ring around the edge as it goes.
 */
@Composable
private fun IntervalPage(s: RunState, iv: Intervals, dim: Boolean) {
    val dx = rememberDx()
    val kind = iv.kind(s.stepNo)
    val on = kind == Intervals.Kind.ON && !dim
    val time = fmtDur(s.stepMs)
    val fg = when { dim -> GRAY; on -> Color.Black; else -> Color.White }
    val lc = when { dim -> GRAY; on -> Color.Black; kind == Intervals.Kind.REST -> ACCENT; else -> Color.White }
    Box(Modifier.fillMaxSize().background(if (on) ACCENT else Color.Black), contentAlignment = Alignment.Center) {
        Text(time, style = number(dx, fit(time, 150, 4), fg))
        Text(
            kind.label, style = label(dx, if (kind.label.length <= 4) 44 else 40, lc, spacing = 0.14f),
            modifier = Modifier.offset(y = -dx.dp(102)),
        )
        if (kind == Intervals.Kind.REST) {
            Text(
                "${iv.repsLeft(s.stepNo)} MORE", style = label(dx, 30, if (dim) GRAY else Color(0xFFBDBDBD), spacing = 0.12f),
                modifier = Modifier.offset(y = dx.dp(96)),
            )
        }
        val sec = s.step?.seconds
        if (sec != null && !dim) {
            val done = (s.stepMs / (sec * 1000)).toFloat().coerceIn(0f, 1f)
            Canvas(Modifier.fillMaxSize()) {
                val w = dx.dp(13).toPx()
                val tl = Offset(w / 2, w / 2)
                val sz = Size(size.width - w, size.height - w)
                drawArc(if (on) Color.Black.copy(alpha = 0.15f) else Color(0xFF262626), 0f, 360f, false, tl, sz, style = Stroke(w))
                drawArc(if (on) Color.Black else ACCENT, -90f, 360f * done, false, tl, sz, style = Stroke(w))
            }
        }
    }
}

/** Paused in intervals: the interval's time stays centered; hold anywhere to end. */
@Composable
private fun IntervalPausedPage(s: RunState, iv: Intervals) {
    val dx = rememberDx()
    val time = fmtDur(s.stepMs)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(dx.dp(112)).background(ACCENT).padding(bottom = dx.dp(10)),
            contentAlignment = Alignment.BottomCenter,
        ) { Text("PAUSED", style = label(dx, 28, Color.Black, spacing = 0.18f)) }
        Text(time, style = number(dx, fit(time, 120, 4), Color.White))
        Text(iv.kind(s.stepNo).label, style = label(dx, 22, Color(0xFFBDBDBD)), modifier = Modifier.offset(y = dx.dp(80)))
        Text("HOLD TO END", style = label(dx, 14, Color.White, spacing = 0.14f), modifier = Modifier.offset(y = dx.dp(122)))
    }
}

/** A rep just ended: its time on white for the first [REP_FLASH_MS] of the rest. */
@Composable
private fun RepFlash(s: RunState, iv: Intervals) {
    val dx = rememberDx()
    val time = fmtDur(s.lastLapMs ?: 0)
    val next = if (iv.kind(s.stepNo) == Intervals.Kind.REST) "REST · ${iv.repsLeft(s.stepNo)} MORE" else "COOL DOWN"
    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
        Text(time, style = number(dx, fit(time, 128, 4), Color.Black))
        Text("REP ${iv.rep(s.stepNo)}", style = label(dx, 26, Color.Black, spacing = 0.2f), modifier = Modifier.offset(y = -dx.dp(78)))
        Text(next, style = label(dx, 24, Color(0xFF444444), spacing = 0.12f), modifier = Modifier.offset(y = dx.dp(78)))
    }
}

/** Full screen before the run: gray while GPS searches, then the whole screen is the start button. */
@Composable
private fun ReadyPage(gpsReady: Boolean, go: () -> Unit) {
    val dx = rememberDx()
    if (gpsReady) {
        Box(Modifier.fillMaxSize().background(ACCENT).clickable(onClick = go), contentAlignment = Alignment.Center) {
            Text("START", style = label(dx, 84, Color.Black, spacing = 0.06f))
        }
    } else {
        Column(Modifier.fillMaxSize().background(Color(0xFF333333)), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("FINDING", style = label(dx, 62, Color.White, spacing = 0.06f))
            Text("GPS", style = label(dx, 62, Color.White, spacing = 0.06f))
        }
    }
}

/** A closed lap: the whole screen turns accent, its pace dead center with the lap's name above. */
@Composable
private fun LapFlash(s: RunState, cfg: Config) {
    val dx = rememberDx()
    // Auto-laps in a plain run are whole miles (or km); workout steps are just laps.
    val unit = if (cfg.unitLabel == "mi") "MILE" else "KM"
    val name = if (s.step == null && s.lastLapMeters != null && s.lastLapMeters >= cfg.metersPerUnit * 0.99) "$unit ${s.lastLapNo}" else "LAP ${s.lastLapNo}"
    val pace = fmtPace(s.lastLapMeters?.takeIf { it > 10 }?.let { (s.lastLapMs ?: 0) / 1000.0 / it }, cfg.metersPerUnit)
    Box(Modifier.fillMaxSize().background(ACCENT), contentAlignment = Alignment.Center) {
        Text(pace, style = number(dx, fit(pace, 128, 4), Color.Black))
        Text(name, style = label(dx, 26, Color.Black, spacing = 0.2f), modifier = Modifier.offset(y = -dx.dp(78)))
    }
}

/**
 * The running screen: [keys] fill the top, middle-left, middle-right and bottom fields.
 * Numbers are as large as the round screen allows; longer values shrink to fit.
 */
@Composable
private fun DataPage(keys: List<String>, s: RunState, cfg: Config, dim: Boolean) {
    val dx = rememberDx()
    val (topL, topV) = field(keys.getOrElse(0) { "distance" }, s, cfg)
    val (leftL, leftV) = field(keys.getOrElse(1) { "lapPace" }, s, cfg)
    val (rightL, rightV) = field(keys.getOrElse(2) { "avgPace" }, s, cfg)
    val (botL, botV) = field(keys.getOrElse(3) { "time" }, s, cfg)
    val fg = if (dim) Color(0xFF9A9A9A) else Color.White
    val lc = if (dim) Color(0xFF9A9A9A) else ACCENT
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().height(dx.dp(150)).padding(bottom = dx.dp(6)),
            verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(topL, style = label(dx, 12, lc))
            Text(topV, style = number(dx, fit(topV, 100, 4), fg))
        }
        Divider(dx)
        Row(Modifier.fillMaxWidth().height(dx.dp(118))) {
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(end = dx.dp(10)),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.End,
            ) {
                Text(leftL, style = label(dx, 12, lc))
                Text(leftV, style = number(dx, fit(leftV, 74, 4), fg))
            }
            Box(Modifier.width(dx.dp(2)).fillMaxHeight().background(LINE))
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(start = dx.dp(10)),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.Start,
            ) {
                Text(rightL, style = label(dx, 12, lc))
                Text(rightV, style = number(dx, fit(rightV, 74, 4), fg))
            }
        }
        Divider(dx)
        Column(Modifier.fillMaxWidth().padding(top = dx.dp(8)), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(botV, style = number(dx, fit(botV, 86, 5), fg))
            Text(botL, style = label(dx, 12, lc))
        }
    }
}

/** Paused: an accent band says so; hold anywhere to end (the ring is drawn over this by RunScreen). */
@Composable
private fun PausedPage(s: RunState, cfg: Config) {
    val dx = rememberDx()
    val time = fmtDur(s.activeMs)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(dx.dp(142)).background(ACCENT).padding(bottom = dx.dp(10)), contentAlignment = Alignment.BottomCenter) {
            Text("PAUSED", style = label(dx, 30, Color.Black, spacing = 0.18f))
        }
        Spacer(Modifier.height(dx.dp(14)))
        Text(time, style = number(dx, fit(time, 100, 5), Color.White))
        Spacer(Modifier.height(dx.dp(8)))
        val dist = "${fmtDist(s.meters, cfg.metersPerUnit)} ${cfg.unitLabel.uppercase()}"
        Text(dist, style = number(dx, fit(dist, 46, 7), Color(0xFFD0D0D0)))
        Spacer(Modifier.height(dx.dp(14)))
        Text("HOLD TO END", style = label(dx, 14, Color.White, spacing = 0.14f))
    }
}

@Composable
private fun StepPage(s: RunState, cfg: Config) {
    val step = s.step ?: return Center("Workout done", "Tap ◀ for data")
    val u = cfg.metersPerUnit
    val remaining = when {
        step.meters != null -> fmtDist((step.meters - s.stepMeters).coerceAtLeast(0.0), u) + " " + cfg.unitLabel
        step.seconds != null -> fmtDur(((step.seconds * 1000) - s.stepMs).toLong().coerceAtLeast(0))
        else -> "press lap"
    }
    val color = when (s.paceFlag) { -1 -> Color(0xFF4FC3F7); 1 -> Color(0xFFFFB74D); else -> Color.White }
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${s.stepNo + 1}/${s.stepCount} ${step.name}", color = ACCENT, fontSize = 13.sp, textAlign = TextAlign.Center)
        Text(remaining, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text(fmtPace(s.pace, u), color = color, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        if (step.paceFast != null) {
            Text("target ${fmtPace(step.paceFast, u)}–${fmtPace(step.paceSlow, u)}", color = Color.Gray, fontSize = 12.sp)
            Text(when (s.paceFlag) { -1 -> "TOO FAST"; 1 -> "TOO SLOW"; else -> "ON PACE" }, color = color, fontSize = 12.sp)
        }
    }
}

@Composable
private fun RacePage(s: RunState, cfg: Config) {
    val r = s.race ?: return
    val targetPace = r.targetSec / r.meters
    val ahead = targetPace * s.meters - s.activeMs / 1000.0
    val avg = if (s.meters > 50) s.activeMs / 1000.0 / s.meters else targetPace
    val projected = s.activeMs / 1000.0 + (r.meters - s.meters).coerceAtLeast(0.0) * avg
    val u = cfg.metersPerUnit
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(r.name, color = ACCENT, fontSize = 13.sp)
        Text(
            (if (ahead >= 0) "-" else "+") + fmtDur((kotlin.math.abs(ahead) * 1000).toLong()),
            color = if (ahead >= 0) Color(0xFF81C784) else Color(0xFFE57373), fontSize = 38.sp, fontWeight = FontWeight.Bold,
        )
        Text(if (ahead >= 0) "ahead of target" else "behind target", color = Color.Gray, fontSize = 11.sp)
        Text("proj ${fmtDur((projected * 1000).toLong())}", color = Color.White, fontSize = 20.sp)
        Text("${fmtDist((r.meters - s.meters).coerceAtLeast(0.0), u)} ${cfg.unitLabel} to go · tgt ${fmtPace(targetPace, u)}", color = Color.Gray, fontSize = 11.sp)
    }
}

@Composable
private fun Summary(s: RunState, cfg: Config, done: () -> Unit) {
    val dx = rememberDx()
    val u = cfg.metersPerUnit
    val time = fmtDur(s.activeMs)
    val dist = fmtDist(s.meters, u)
    val avg = fmtPace(if (s.meters > 10) s.activeMs / 1000.0 / s.meters else null, u)
    val gray = Color(0xFF9A9A9A)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("SUMMARY", style = label(dx, 14, ACCENT, spacing = 0.18f))
        Spacer(Modifier.height(dx.dp(6)))
        Column(Modifier.width(dx.dp(300)), horizontalAlignment = Alignment.CenterHorizontally) {
            Divider(dx)
            Text(time, style = number(dx, fit(time, 72, 5), Color.White), modifier = Modifier.padding(top = dx.dp(6)))
            Text("TIME", style = label(dx, 11, gray), modifier = Modifier.padding(bottom = dx.dp(6)))
            Divider(dx)
            Row(Modifier.height(IntrinsicSize.Min)) {
                Column(Modifier.weight(1f).padding(vertical = dx.dp(6)), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dist, style = number(dx, fit(dist, 50, 4), Color.White))
                    Text(cfg.unitLabel.let { if (it == "mi") "MILES" else "KM" }, style = label(dx, 11, gray))
                }
                Box(Modifier.width(dx.dp(2)).fillMaxHeight().background(LINE))
                Column(Modifier.weight(1f).padding(vertical = dx.dp(6)), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(avg, style = number(dx, fit(avg, 50, 4), Color.White))
                    Text("AVG /${cfg.unitLabel.uppercase()}", style = label(dx, 11, gray))
                }
            }
            Divider(dx)
        }
        Spacer(Modifier.height(dx.dp(10)))
        // The run is already saved and syncing; the watch deletes it after 30 days once uploaded.
        Box(
            Modifier.size(dx.dp(200), dx.dp(72)).clip(RoundedCornerShape(dx.dp(10))).background(ACCENT).clickable(onClick = done),
            contentAlignment = Alignment.Center,
        ) { Text("DONE", style = label(dx, 20, Color.Black, spacing = 0.1f)) }
    }
}

@Composable
private fun Center(title: String, sub: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 22.sp, color = Color.White)
        Text(sub, color = ACCENT, fontSize = 13.sp)
    }
}

private val ACCENT = Color(0xFFC8F23A)
private val LINE = Color(0xFF3A3A3A)
private val GRAY = Color(0xFF9A9A9A)
private val CHIVO = FontFamily(Font(R.font.chivo_extrabold, FontWeight.ExtraBold))
private val CHIVO_MONO = FontFamily(Font(R.font.chivo_mono_bold, FontWeight.Bold), Font(R.font.chivo_mono_medium, FontWeight.Medium))

/**
 * The design was drawn at 408 px across (a Pixel Watch 3 41 mm); [Dx] turns its px into this
 * screen's size, for layout and type alike, so text ignores the system font scale and always fits.
 */
private class Dx(private val unit: Dp, private val density: Density) {
    fun dp(px: Number): Dp = unit * px.toFloat()
    fun sp(px: Number): TextUnit = with(density) { dp(px).toSp() }
}

@Composable
private fun rememberDx(): Dx {
    val width = LocalConfiguration.current.screenWidthDp.dp
    val density = LocalDensity.current
    return remember(width, density) { Dx(width / 408f, density) }
}

/** Shrinks [px] for values longer than [chars] characters (10+ miles, 10:00+ pace, an hour+). */
private fun fit(text: String, px: Int, chars: Int): Float = px * minOf(1f, chars.toFloat() / text.length)

private val TIGHT = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

private fun number(dx: Dx, px: Number, color: Color, bold: Boolean = true) = TIGHT.copy(
    fontFamily = CHIVO_MONO, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, color = color,
    fontSize = dx.sp(px), lineHeight = dx.sp(px.toFloat() * 0.85f), letterSpacing = (-0.04).em,
)

private fun label(dx: Dx, px: Number, color: Color, spacing: Float = 0.16f) = TIGHT.copy(
    fontFamily = CHIVO, fontWeight = FontWeight.ExtraBold, color = color,
    fontSize = dx.sp(px), lineHeight = dx.sp(px.toFloat() * 1.1f), letterSpacing = spacing.em,
)

@Composable
private fun Divider(dx: Dx) = Box(Modifier.fillMaxWidth().height(dx.dp(2)).background(LINE))
