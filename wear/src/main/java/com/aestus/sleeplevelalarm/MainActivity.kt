package com.aestus.sleeplevelalarm

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.*
import com.aestus.sleep.core.*
import com.aestus.sleeplevelalarm.alarm.RingingService
import com.aestus.sleeplevelalarm.tracking.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormat = DateTimeFormatter.ofPattern("MM.dd HH:mm")
private fun time(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(timeFormat)
private fun date(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(dateFormat)
private fun range(session: SleepSession) = "${time(session.window.start)}–${time(session.window.end)}"

class MainActivity : ComponentActivity() {
    private val app get() = application as SleepApplication
    private val message = MutableStateFlow("")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SleepApp() }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            try {
                val now = System.currentTimeMillis()
                val active = withContext(Dispatchers.IO) { app.repository.restore(now) } ?: return@launch
                if (active.state == SessionState.RINGING || active.window.end <= now) {
                    if (now - (active.firedAt ?: active.window.end) <= 5 * MINUTE) {
                        RingingService.start(this@MainActivity, active.id, AlarmReason.RECOVERED)
                    } else withContext(Dispatchers.IO) { app.repository.finish(active.id, false, "복구 시 알람 시각 경과 · 미실행", now) }
                }
            } catch (e: Exception) { message.value = "알람 예약 확인 실패 · 정확한 알람 권한을 확인해 주세요" }
        }
    }

    private fun foregroundSensorPermissions() = arrayOf(
        if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACTIVITY_RECOGNITION)
    private fun backgroundSensorPermission() = when {
        Build.VERSION.SDK_INT >= 36 -> "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        Build.VERSION.SDK_INT >= 33 -> Manifest.permission.BODY_SENSORS_BACKGROUND
        else -> null
    }
    private fun hasSensorPermission() = foregroundSensorPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
    private fun hasBackgroundSensorPermission() = backgroundSensorPermission()?.let {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    } ?: true

    private fun track(session: SleepSession) {
        if (!hasSensorPermission()) { message.value = "센서 권한 없음 · 일반 알람으로 예약했습니다"; return }
        if (!hasBackgroundSensorPermission()) { message.value = "야간 센서 권한 없음 · 일반 알람으로 예약했습니다"; return }
        try {
            ContextCompat.startForegroundService(this, Intent(this, SleepTrackingService::class.java).putExtra("sessionId", session.id))
        } catch (e: Exception) {
            message.value = "추적 시작 실패 · 일반 알람은 유지됩니다"
            app.scope.launch { app.repository.note(session.id, message.value) }
        }
    }

    @Composable
    private fun SleepApp() {
        val settings by app.settings.settings.collectAsStateWithLifecycle(AlarmSettings())
        val records by app.repository.sessions.collectAsStateWithLifecycle(emptyList())
        val status by TrackingState.status.collectAsStateWithLifecycle()
        val error by message.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var screen by remember { mutableStateOf("home") }
        var detailId by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val active = records.firstOrNull { it.state in listOf(SessionState.TRACKING, SessionState.RINGING) }
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            message.value = if (hasSensorPermission()) "센서 권한을 허용했습니다" else "센서 권한 없음 · 일반 알람 사용 가능"
        }
        val backgroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            message.value = if (it) "야간 센서 접근을 허용했습니다" else "앱 권한 설정에서 센서의 항상 허용을 확인해 주세요"
        }
        LaunchedEffect(Unit) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(TrackingPowerPolicy.IDLE_PROCESSING_MS)
                }
            }
        }
        BackHandler(screen != "home") { screen = "home" }
        fun save(value: AlarmSettings) { scope.launch { try { app.settings.save(value) } catch (e: Exception) { message.value = "설정을 저장하지 못했습니다" } } }
        val colors = Colors(primary = Color(0xFF48345D), primaryVariant = Color(0xFF6B527E),
            onPrimary = Color.White, secondary = Color(0xFFCBCBCB), onSecondary = Color.Black,
            background = Color(0xFF8BA9D6), onBackground = Color(0xFF251D32), surface = Color(0xFF1A1A1A),
            onSurface = Color.White, onSurfaceVariant = Color(0xFFCACACA), error = Color(0xFF8C1232), onError = Color.White)
        MaterialTheme(colors = colors) {
            Scaffold(modifier = Modifier.fillMaxSize().paint(painterResource(R.drawable.elegant_background), contentScale = ContentScale.FillBounds),
                timeText = { TimeText(timeTextStyle = TimeTextDefaults.timeTextStyle().copy(color = colors.onBackground)) }) {
                if (active?.state == SessionState.RINGING) {
                    WatchList("일어날 시간이에요") {
                        item { Text(time(active.firedAt ?: now), style = MaterialTheme.typography.display1, color = colors.onBackground) }
                        item { Body(if (active.reason == AlarmReason.SMART) "스마트 기상 · 실험 추정" else "예약 기상 알람") }
                        item { Action("알람 종료", primary = true) {
                            startService(Intent(this@MainActivity, RingingService::class.java).setAction(RingingService.STOP).putExtra("sessionId", active.id))
                        } }
                    }
                } else when (screen) {
                    "time" -> TimeEditor(settings.hour, settings.minute, { hour, minute -> save(settings.copy(hour = hour, minute = minute)); screen = "home" }, { screen = "home" })
                    "history" -> WatchList("수면 기록") {
                        if (records.isEmpty()) item { Body("첫 수면을 기록해 보세요.\n기록은 워치에만 저장됩니다.") }
                        items(records, key = { it.id }) { session ->
                            Action(date(session.startedAt), when (session.state) {
                                SessionState.TRACKING -> "진행 중 · ${range(session)}"
                                SessionState.RINGING -> "알람 울리는 중"
                                SessionState.CANCELLED -> "사용자 취소"
                                SessionState.FAILED -> "예약 실패"
                                SessionState.COMPLETED -> session.firedAt?.let { "기상 ${time(it)}" } ?: "미실행"
                            }) { detailId = session.id; screen = "detail" }
                        }
                        item { Action("돌아가기") { screen = "home" } }
                    }
                    "detail" -> WatchList("수면 기록 상세") {
                        val session = records.find { it.id == detailId }
                        if (session != null) {
                            item { Body("시작 ${date(session.startedAt)}\n기상 범위 ${range(session)}") }
                            item { Body("수면 진입 ${session.onset?.let(::time) ?: "감지 안 됨 · 임시값 사용"}") }
                            item { Body("알람 ${session.firedAt?.let(::date) ?: "실행 기록 없음"}\n${when (session.reason) { AlarmReason.SMART -> "스마트 기상"; AlarmReason.FINAL -> "최종 알람"; AlarmReason.RECOVERED -> "복구된 알람"; null -> "" }}") }
                            if (session.batteryStart >= 0) item { Body("배터리 ${session.batteryStart}% → ${session.batteryEnd.takeIf { it >= 0 }?.let { "$it%" } ?: "진행 중"}") }
                            if (session.note.isNotBlank()) item { Body(session.note) }
                            item { Body("실험 알고리즘\n${session.algorithmVersion}") }
                        }
                        item { Action("기록 목록") { screen = "history" } }
                    }
                    else -> WatchList("수면 알람") {
                        if (active != null) {
                            item { Text(range(active), fontSize = 25.sp, textAlign = TextAlign.Center, color = colors.onBackground) }
                            item { Body(if (app.scheduler.canSchedule()) "최종 알람 ${time(active.window.end)}\n${if (active.settings.mode == AlarmMode.CYCLES) "${active.settings.cycles}회 사이클" else "기상 마감 시각"}" else "알람 권한 없음 · 예약 확인 필요", warning = !app.scheduler.canSchedule()) }
                            item { Body(when {
                                !status.running && !hasSensorPermission() -> "일반 알람 예약됨\n센서 권한이 필요합니다"
                                !status.running && !hasBackgroundSensorPermission() -> "일반 알람 예약됨\n야간 센서 권한이 필요합니다"
                                else -> status.message
                            }) }
                            val estimate = status.estimate
                            if (estimate != null && now - estimate.at <= MINUTE) item {
                                val stage = when (status.sleepStage) {
                                    SleepStage.WAKE -> "각성"
                                    SleepStage.N1 -> "얕은 잠 · N1"
                                    SleepStage.N2 -> "얕은 잠 · N2"
                                    SleepStage.N3 -> "깊은 잠 · N3"
                                    SleepStage.REM -> "REM 수면"
                                    null -> null
                                }
                                Body("${stage ?: if (estimate.quality != DataQuality.GOOD) "추정 불가" else when (estimate.state) {
                                    SleepState.ASLEEP_CANDIDATE -> "수면 진입 후보"
                                    SleepState.AWAKE -> "깨어 있음 후보"
                                    SleepState.UNKNOWN -> "추정 불가"
                                }}\n심박 ${status.heartRate?.toInt()?.let { "$it bpm" } ?: "측정 중"}")
                            }
                            if (!status.running && hasSensorPermission() && hasBackgroundSensorPermission()) item { Action("센서 추적 다시 시작") { track(active) } }
                            item { Action("수면 추적 종료") {
                                if (!busy) scope.launch {
                                    busy = true
                                    try {
                                        withContext(Dispatchers.IO) { app.repository.finish(active.id, true, "사용자가 추적 종료", System.currentTimeMillis()) }
                                        stopService(Intent(this@MainActivity, SleepTrackingService::class.java))
                                        stopService(Intent(this@MainActivity, RingingService::class.java))
                                    } catch (e: Exception) { message.value = "종료에 실패했습니다. 다시 시도해 주세요." }
                                    finally { busy = false }
                                }
                            } }
                            if (active.note.isNotBlank()) item { Body(active.note) }
                        } else {
                            if (settings.mode == AlarmMode.DEADLINE) {
                                item { Action("%02d:%02d".format(settings.hour, settings.minute), "직전 30분 안에 스마트 기상") { screen = "time" } }
                            } else {
                                item { Body("${settings.cycles}회 · 약 ${settings.cycles * 90 / 60}시간 ${settings.cycles * 90 % 60}분\n예상 기상 전후 15분") }
                                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { save(settings.copy(cycles = settings.cycles - 1)) }, enabled = settings.cycles > 1, modifier = Modifier.size(52.dp)) { Text("감소", fontSize = 13.sp) }
                                    Button(onClick = { save(settings.copy(cycles = settings.cycles + 1)) }, enabled = settings.cycles < 6, modifier = Modifier.size(52.dp)) { Text("증가", fontSize = 13.sp) }
                                } }
                            }
                            item { Action(if (busy) "예약 중…" else "수면 시작", primary = true, enabled = !busy) {
                                if (!busy) scope.launch {
                                    busy = true; message.value = ""
                                    try {
                                        val session = withContext(Dispatchers.IO) { app.repository.start(settings, System.currentTimeMillis()) }
                                        track(session)
                                    } catch (e: Exception) { message.value = e.message ?: "알람을 예약하지 못했습니다" }
                                    finally { busy = false }
                                }
                            } }
                            item { Action(if (settings.mode == AlarmMode.DEADLINE) "기상 마감 시각" else "사이클 횟수",
                                "눌러서 방식 변경") { save(settings.copy(mode = if (settings.mode == AlarmMode.DEADLINE) AlarmMode.CYCLES else AlarmMode.DEADLINE)) } }
                            item { ToggleChip(checked = settings.sound, onCheckedChange = { save(settings.copy(sound = it)) },
                                label = { Text("알람 소리", maxLines = 1) }, secondaryLabel = { Text(if (settings.sound) "진동과 소리" else "진동만") },
                                toggleControl = { Switch(checked = settings.sound) }, modifier = Modifier.fillMaxWidth()) }
                        }
                        if (!hasSensorPermission()) item { Action("센서 권한 허용", "허용 전에는 일반 알람으로 동작") {
                            val required = foregroundSensorPermissions().toMutableList()
                            if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.POST_NOTIFICATIONS)
                            permissions.launch(required.toTypedArray())
                        } }
                        if (hasSensorPermission() && !hasBackgroundSensorPermission()) item { Action("야간 센서 접근 허용", "화면이 꺼진 동안 수면 추적에 필요") {
                            backgroundSensorPermission()?.let { backgroundPermission.launch(it) }
                        } }
                        if (hasSensorPermission() && !hasBackgroundSensorPermission()) item { Action("센서 접근 설정 열기") {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        } }
                        if (!app.scheduler.canSchedule()) item { Action("정확한 알람 권한 설정") {
                            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                        } }
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            item { Action("알람 알림 허용") { permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) } }
                        }
                        if (Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
                            item { Action("알람 전체 화면 허용") {
                                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                            } }
                        }
                        if (error.isNotEmpty()) item { Body(error, warning = true) }
                        item { Action("수면 기록") { screen = "history" } }
                        item { Body("실험용 기상 추정\n90분은 고정된 수면 주기가 아닙니다.\n워치 전원이 꺼지면 울릴 수 없습니다.") }
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchList(title: String, content: androidx.wear.compose.foundation.lazy.ScalingLazyListScope.() -> Unit) {
    ScalingLazyColumn(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
        autoCentering = null,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(title, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center, color = MaterialTheme.colors.onBackground) }
        content()
    }
}

