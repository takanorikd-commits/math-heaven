package com.example.medicalschoolapp

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.medicalschoolapp.data.LocalSettingsRepository
import com.example.medicalschoolapp.ui.MainViewModel
import com.example.medicalschoolapp.ui.mainViewModelFactory
import kotlinx.coroutines.delay

class BlockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent the back gesture/button from closing this screen and
        // leaking access to the blocked app underneath.
        onBackPressedDispatcher.addCallback(this) {
            // Intentionally does nothing: the only way out is a valid temp password.
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    BlockScreen(
                        onUnlocked = {
                            Toast.makeText(this, "15分延長されました", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    )
                }
            }
        }
    }
}

private const val MAX_UNLOCK_ATTEMPTS = 5
private const val LOCKOUT_DURATION_MS = 30_000L

@Composable
fun BlockScreen(
    onUnlocked: () -> Unit
) {
    // Prevent back navigation via gesture
    androidx.activity.compose.BackHandler(enabled = true) {
        // Do nothing, block user from escaping
    }

    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel(
        factory = mainViewModelFactory(LocalSettingsRepository(context.applicationContext))
    )

    var passwordInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var failedAttempts by remember { mutableIntStateOf(0) }
    var lockedUntilMs by remember { mutableStateOf(0L) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var isParentAuthMode by remember { mutableStateOf(false) }

    val isLocked = nowMs < lockedUntilMs
    LaunchedEffect(isLocked) {
        while (isLocked) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }

    val isPseudoActive by viewModel.isPseudoRestrictionActive.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isPseudoActive) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Text(
                    text = "【疑似制限モード有効中】テスト目的のため解除ボタンを表示しています",
                    color = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Text(
            text = if (isParentAuthMode) "保護者パスワードで解除" else if (isPseudoActive) "疑似制限モード実行中" else "本日の遊びスマホ時間は終了しました",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        OutlinedTextField(
            value = passwordInput,
            onValueChange = { passwordInput = it },
            label = { Text(if (isParentAuthMode) "保護者パスワード" else "一時パスワード (6桁)") },
            visualTransformation = PasswordVisualTransformation(),
            isError = errorMessage != null,
            enabled = !isLocked,
            modifier = Modifier.fillMaxWidth()
        )

        if (errorMessage != null) {
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (isLocked) {
            val remainingSec = ((lockedUntilMs - nowMs) / 1000).coerceAtLeast(0) + 1
            Text(
                text = "試行回数が上限に達しました。${remainingSec}秒後に再試行してください。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (isParentAuthMode) {
                    viewModel.verifyParentPassword(passwordInput) { success ->
                        if (success) {
                            failedAttempts = 0
                            // 保護者パスワードの場合は、一時パスワードと同様に解除して閉じる
                            // (リポジトリ側の制限時間自体を増やす処理を挟むことも可能ですが、ここでは画面を閉じるのみ)
                            onUnlocked()
                        } else {
                            errorMessage = "保護者パスワードが違います"
                        }
                    }
                } else {
                    viewModel.useTempPassword(passwordInput) { success ->
                        if (success) {
                            failedAttempts = 0
                            onUnlocked()
                        } else {
                            failedAttempts += 1
                            if (failedAttempts >= MAX_UNLOCK_ATTEMPTS) {
                                lockedUntilMs = System.currentTimeMillis() + LOCKOUT_DURATION_MS
                                nowMs = System.currentTimeMillis()
                                failedAttempts = 0
                            }
                            errorMessage = "パスワードが無効か、使用済みです"
                        }
                    }
                }
            },
            enabled = !isLocked,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isParentAuthMode) "保護者認証で解除" else "延長する (15分)")
        }

        TextButton(onClick = { 
            isParentAuthMode = !isParentAuthMode 
            errorMessage = null
            passwordInput = ""
        }) {
            Text(if (isParentAuthMode) "一時パスワード入力に戻る" else "保護者パスワードで解除する")
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = {
                val intent = context.packageManager.getLaunchIntentForPackage("com.openai.chatgpt")
                if (intent != null) {
                    context.startActivity(intent)
                    (context as? ComponentActivity)?.finish()
                } else {
                    Toast.makeText(context, "ChatGPTがインストールされていません", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("ChatGPTを開く (学習用)")
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                (context as? ComponentActivity)?.finish()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("ホーム画面に戻る")
        }

        if (isPseudoActive) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { 
                    viewModel.togglePseudoRestriction()
                    (context as? ComponentActivity)?.finish()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("疑似制限モードを終了する")
            }
        }
    }
}
