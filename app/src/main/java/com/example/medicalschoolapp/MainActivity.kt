package com.example.medicalschoolapp

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.example.medicalschoolapp.data.LocalSettingsRepository
import com.example.medicalschoolapp.ui.MainViewModel
import com.example.medicalschoolapp.ui.mainViewModelFactory
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavigation()
                }
            }
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

    // Periodically refresh the countdown and re-query live usage time so the
    // screen doesn't keep showing a stale cached value (e.g. right after midnight).
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.updateCountdown()
            viewModel.refreshRemainingTime()
            delay(30000)
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
        
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) {
            Text("ユーザー補助権限を設定")
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
                onValueChange = { inputPassword = it },
                label = { Text("新しいパスワード") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            )
            Button(
                onClick = {
                    if (inputPassword.isNotBlank()) {
                        viewModel.setParentPassword(inputPassword)
                        onAuthenticated()
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
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("アプリ制限") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("一時パスワード") })
        }

        when (selectedTab) {
            0 -> AppCategoryList(viewModel)
            1 -> TempPasswordManager(viewModel)
        }
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
