package com.jameslongo.truerun.ui

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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import com.jameslongo.truerun.Config
import com.jameslongo.truerun.ExerciseService
import com.jameslongo.truerun.MainActivity
import com.jameslongo.truerun.RunRepo
import com.jameslongo.truerun.RunState
import com.jameslongo.truerun.Status
import com.jameslongo.truerun.field
import com.jameslongo.truerun.fmtDist
import com.jameslongo.truerun.fmtDur
import com.jameslongo.truerun.fmtPace
import java.io.File

private val ORANGE = Color(0xFFFC4C02)

private enum class Menu { HOME, WORKOUTS, RACES, RUNS }

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

    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (s.status) {
                Status.STARTING, Status.ACTIVE, Status.PAUSED, Status.AUTO_PAUSED ->
                    RunScreen(act, s, cfg, act.ambient.value)
                Status.ENDED -> Summary(s, cfg) { RunRepo.state.value = RunState(); cfg = Config.load(act) }
                Status.IDLE -> {
                    BackHandler(menu != Menu.HOME) { menu = Menu.HOME }
                    fun start(workout: String? = null, race: String? = null) {
                        if (missing.isNotEmpty()) { ask.launch(missing); return }
                        ExerciseService.send(act, ExerciseService.ACTION_START) {
                            workout?.let { putExtra(ExerciseService.EXTRA_WORKOUT, it) }
                            race?.let { putExtra(ExerciseService.EXTRA_RACE, it) }
                        }
                    }
                    when (menu) {
                        Menu.HOME -> ChipList("TrueRun", buildList {
                            add("Run" to { start() })
                            if (cfg.workouts.isNotEmpty()) add("Workout ›" to { menu = Menu.WORKOUTS })
                            if (cfg.races.isNotEmpty()) add("Race ›" to { menu = Menu.RACES })
                            add("Runs & sync ›" to { menu = Menu.RUNS })
                            if (missing.isNotEmpty()) add("Grant permissions" to { ask.launch(missing) })
                        })
                        Menu.WORKOUTS -> ChipList("Workouts", cfg.workouts.map { w -> w.name to { start(workout = w.name) } })
                        Menu.RACES -> ChipList("Races", cfg.races.map { r ->
                            "${r.name} · ${fmtDur((r.targetSec * 1000).toLong())}" to { start(race = r.name) }
                        })
                        Menu.RUNS -> Runs(act)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipList(title: String, items: List<Pair<String, () -> Unit>>) {
    ScalingLazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        item { Text(title, color = ORANGE, fontWeight = FontWeight.Bold) }
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
    if (s.status == Status.STARTING) {
        BackHandler { ExerciseService.send(act, ExerciseService.ACTION_CANCEL) }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.mode, color = ORANGE, fontSize = 14.sp)
            Text(if (s.gpsReady) "GPS ready" else "Finding GPS…", color = if (s.gpsReady) Color(0xFF81C784) else Color.Gray, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { ExerciseService.send(act, ExerciseService.ACTION_GO) },
                modifier = Modifier.size(84.dp), colors = ButtonDefaults.primaryButtonColors(backgroundColor = ORANGE),
            ) { Text("START", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            Text("swipe back to cancel", color = Color.Gray, fontSize = 10.sp)
        }
        return
    }
    val extra = if (s.step != null || s.race != null) 1 else 0
    val pages = cfg.screens.size + extra + 1
    val pager = rememberPagerState(pageCount = { pages })
    HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
        when {
            ambient -> DataPage(cfg.screens.first(), s, cfg, dim = true)
            page < cfg.screens.size -> DataPage(cfg.screens[page], s, cfg, dim = false)
            page == cfg.screens.size && extra == 1 -> if (s.step != null) StepPage(s, cfg) else RacePage(s, cfg)
            else -> Controls(act, s)
        }
    }
}

@Composable
private fun DataPage(keys: List<String>, s: RunState, cfg: Config, dim: Boolean) {
    val fg = if (dim) Color.Gray else Color.White
    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (s.status != Status.ACTIVE) Text(if (s.status == Status.AUTO_PAUSED) "AUTO-PAUSED" else "PAUSED", color = ORANGE, fontSize = 12.sp)
        keys.firstOrNull()?.let { k ->
            val (label, value) = field(k, s, cfg)
            Text(label, color = Color.Gray, fontSize = 11.sp)
            Text(value, color = fg, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        }
        keys.drop(1).forEach { k ->
            val (label, value) = field(k, s, cfg)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Color.Gray, fontSize = 11.sp)
                Text(value, color = fg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
        }
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
        Text("${s.stepNo + 1}/${s.stepCount} ${step.name}", color = ORANGE, fontSize = 13.sp, textAlign = TextAlign.Center)
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
        Text(r.name, color = ORANGE, fontSize = 13.sp)
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
private fun Controls(act: MainActivity, s: RunState) {
    var confirmEnd by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val paused = s.status != Status.ACTIVE
            Button(
                onClick = { ExerciseService.send(act, if (paused) ExerciseService.ACTION_RESUME else ExerciseService.ACTION_PAUSE) },
                modifier = Modifier.size(64.dp), colors = ButtonDefaults.primaryButtonColors(backgroundColor = ORANGE),
            ) { Text(if (paused) "GO" else "II", fontSize = 20.sp) }
            Button(onClick = { ExerciseService.send(act, ExerciseService.ACTION_LAP) }, modifier = Modifier.size(64.dp)) {
                Text("LAP", fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        // Two taps to end, so a stray touch mid-race can't stop the recording.
        Button(
            onClick = { if (confirmEnd == 1) ExerciseService.send(act, ExerciseService.ACTION_END) else confirmEnd = 1 },
            modifier = Modifier.size(width = 110.dp, height = 44.dp),
            colors = ButtonDefaults.secondaryButtonColors(),
        ) { Text(if (confirmEnd == 1) "Tap to end" else "End", color = if (confirmEnd == 1) ORANGE else Color.White) }
    }
}

@Composable
private fun Summary(s: RunState, cfg: Config, done: () -> Unit) {
    val u = cfg.metersPerUnit
    ScalingLazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        item { Text(s.mode, color = ORANGE, fontWeight = FontWeight.Bold) }
        item { Text(fmtDur(s.activeMs), fontSize = 30.sp, fontWeight = FontWeight.Bold) }
        item { Text("${fmtDist(s.meters, u)} ${cfg.unitLabel} · ${fmtPace(if (s.meters > 10) s.activeMs / 1000.0 / s.meters else null, u)}/${cfg.unitLabel}") }
        item { Text("Saved; uploading when online", color = Color.Gray, fontSize = 11.sp) }
        item { Chip(label = { Text("Done") }, onClick = done, colors = ChipDefaults.primaryChipColors(backgroundColor = ORANGE)) }
    }
}

@Composable
private fun Runs(act: MainActivity) {
    val dir = File(act.filesDir, "runs")
    val runs = dir.listFiles { f -> f.name.endsWith(".tcx") }.orEmpty().sortedByDescending { it.name }.take(20)
    val log = File(act.filesDir, "sync.log").takeIf { it.exists() }?.readLines()?.lastOrNull()
    ScalingLazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        item { Text("Runs", color = ORANGE, fontWeight = FontWeight.Bold) }
        item {
            Chip(label = { Text("Sync now") }, onClick = { com.jameslongo.truerun.SyncWorker.enqueue(act) },
                colors = ChipDefaults.secondaryChipColors())
        }
        items(runs) { f ->
            val base = f.path.removeSuffix(".tcx")
            val st = (if (File("$base.strava").exists()) "Strava ✓ " else "Strava … ") +
                (if (File("$base.google").exists()) "Google ✓" else "Google …")
            Column(Modifier.fillMaxWidth().padding(4.dp)) {
                Text(f.name.take(15).replace("T", " "), fontSize = 12.sp)
                Text(st, color = Color.Gray, fontSize = 11.sp)
            }
        }
        log?.let { item { Text(it.takeLast(120), color = Color(0xFFE57373), fontSize = 10.sp) } }
    }
}

@Composable
private fun Center(title: String, sub: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 22.sp, color = Color.White)
        Text(sub, color = ORANGE, fontSize = 13.sp)
    }
}
