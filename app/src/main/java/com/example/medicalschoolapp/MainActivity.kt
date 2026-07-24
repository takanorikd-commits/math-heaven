package com.example.medicalschoolapp

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.example.medicalschoolapp.receiver.MyAdminReceiver
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.example.medicalschoolapp.data.LocalSettingsRepository
import com.example.medicalschoolapp.service.AppMonitorService
import com.example.medicalschoolapp.ui.MainViewModel
import com.example.medicalschoolapp.ui.mainViewModelFactory
import com.example.medicalschoolapp.util.TimeCalculator
import kotlinx.coroutines.delay

import android.app.PictureInPictureParams
import android.util.Rational
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer

import com.example.medicalschoolapp.model.StudySchedule
import com.example.medicalschoolapp.model.StudyTimeRange
import java.util.Calendar
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    private var _isInPipMode = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        addOnPictureInPictureModeChangedListener { info ->
            _isInPipMode.value = info.isInPictureInPictureMode
        }

        setContent {
            val isInPipMode by _isInPipMode
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (isInPipMode) {
                        PipScreen()
                    } else {
                        AppNavigation()
                    }
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        
        val repo = LocalSettingsRepository(applicationContext)
        val isStudyTime = repo.isStudyTimeNow()
        
        val isTimeUp = runBlocking {
            try {
                val stats = repo.dailyUsageStatsFlow.first()
                val base = repo.baseTimeFlow.first()
                val start = repo.startDateFlow.first()
                val baseMins = base ?: TimeCalculator.getBaseAllowedMinutes(start, System.currentTimeMillis())
                val remainingMs: Long = (baseMins.toLong() + stats.second.toLong()) * 60000L - stats.first
                remainingMs <= 0L
            } catch (e: Exception) {
                false
            }
        }

        if (!isStudyTime && !isTimeUp) {
            enterPip()
        }
    }

    private fun enterPip() {
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(1, 1))
            .build()
        enterPictureInPictureMode(params)
    }
}

