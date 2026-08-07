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
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

class BlockActivity : ComponentActivity() {
    private var audioFocusRequest: AudioFocusRequest? = null
    private val pauseRetryHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 他のPiP（小窓）を追い出し、この画面を最前面に固定
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        // フルスクリーンインテント経由で強制起動された場合、裏に残る常駐通知を消す
        (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .cancel(com.example.medicalschoolapp.service.AppMonitorService.BLOCK_NOTIFICATION_ID)

        // 2. メディア再生を強制停止。PiP（小窓）はOSの仕様上どのActivityでも覆い隠せないため、
        //    「動き続ける」こと自体を止めるにはオーディオフォーカスを奪うのが最も確実。
        //    YouTube側がPiPへの遷移アニメーション中に再生を再開することがあるため、数回リトライする。
        stealAudioFocusAndPause()

        onBackPressedDispatcher.addCallback(this) {}

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.errorContainer) {
                    BlockScreen(onUnlocked = {
                        Toast.makeText(this, "延長されました", Toast.LENGTH_SHORT).show()
                        finish()
                    })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 画面が戻ってきた際にも再度メディア停止（しつこく止める）
        stealAudioFocusAndPause()
    }

    private fun stealAudioFocusAndPause() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // オーディオフォーカスを強制的に奪う。ほとんどのメディアアプリはフォーカスを失うと
        // 自動的に一時停止するため、YouTube等の動画を止める最も確実な方法。
        // 子供がPiP小窓の再生ボタンを押すとYouTube側が再度フォーカスを要求して奪い返してくるため、
        // このActivityが表示されている間はフォーカスを失った瞬間に即座に奪い返し続ける(綱引き)。
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { focusChange ->
                if (!isFinishing && !isDestroyed &&
                    (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                ) {
                    stealAudioFocusAndPause()
                }
            }
            .build()
        audioManager.abandonAudioFocusRequest(audioFocusRequest ?: request)
        audioManager.requestAudioFocus(request)
        audioFocusRequest = request

        // フォーカス喪失を無視するアプリ向けの保険として、メディアキーでのポーズも複数回リトライする
        pauseRetryHandler.removeCallbacksAndMessages(null)
        for (attempt in 0..4) {
            pauseRetryHandler.postDelayed({
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            }, attempt * 400L)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pauseRetryHandler.removeCallbacksAndMessages(null)
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }
}

@Composable
fun BlockScreen(onUnlocked: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = true) {}
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel(factory = mainViewModelFactory(LocalSettingsRepository(context.applicationContext)))
    var passwordInput by remember { mutableStateOf("") }; var errorMessage by remember { mutableStateOf<String?>(null) }
    var failedAttempts by remember { mutableIntStateOf(0) }; var lockedUntilMs by remember { mutableStateOf(0L) }; var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var isParentAuthMode by remember { mutableStateOf(false) }
    val isLocked = nowMs < lockedUntilMs
    LaunchedEffect(isLocked) { while (isLocked) { delay(1000); nowMs = System.currentTimeMillis() } }
    val isPseudoActive by viewModel.isPseudoRestrictionActive.collectAsState()
    val remainingTimeMs by viewModel.remainingTimeMs.collectAsState()
    val remainingMinutes = (remainingTimeMs / 60000).coerceAtLeast(0)

    Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (isPseudoActive) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(text = "【疑似制限モード有効中】", color = MaterialTheme.colorScheme.onError, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        val reasonText = if (isParentAuthMode) "保護者パスワードで解除" else if (isPseudoActive) "疑似制限モード実行中" else if (remainingMinutes <= 0) "本日の遊びスマホ時間は終了しました" else "現在は【勉強時間】に指定されています"
        Text(text = reasonText, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(bottom = 32.dp))
        OutlinedTextField(value = passwordInput, onValueChange = { passwordInput = it }, label = { Text(if (isParentAuthMode) "保護者パスワード" else "一時パスワード (6桁)") }, visualTransformation = PasswordVisualTransformation(), isError = errorMessage != null, enabled = !isLocked, modifier = Modifier.fillMaxWidth())
        if (errorMessage != null) { Text(text = errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        if (isLocked) { val remainingSec = ((lockedUntilMs - nowMs) / 1000).coerceAtLeast(0) + 1; Text(text = "試行回数が上限に達しました。${remainingSec}秒後に再試行してください。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = {
            if (isParentAuthMode) {
                viewModel.verifyParentPassword(passwordInput) { success ->
                    if (success) { viewModel.addExtensionTime(60); onUnlocked() } else { errorMessage = "保護者パスワードが違います" }
                }
            } else {
                viewModel.useTempPassword(passwordInput) { success ->
                    if (success) { onUnlocked() } else {
                        failedAttempts += 1
                        if (failedAttempts >= 5) { lockedUntilMs = System.currentTimeMillis() + 30000L; nowMs = System.currentTimeMillis(); failedAttempts = 0 }
                        errorMessage = "パスワードが無効か、使用済みです"
                    }
                }
            }
        }, enabled = !isLocked, modifier = Modifier.fillMaxWidth()) { Text(if (isParentAuthMode) "保護者認証で解除 (+60分)" else "延長する (15分)") }
        TextButton(onClick = { isParentAuthMode = !isParentAuthMode; errorMessage = null; passwordInput = "" }) { Text(if (isParentAuthMode) "一時パスワード入力に戻る" else "保護者パスワードで解除する") }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(onClick = {
            val intent = context.packageManager.getLaunchIntentForPackage("com.openai.chatgpt")
            if (intent != null) { context.startActivity(intent); (context as? ComponentActivity)?.finish() } else { Toast.makeText(context, "ChatGPTが未インストールです", Toast.LENGTH_SHORT).show() }
        }, modifier = Modifier.fillMaxWidth()) { Text("ChatGPTを開く (学習用)") }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = {
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            context.startActivity(intent); (context as? ComponentActivity)?.finish()
        }, modifier = Modifier.fillMaxWidth()) { Text("ホーム画面に戻る") }
        if (isPseudoActive) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { viewModel.togglePseudoRestriction(); (context as? ComponentActivity)?.finish() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary), modifier = Modifier.fillMaxWidth()) { Text("疑似制限モードを終了する") }
        }
    }
}