@Composable
private fun Body(text: String, warning: Boolean = false) {
    Text(text, style = MaterialTheme.typography.body2, color = if (warning) MaterialTheme.colors.error else MaterialTheme.colors.onBackground,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
}

@Composable
private fun Action(label: String, secondary: String? = null, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Chip(onClick = onClick, label = { Text(label) },
        secondaryLabel = secondary?.let { value -> { Text(value) } }, enabled = enabled,
        colors = if (primary) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp))
}

@Composable
private fun TimeEditor(hour: Int, minute: Int, save: (Int, Int) -> Unit, back: () -> Unit) {
    val hours = rememberPickerState(24, hour)
    val minutes = rememberPickerState(60, minute)
    WatchList("기상 마감 시각") {
        item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Picker(state = hours, contentDescription = "시", gradientColor = Color.Transparent, modifier = Modifier.width(58.dp).height(100.dp)) { Text("%02d".format(it), fontSize = 28.sp, color = MaterialTheme.colors.onBackground) }
            Text(":", fontSize = 28.sp, color = MaterialTheme.colors.onBackground)
            Picker(state = minutes, contentDescription = "분", gradientColor = Color.Transparent, modifier = Modifier.width(58.dp).height(100.dp)) { Text("%02d".format(it), fontSize = 28.sp, color = MaterialTheme.colors.onBackground) }
        } }
        item { Action("저장", primary = true) { save(hours.selectedOption, minutes.selectedOption) } }
        item { Action("취소", onClick = back) }
    }
}
