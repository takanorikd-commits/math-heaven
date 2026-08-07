package com.example.medicalschoolapp.service

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.example.medicalschoolapp.BlockActivity
import com.example.medicalschoolapp.data.LocalSettingsRepository
import com.example.medicalschoolapp.data.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class AppMonitorService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var repository: SettingsRepository
    private var monitorJob: Job? = null

    // onAccessibilityEvent(メインスレッド)と監視ループ(IOスレッド)の両方から読まれるため @Volatile にする
    @Volatile
    private var currentForegroundPackage: String? = null

    // lastCheckTimeMs は監視ループのみが所有・更新する(他スレッドから触らずレースを避ける)
    private var lastCheckTimeMs = 0L

    companion object {
        private const val TAG = "AppMonitorService"
        private const val CHANNEL_ID = "monitor_service_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_INTERVAL_MS = 1000L

        const val BLOCK_CHANNEL_ID = "block_alert_channel"
        const val BLOCK_NOTIFICATION_ID = 1002
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = LocalSettingsRepository(applicationContext)
        setupForegroundNotification()
        startPersistentMonitor()
    }

    private fun setupForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "アプリ監視サービス", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)

            // ブロック画面の強制起動用。着信/アラームと同じ仕組み(フルスクリーンインテント)を使うため
            // IMPORTANCE_HIGH が必須(でないとOSがヘッズアップ/フルスクリーン表示を許可しない)
            val blockChannel = NotificationChannel(
                BLOCK_CHANNEL_ID, "利用制限アラート", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "遊びスマホ時間切れ・勉強時間になった際に画面をロックします"
                setBypassDnd(true)
            }
            manager.createNotificationChannel(blockChannel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("医学部合格アプリ：稼働中")
            .setContentText("利用時間を秒単位で計測しています")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        currentForegroundPackage = pkg
    }

    private fun startPersistentMonitor() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            lastCheckTimeMs = System.currentTimeMillis()
            while (isActive) {
                val now = System.currentTimeMillis()
                // 端末スリープ等で間隔が異常に開いた場合に一気に加算されないようクランプする
                val elapsed = (now - lastCheckTimeMs).coerceIn(0L, TICK_INTERVAL_MS * 3)
                lastCheckTimeMs = now

                val foreground = (rootInActiveWindow?.packageName?.toString() ?: currentForegroundPackage ?: "").trim()

                if (foreground.isNotEmpty() && !isSystemWhitelisted(foreground)) {
                    val isPlay = repository.isAppPlayCategory(foreground)
                    if (isPlay && elapsed > 0) {
                        // UsageStatsManagerに頼らず、検知している間だけ実時間をそのままDataStoreに反映する
                        repository.addUsageMs(elapsed)
                    }

                    if (!repository.isRecentlyUnlocked()) {
                        checkAndBlock(foreground, isPlay)
                    }
                }

                // ブロック画面が最前面になっている間は上のブロックが丸ごとスキップされる
                // (自アプリはホワイトリスト済みのため)。その間に裏のPiP小窓で再生ボタンを
                // 押されても検知できるよう、フォアグラウンド判定とは別に毎tick確認する。
                suppressLingeringPip()

                delay(TICK_INTERVAL_MS)
            }
        }
    }

    private fun isSystemWhitelisted(pkg: String): Boolean = 
        pkg == applicationContext.packageName || pkg == "com.android.systemui" || LocalSettingsRepository.defaultAllowedPackages.contains(pkg)

    private suspend fun checkAndBlock(packageName: String, isPlay: Boolean) {
        // アンインストール防止 (設定画面等)
        if (packageName == "com.android.settings" || packageName.contains("packageinstaller")) {
            checkAndBlockUninstallAttempt()
            return
        }

        // 勉強時間・疑似制限
        if ((repository.isStudyTimeNow() || repository.isPseudoRestrictionFlow.first()) && isPlay) {
            findPipWindow()?.let { dismissPip(it) }
            launchBlockActivity()
            return
        }

        // 時間切れ判定（MainActivity/ウィジェットと同じ repository.calculateRemainingMs() を使い、ズレをなくす）
        if (isPlay && repository.calculateRemainingMs() < 60000L) {
            findPipWindow()?.let { dismissPip(it) }
            launchBlockActivity()
        }
    }

    // ブロック画面が最前面でも、裏でPiP小窓の「遊びアプリ」が生きていて再生ボタンを押される
    // ケースをここで捕捉する。checkAndBlockはフォアグラウンド判定に依存するため、
    // 自アプリ(ホワイトリスト済み)が最前面の間は呼ばれない -> このチェックで補う。
    private suspend fun suppressLingeringPip() {
        val pipWindow = findPipWindow() ?: return
        val pipPackage = pipWindow.root?.packageName?.toString() ?: return
        if (isSystemWhitelisted(pipPackage) || repository.isRecentlyUnlocked()) return
        if (!repository.isAppPlayCategory(pipPackage)) return

        val shouldBlock = repository.isStudyTimeNow() ||
            repository.isPseudoRestrictionFlow.first() ||
            repository.calculateRemainingMs() < 60000L
        if (!shouldBlock) return

        Log.d(TAG, "lingering PiP detected for $pipPackage while blocked, re-suppressing")
        dismissPip(pipWindow)
        launchBlockActivity()
    }

    private fun findPipWindow(): android.view.accessibility.AccessibilityWindowInfo? = try {
        windows?.firstOrNull { it.isInPictureInPictureMode }
    } catch (e: Exception) {
        Log.w(TAG, "failed to query windows for PiP detection", e)
        null
    }

    // YouTube等のPiP(小窓)はAndroidのシステムUIが描画する標準windowで、通常のActivityでは覆い隠せない。
    // 「PiP小窓を画面下端までドラッグすると閉じる」という標準ジェスチャーを自動実行し、確実に閉じる。
    private fun dismissPip(pipWindow: android.view.accessibility.AccessibilityWindowInfo) {
        val bounds = android.graphics.Rect()
        pipWindow.getBoundsInScreen(bounds)
        val metrics = resources.displayMetrics
        val path = android.graphics.Path().apply {
            moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            lineTo(metrics.widthPixels / 2f, metrics.heightPixels.toFloat() - 4f)
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 400))
            .build()
        val dispatched = dispatchGesture(gesture, null, null)
        Log.d(TAG, "PiP dismiss gesture dispatched=$dispatched bounds=$bounds")
    }

    private fun checkAndBlockUninstallAttempt() {
        val root = rootInActiveWindow ?: return
        val appName = getString(com.example.medicalschoolapp.R.string.app_name)
        val dangerous = listOf("アンインストール", "削除", "無効", "停止", "解除", "Uninstall", "Delete", "Disable", "Force stop")
        var isTarget = root.findAccessibilityNodeInfosByText(appName).isNotEmpty()
        if (!isTarget) { for (k in dangerous) { if (root.findAccessibilityNodeInfosByText(k).isNotEmpty()) { isTarget = true; break } } }
        if (isTarget) launchBlockActivity()
    }

    private fun launchBlockActivity() {
        Log.d(TAG, "launchBlockActivity() called")
        val intent = Intent(this, BlockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        // 動画視聴中などフォアグラウンドが変化しない状況では、バックグラウンドからの
        // startActivity() がOSに黙って弾かれることがあるため直接起動を試みつつ、
        // 着信/アラームと同じ「フルスクリーンインテント通知」で確実に画面を割り込ませる。
        try { startActivity(intent); Log.d(TAG, "direct startActivity() succeeded (no exception)") } catch (e: Exception) { Log.w(TAG, "direct startActivity failed", e) }

        val pendingIntent = PendingIntent.getActivity(
            this, BLOCK_NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, BLOCK_CHANNEL_ID)
            .setContentTitle("遊びスマホ時間の制限")
            .setContentText("設定した時間になりました")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(BLOCK_NOTIFICATION_ID, notification)
        Log.d(TAG, "block fullScreenIntent notification posted (areNotificationsEnabled=${manager.areNotificationsEnabled()}, channelImportance=${manager.getNotificationChannel(BLOCK_CHANNEL_ID)?.importance})")
    }

    override fun onInterrupt() {}
    override fun onDestroy() { super.onDestroy(); serviceScope.cancel() }
}
