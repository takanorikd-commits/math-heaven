package com.example.medicalschoolapp

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

    val isLocked = nowMs < lockedUntilMs
    LaunchedEffect(isLocked) {
        while (isLocked) {
            delay(1000)
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
        Text(
            text = "本日の遊びスマホ時間は終了しました",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        OutlinedTextField(
            value = passwordInput,
            onValueChange = { passwordInput = it },
            label = { Text("一時パスワード (6桁)") },
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
            },
            enabled = !isLocked,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("延長する (15分)")
        }
    }
}
