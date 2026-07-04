package com.example.pomodorotimerapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import androidx.core.app.NotificationCompat

class PomodoroService : Service() {
    
    private var countDownTimer: CountDownTimer? = null
    private var remainingTime: Long = 25 * 60 * 1000L // 25 minutes in milliseconds
    private var isWorkPeriod = true
    private var sessionCount = 0
    private var isPaused = false
    private var soundEnabled = true
    private var mediaPlayer: MediaPlayer? = null
    
    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "pomodoro_channel"
        private const val WORK_DURATION = 25 * 60 * 1000L // 25 minutes
        private const val SHORT_BREAK_DURATION = 5 * 60 * 1000L // 5 minutes
        private const val LONG_BREAK_DURATION = 15 * 60 * 1000L // 15 minutes
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializeMediaPlayer()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_TIMER" -> {
                soundEnabled = intent.getBooleanExtra("soundEnabled", true)
                startTimer()
            }
            "PAUSE_TIMER" -> pauseTimer()
            "STOP_TIMER" -> stopTimer()
            "ENABLE_SOUND" -> {
                soundEnabled = true
                if (isWorkPeriod && !isPaused) {
                    startNatureSounds()
                }
            }
            "DISABLE_SOUND" -> {
                soundEnabled = false
                stopNatureSounds()
            }
        }
        return START_STICKY
    }
    
    private fun startTimer() {
        isPaused = false
        startForegroundService()
        
        countDownTimer = object : CountDownTimer(remainingTime, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                remainingTime = millisUntilFinished
                broadcastUpdate()
                updateNotification()
            }
            
            override fun onFinish() {
                onTimerComplete()
            }
        }.start()
        
        // Start nature sounds during work period
        if (isWorkPeriod && soundEnabled) {
            startNatureSounds()
        }
    }
    
    private fun pauseTimer() {
        isPaused = true
        countDownTimer?.cancel()
        stopNatureSounds()
        stopForeground(true)
    }
    
    private fun stopTimer() {
        isPaused = false
        countDownTimer?.cancel()
        stopNatureSounds()
        resetToWorkPeriod()
        broadcastUpdate()
        stopForeground(true)
        stopSelf()
    }
    
    private fun onTimerComplete() {
        stopNatureSounds()
        
        if (isWorkPeriod) {
            sessionCount++
            // After 4 work sessions, take a long break
            if (sessionCount >= 4) {
                isWorkPeriod = false
                remainingTime = LONG_BREAK_DURATION
                sessionCount = 0 // Reset session count after long break
            } else {
                // Short break
                isWorkPeriod = false
                remainingTime = SHORT_BREAK_DURATION
            }
        } else {
            // Break is over, start work period
            isWorkPeriod = true
            remainingTime = WORK_DURATION
        }
        
        broadcastUpdate()
        startTimer() // Auto-start next period
    }
    
    private fun resetToWorkPeriod() {
        isWorkPeriod = true
        remainingTime = WORK_DURATION
        sessionCount = 0
    }
    
    private fun initializeMediaPlayer() {
        // In a real implementation, you would have actual nature sound files
        // For now, we'll create a placeholder that would play nature sounds
        // mediaPlayer = MediaPlayer.create(this, R.raw.forest_sounds)
        // mediaPlayer?.isLooping = true
    }
    
    private fun startNatureSounds() {
        if (soundEnabled && isWorkPeriod) {
            try {
                mediaPlayer?.let { player ->
                    if (!player.isPlaying) {
                        player.start()
                    }
                }
            } catch (e: Exception) {
                // Handle any media player errors
                e.printStackTrace()
            }
        }
    }
    
    private fun stopNatureSounds() {
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun startForegroundService() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.timer_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val minutes = (remainingTime / 1000) / 60
        val seconds = (remainingTime / 1000) % 60
        val timeString = String.format("%02d:%02d", minutes, seconds)
        
        val contentText = if (isWorkPeriod) {
            getString(R.string.timer_notification_work)
        } else {
            getString(R.string.timer_notification_break)
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("$timeString - ${getString(R.string.timer_notification_title)}")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification() {
        val notification = createNotification()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun broadcastUpdate() {
        val intent = Intent("POMODORO_TIMER_UPDATE").apply {
            putExtra("time_left", remainingTime)
            putExtra("isWorkPeriod", isWorkPeriod)
            putExtra("sessionCount", sessionCount)
        }
        sendBroadcast(intent)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        stopNatureSounds()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}

