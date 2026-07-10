package com.example.medicalschoolapp.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.medicalschoolapp.data.LocalSettingsRepository
import com.example.medicalschoolapp.util.TimeCalculator
import kotlinx.coroutines.flow.first

class RemainingTimeWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = LocalSettingsRepository(context.applicationContext)
        val startDateMs = repository.startDateFlow.first()
        val stats = repository.dailyUsageStatsFlow.first()
        val usedTimeMs = repository.getTodayPlayUsageMs()
        val extendedTimeMins = stats.second
        val now = System.currentTimeMillis()

        val remainingMs = TimeCalculator.getRemainingTimeTodayMs(
            startDateMs, now, usedTimeMs, extendedTimeMins
        )
        val remainingMins = (remainingMs / 60000).coerceAtLeast(0)
        
        val countdown = TimeCalculator.getCountdownToCommonTest()

        provideContent {
            Column(
                modifier = GlanceModifier.fillMaxSize()
                    .padding(8.dp)
                    .background(Color.White),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "遊びスマホ残り",
                    style = TextStyle(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "$remainingMins 分",
                    style = TextStyle(fontWeight = FontWeight.Bold)
                )
                
                Spacer(modifier = GlanceModifier.height(8.dp))
                
                Text(
                    text = "共通テストまで",
                    style = TextStyle(fontWeight = FontWeight.Bold)
                )
                Text(text = countdown)
            }
        }
    }
}
