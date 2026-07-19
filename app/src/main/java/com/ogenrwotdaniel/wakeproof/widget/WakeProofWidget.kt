package com.ogenrwotdaniel.wakeproof.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.provideContent
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ogenrwotdaniel.wakeproof.MainActivity
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.StatsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

/**
 * Home-screen widget: streak + today's focus vs goal, tap to open the app.
 * Data is pulled through a Hilt entry point because Glance instantiates the
 * widget outside the DI graph.
 */
class WakeProofWidget : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun statsRepository(): StatsRepository
        fun settingsRepository(): SettingsRepository
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext, WidgetEntryPoint::class.java
        )
        val today = entryPoint.statsRepository().observeToday().first()
        val streak = entryPoint.statsRepository().observeStreak().first()
        val goal = entryPoint.settingsRepository().settings.first().dailyFocusGoalMinutes

        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .padding(12.dp)
                        .clickable(actionStartActivity<MainActivity>())
                ) {
                    Text(
                        text = "🔥 ${streak.days}-day streak",
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = GlanceTheme.colors.onSurface
                        )
                    )
                    Spacer(GlanceModifier.height(6.dp))
                    Text(
                        text = "Focus ${today.focusMinutes}/$goal min" +
                            if (today.focusMinutes >= goal) " ✓" else "",
                        style = TextStyle(
                            fontSize = 13.sp,
                            color = GlanceTheme.colors.onSurfaceVariant
                        )
                    )
                    Spacer(GlanceModifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = (today.focusMinutes.toFloat() / goal).coerceIn(0f, 1f),
                        modifier = GlanceModifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/** Manifest-registered receiver that hosts [WakeProofWidget]. */
class WakeProofWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WakeProofWidget()
}