@Composable
fun PipScreen() {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel(
        factory = mainViewModelFactory(LocalSettingsRepository(context.applicationContext))
    )
    val remainingTimeMs by viewModel.remainingTimeMs.collectAsState()
    val remainingMinutes = (remainingTimeMs / 60000).coerceAtLeast(0)

    // 定期更新用
    LaunchedEffect(Unit) {
        while(true) {
            viewModel.refreshRemainingTime()
            delay(10000)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("残り", style = MaterialTheme.typography.bodySmall)
            Text(
                text = "${remainingMinutes}分",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (remainingMinutes <= 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel(
        factory = mainViewModelFactory(LocalSettingsRepository(context.applicationContext))
    )

    NavHost(navController = navController, startDestination = "dashboard") {
        composable("dashboard") {
            DashboardScreen(
                viewModel = viewModel,
                onNavigateToSettings = { navController.navigate("settings_auth") }
            )
        }
        composable("settings_auth") {
            SettingsAuthScreen(
                viewModel = viewModel,
                onAuthenticated = {
                    navController.popBackStack()
                    navController.navigate("settings")
                },
                onCancel = { navController.popBackStack() }
            )
        }
        composable("settings") {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

@Composable
fun DashboardScreen(viewModel: MainViewModel, onNavigateToSettings: () -> Unit) {
    val context = LocalContext.current
    val remainingTimeMs by viewModel.remainingTimeMs.collectAsState()
    val countdown by viewModel.commonTestCountdown.collectAsState()
    
    var isAccessibilityEnabled by remember { mutableStateOf(false) }

    // Refresh state
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.updateCountdown()
            viewModel.refreshRemainingTime()
            
            // Check accessibility status
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC)
            isAccessibilityEnabled = enabledServices.any { 
                it.resolveInfo.serviceInfo.packageName == context.packageName && 
                it.resolveInfo.serviceInfo.name == AppMonitorService::class.java.name 
            }
            
            delay(10000)
        }
    }

    val remainingMinutes = (remainingTimeMs / 60000).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("本日の遊びスマホ残り時間", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "${remainingMinutes} 分",
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = if (remainingMinutes <= 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 16.dp)
        )

        if (!isAccessibilityEnabled) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Text(
                    text = "警告: 制限機能を有効にするには「ユーザー補助権限」が必要です。下のボタンから設定してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        Divider(modifier = Modifier.padding(vertical = 24.dp))

        Text("共通テストまで", style = MaterialTheme.typography.titleMedium)
        Text(
            text = countdown,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }) {
            Text("使用状況へのアクセス権限を設定")
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onNavigateToSettings,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保護者設定")
        }
    }
}

private const val MAX_AUTH_ATTEMPTS = 5
private const val LOCKOUT_DURATION_MS = 30_000L

@Composable
fun SettingsAuthScreen(viewModel: MainViewModel, onAuthenticated: () -> Unit, onCancel: () -> Unit) {
    val isPasswordSet by viewModel.isParentPasswordSet.collectAsState()
    var inputPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var failedAttempts by remember { mutableIntStateOf(0) }
    var lockedUntilMs by remember { mutableStateOf(0L) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

    val isLocked = nowMs < lockedUntilMs
    LaunchedEffect(isLocked) {
        while (isLocked) {
            kotlinx.coroutines.delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (!isPasswordSet) {
            Text("保護者パスワードの初期設定", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = inputPassword,
                onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) inputPassword = it },
                label = { Text("新しいパスワード (4桁の数字)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            )
            Button(
                onClick = {
                    if (inputPassword.length == 4) {
                        viewModel.setParentPassword(inputPassword)
                        onAuthenticated()
                    } else {
                        errorMessage = "4桁の数字を入力してください"
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) {
                Text("設定して進む")
            }
        } else {
            Text("保護者パスワードを入力", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = inputPassword,
                onValueChange = { inputPassword = it },
                label = { Text("パスワード") },
                visualTransformation = PasswordVisualTransformation(),
                isError = errorMessage != null,
                enabled = !isLocked,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            )
            if (errorMessage != null) {
                Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
            }
            if (isLocked) {
                val remainingSec = ((lockedUntilMs - nowMs) / 1000).coerceAtLeast(0) + 1
                Text(
                    "試行回数が上限に達しました。${remainingSec}秒後に再試行してください。",
                    color = MaterialTheme.colorScheme.error
                )
            }
            Button(
                onClick = {
                    viewModel.verifyParentPassword(inputPassword) { success ->
                        if (success) {
                            failedAttempts = 0
                            onAuthenticated()
                        } else {
                            failedAttempts += 1
                            if (failedAttempts >= MAX_AUTH_ATTEMPTS) {
                                lockedUntilMs = System.currentTimeMillis() + LOCKOUT_DURATION_MS
                                nowMs = System.currentTimeMillis()
                                failedAttempts = 0
                            }
                            errorMessage = "パスワードが間違っています"
                        }
                    }
                },
                enabled = !isLocked,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) {
                Text("ロック解除")
            }
            TextButton(onClick = onCancel) {
                Text("キャンセル")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("保護者設定") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                }
            }
        )
        ScrollableTabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("アプリ制限") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("時間設定") })
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("勉強スケジュール") })
            Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("パスワード") })
            Tab(selected = selectedTab == 4, onClick = { selectedTab = 4 }, text = { Text("一時パス") })
            Tab(selected = selectedTab == 5, onClick = { selectedTab = 5 }, text = { Text("防止") })
        }

        when (selectedTab) {
            0 -> AppCategoryList(viewModel)
            1 -> TimeSettingsScreen(viewModel)
            2 -> StudyScheduleScreen(viewModel)
            3 -> PasswordSettingsScreen(viewModel)
            4 -> TempPasswordManager(viewModel)
            5 -> DeviceAdminScreen()
        }
    }
}

