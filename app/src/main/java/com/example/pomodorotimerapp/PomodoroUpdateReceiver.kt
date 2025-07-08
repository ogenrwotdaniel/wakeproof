package com.example.pomodorotimerapp

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class PomodoroUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "POMODORO_TIMER_UPDATE") {
            val remainingTime = intent.getLongExtra("time_left", 0)
            val isWorkPeriod = intent.getBooleanExtra("isWorkPeriod", true)
            val sessionCount = intent.getIntExtra("sessionCount", 0)

            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, PomodoroWidget::class.java)
            )

            for (appWidgetId in appWidgetIds) {
                PomodoroWidget.updateAppWidget(
                    context, appWidgetManager, appWidgetId, remainingTime, isWorkPeriod, sessionCount
                )
            }
        }
    }
}

