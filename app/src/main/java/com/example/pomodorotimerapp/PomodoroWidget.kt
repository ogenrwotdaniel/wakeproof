package com.example.pomodorotimerapp

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.RemoteViews

class PomodoroWidget : AppWidgetProvider() {

    private var timerUpdateReceiver: BroadcastReceiver? = null

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, 25 * 60 * 1000L, true, 0)
        }
    }

    override fun onEnabled(context: Context) {
        // Register receiver for timer updates
        timerUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val timeLeft = intent?.getLongExtra("time_left", 0L) ?: 0L
                val isWorkPeriod = intent?.getBooleanExtra("isWorkPeriod", true) ?: true
                val sessionCount = intent?.getIntExtra("sessionCount", 0) ?: 0
                
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val thisWidget = ComponentName(context, PomodoroWidget::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                
                for (appWidgetId in appWidgetIds) {
                    updateAppWidget(context, appWidgetManager, appWidgetId, timeLeft, isWorkPeriod, sessionCount)
                }
            }
        }
        context.registerReceiver(timerUpdateReceiver, IntentFilter("POMODORO_TIMER_UPDATE"))
    }

    override fun onDisabled(context: Context) {
        // Unregister receiver
        timerUpdateReceiver?.let {
            context.unregisterReceiver(it)
            timerUpdateReceiver = null
        }
    }

    companion object {
        fun updateAppWidget(
            context: Context, 
            appWidgetManager: AppWidgetManager, 
            appWidgetId: Int,
            remainingTime: Long, 
            isWorkPeriod: Boolean, 
            sessionCount: Int
        ) {
            val minutes = (remainingTime / 1000 / 60).toInt()
            val seconds = (remainingTime / 1000 % 60).toInt()
            val timeString = String.format("%02d:%02d", minutes, seconds)
            
            val periodType = if (isWorkPeriod) {
                "Work ${sessionCount + 1}/4"
            } else {
                if (sessionCount == 0) "Long Break" else "Break"
            }

            val views = RemoteViews(context.packageName, R.layout.pomodoro_widget)
            views.setTextViewText(R.id.widget_time, timeString)
            views.setTextViewText(R.id.widget_period, periodType)
            
            // Change widget background color based on session type
            val backgroundColor = if (isWorkPeriod) {
                context.resources.getColor(R.color.work_color, null)
            } else {
                context.resources.getColor(R.color.break_color, null)
            }
            views.setInt(R.id.widget_root, "setBackgroundColor", backgroundColor)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