@Composable
fun StudyScheduleScreen(viewModel: MainViewModel) {
    val schedules by viewModel.studySchedules.collectAsState()
    val days = listOf(
        "月" to Calendar.MONDAY,
        "火" to Calendar.TUESDAY,
        "水" to Calendar.WEDNESDAY,
        "木" to Calendar.THURSDAY,
        "金" to Calendar.FRIDAY,
        "土" to Calendar.SATURDAY,
        "日" to Calendar.SUNDAY
    )

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        items(days) { (dayName, dayInt) ->
            val daySchedule = schedules.find { it.dayOfWeek == dayInt } ?: StudySchedule(dayInt)
            
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "${dayName}曜日", style = MaterialTheme.typography.titleMedium)
                    
                    daySchedule.ranges.forEachIndexed { index, range ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text("${String.format("%02d:%02d", range.startHour, range.startMinute)} - ${String.format("%02d:%02d", range.endHour, range.endMinute)}")
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                val newList = daySchedule.ranges.toMutableList().apply { removeAt(index) }
                                updateDaySchedule(viewModel, schedules, dayInt, newList)
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "削除")
                            }
                        }
                    }

                    var showTimePicker by remember { mutableStateOf(false) }
                    if (showTimePicker) {
                        // Simplified time entry for brevity in this UI
                        // In a real app, use Material TimePicker
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            var startH by remember { mutableStateOf("18") }
                            var startM by remember { mutableStateOf("00") }
                            var endH by remember { mutableStateOf("21") }
                            var endM by remember { mutableStateOf("00") }
                            
                            Column {
                                Row {
                                    TextField(value = startH, onValueChange = {startH = it}, modifier = Modifier.width(60.dp), label = {Text("開")})
                                    TextField(value = startM, onValueChange = {startM = it}, modifier = Modifier.width(60.dp))
                                }
                                Text("〜")
                                Row {
                                    TextField(value = endH, onValueChange = {endH = it}, modifier = Modifier.width(60.dp), label = {Text("終")})
                                    TextField(value = endM, onValueChange = {endM = it}, modifier = Modifier.width(60.dp))
                                }
                            }
                            
                            Button(onClick = {
                                val sH = startH.toIntOrNull() ?: 0
                                val sM = startM.toIntOrNull() ?: 0
                                val eH = endH.toIntOrNull() ?: 0
                                val eM = endM.toIntOrNull() ?: 0
                                
                                val newList = daySchedule.ranges.toMutableList().apply {
                                    add(StudyTimeRange(sH, sM, eH, eM))
                                }
                                updateDaySchedule(viewModel, schedules, dayInt, newList)
                                showTimePicker = false
                            }) {
                                Text("追加")
                            }
                        }
                    } else {
                        TextButton(onClick = { showTimePicker = true }) {
                            Icon(Icons.Default.Add, contentDescription = "追加")
                            Text("勉強時間を追加")
                        }
                    }
                }
            }
        }
    }
}

private fun updateDaySchedule(viewModel: MainViewModel, allSchedules: List<StudySchedule>, day: Int, newRanges: List<StudyTimeRange>) {
    val mutableSchedules = allSchedules.toMutableList()
    val index = mutableSchedules.indexOfFirst { it.dayOfWeek == day }
    if (index != -1) {
        mutableSchedules[index] = mutableSchedules[index].copy(ranges = newRanges)
    } else {
        mutableSchedules.add(StudySchedule(day, newRanges))
    }
    viewModel.updateStudySchedules(mutableSchedules)
}

@Composable
fun DeviceAdminScreen() {
    val context = LocalContext.current
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val adminComponent = ComponentName(context, MyAdminReceiver::class.java)
    val isAdminActive = dpm.isAdminActive(adminComponent)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("アンインストール防止 (デバイス管理者)", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "この機能を有効にすると、デバイス管理者権限が付与され、アプリのアンインストールが制限されます。" +
            "アンインストールするには、まずこの設定をオフにする必要があります。",
            style = MaterialTheme.typography.bodySmall
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = {
                if (!isAdminActive) {
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "アンインストール防止のために有効にする必要があります。")
                    }
                    context.startActivity(intent)
                } else {
                    dpm.removeActiveAdmin(adminComponent)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = if (isAdminActive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) 
                     else ButtonDefaults.buttonColors()
        ) {
            Text(if (isAdminActive) "アンインストール防止を解除" else "アンインストール防止を有効にする")
        }
    }
}

@Composable
fun PasswordSettingsScreen(viewModel: MainViewModel) {
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("保護者パスワードの変更", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = newPassword,
            onValueChange = { if (it.length <= 4 && it.all { char -> char.isDigit() }) newPassword = it },
            label = { Text("新しいパスワード (4桁の数字)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            isError = errorMessage != null
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = confirmPassword,
            onValueChange = { if (it.length <= 4 && it.all { char -> char.isDigit() }) confirmPassword = it },
            label = { Text("パスワードの確認") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            isError = errorMessage != null
        )

        if (errorMessage != null) {
            Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (successMessage != null) {
            Text(successMessage!!, color = Color(0xFF4CAF50), style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                if (newPassword.length != 4) {
                    errorMessage = "4桁の数字を入力してください"
                    successMessage = null
                } else if (newPassword != confirmPassword) {
                    errorMessage = "パスワードが一致しません"
                    successMessage = null
                } else {
                    viewModel.setParentPassword(newPassword)
                    errorMessage = null
                    successMessage = "パスワードを変更しました"
                    newPassword = ""
                    confirmPassword = ""
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            Text("パスワードを更新")
        }
    }
}

@Composable
fun TimeSettingsScreen(viewModel: MainViewModel) {
    val baseTimeMins by viewModel.baseTimeMins.collectAsState()
    val startDate by viewModel.startDate.collectAsState()
    val context = LocalContext.current

    val autoBaseMins = TimeCalculator.getBaseAllowedMinutes(startDate, System.currentTimeMillis())
    val displayBaseMins = baseTimeMins ?: autoBaseMins

    var textInputValue by remember(displayBaseMins) { mutableStateOf(displayBaseMins.toString()) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("基本の制限時間 (分)", style = MaterialTheme.typography.titleMedium)

        if (baseTimeMins == null) {
            Text(
                "現在は自動設定が有効です（毎週2.5分ずつ減少）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Text(
                "現在は手動設定が有効です（本日のみ有効）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Numerical input field
        OutlinedTextField(
            value = textInputValue,
            onValueChange = {
                if (it.isEmpty() || (it.all { char -> char.isDigit() } && it.length <= 4)) {
                    textInputValue = it
                    it.toIntOrNull()?.let { mins ->
                        viewModel.setBaseTime(mins.coerceIn(0, 1440))
                    }
                }
            },
            label = { Text("時間を直接入力 (分)") },
            suffix = { Text("分") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 16.dp)
        ) {
            Slider(
                value = displayBaseMins.toFloat().coerceIn(0f, 300f),
                onValueChange = { 
                    viewModel.setBaseTime(it.toInt())
                },
                valueRange = 0f..300f,
                steps = 59,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$displayBaseMins 分",
                modifier = Modifier.padding(start = 16.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }

        if (baseTimeMins != null) {
            Button(
                onClick = { viewModel.setBaseTime(-1) }, // -1 means reset to null/auto
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Text("自動設定（$autoBaseMins 分）に戻す")
            }
        }

        Text(
            "※ この時間を 0 に設定すると、テストとして即座にロックがかかる状態を確認できます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))

        Text("詳細設定", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            Text("ユーザー補助設定を開く")
        }
        Text(
            "※ お子様が勝手に解除した場合は、ここから再度有効にしてください。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
fun AppCategoryList(viewModel: MainViewModel) {
    val context = LocalContext.current
    val pm = context.packageManager
    val packages = remember {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { it.loadLabel(pm).toString() }
    }
    
    val appCategories by viewModel.appCategories.collectAsState()

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        items(packages) { appInfo ->
            val isPlay = appCategories[appInfo.packageName] ?: true // Default restricted
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clickable { viewModel.toggleAppCategory(appInfo.packageName, isPlay) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = appInfo.loadLabel(pm).toString(),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (isPlay) "遊び (制限対象)" else "勉強/通話 (対象外)",
                    color = if (isPlay) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Switch(
                    checked = !isPlay,
                    onCheckedChange = { viewModel.toggleAppCategory(appInfo.packageName, isPlay) }
                )
            }
        }
    }
}

@Composable
fun TempPasswordManager(viewModel: MainViewModel) {
    val passwords by viewModel.tempPasswords.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(
            onClick = { viewModel.generateTempPassword() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("一時パスワードを発行 (15分延長)")
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("有効なパスワード", style = MaterialTheme.typography.titleMedium)

        LazyColumn {
            items(passwords.filter { !it.isUsed && it.expiresAt > System.currentTimeMillis() }) { tp ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("コード: ${tp.code}", style = MaterialTheme.typography.headlineSmall)
                        Text("15分延長 (1回のみ)")
                    }
                }
            }
        }
    }
}
