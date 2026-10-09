package com.ogenrwotdaniel.wakeproof.alarm

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.IBinder
import android.os.PowerManager
import android.os.UserManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.ogenrwotdaniel.wakeproof.R
import com.ogenrwotdaniel.wakeproof.data.AlarmRepository
import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service (mediaPlayback) that rings an alarm: loops the system
 * alarm ringtone with USAGE_ALARM audio attributes (respects alarm volume and
 * bypasses DND media muting), vibrates, ramps volume 30% → 100% over 60 s, and
 * posts the full-screen intent notification that launches [AlarmRingingActivity].
 * It stops only when a verification succeeds (or the emergency dismiss fires).
 */
@AndroidEntryPoint
class AlarmRingingService : Service() {

    @Inject lateinit var repository: AlarmRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var rampJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SET_VOLUME) {
            if (player == null) {
                // Not ringing (e.g. control intent raced the stop): nothing to duck.
                stopSelf()
            } else {
                rampJob?.cancel()
                val volume = intent.getFloatExtra(EXTRA_VOLUME, 1f).coerceIn(0f, 1f)
                player?.setVolume(volume, volume)
            }
            return START_NOT_STICKY
        }
        val alarmId = intent?.getLongExtra(EXTRA_ALARM_ID, -1L) ?: -1L
        mission = intent?.getStringExtra(EXTRA_MISSION)

        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(alarmId, "Wake up. Verification required."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        // A second alarm can fire into this already-running service; tear the
        // previous playback down first or its looping MediaPlayer leaks and
        // keeps ringing forever after the service stops.
        stopRinging()
        startRingtone()
        startVibration()
        launchRingingScreenIfDeviceInUse(alarmId)
        scope.launch {
            // Room is unreadable before the first unlock; keep the generic text then.
            if (!getSystemService(UserManager::class.java).isUserUnlocked) return@launch
            repository.getAlarm(alarmId)?.let { alarm ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(alarmId, contentTextFor(alarm)))
            }
        }
        // Redeliver the intent if the system kills us mid-ring, so the same alarm resumes.
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        scope.cancel()
        stopRinging()
        super.onDestroy()
    }

    /**
     * When the device is unlocked and in active use, the system downgrades the
     * full-screen intent to a heads-up banner, so the alarm would only sound in
     * the background. Launching directly is permitted here because the app holds
     * SYSTEM_ALERT_WINDOW (granted for the floating Pomodoro bubble).
     */
    private fun launchRingingScreenIfDeviceInUse(alarmId: Long) {
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (!interactive || locked) return // full-screen intent handles these itself
        try {
            startActivity(
                Intent(this, AlarmRingingActivity::class.java)
                    .putExtra(EXTRA_ALARM_ID, alarmId)
                    .putExtra(EXTRA_MISSION, mission)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            // Overlay permission revoked or launch blocked: heads-up notification remains.
        }
    }

    /** Debug test fires only: see [EXTRA_MISSION]. Null for every real alarm. */
    private var mission: String? = null

    private fun stopRinging() {
        player?.release()
        player = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun startRingtone() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        mediaPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        mediaPlayer.setDataSource(this, uri)
        mediaPlayer.isLooping = true
        mediaPlayer.setOnPreparedListener { prepared ->
            prepared.setVolume(RAMP_START_VOLUME, RAMP_START_VOLUME)
            prepared.start()
            rampJob = scope.launch { rampVolume(prepared) }
        }
        mediaPlayer.prepareAsync()
    }

    private suspend fun rampVolume(mediaPlayer: MediaPlayer) {
        for (step in 1..RAMP_STEPS) {
            delay(RAMP_DURATION_MS / RAMP_STEPS)
            val volume = RAMP_START_VOLUME + (1f - RAMP_START_VOLUME) * step / RAMP_STEPS
            // The player may be released while we sleep; ignore late failures.
            runCatching { mediaPlayer.setVolume(volume, volume) }
        }
    }

    private fun startVibration() {
        val defaultVibrator = getSystemService(VibratorManager::class.java).defaultVibrator
        vibrator = defaultVibrator
        defaultVibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Alarms",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Ringing WakeProof alarms"
            setSound(null, null) // the service plays audio itself
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(alarmId: Long, text: String): Notification {
        val fullScreenIntent = PendingIntent.getActivity(
            this,
            alarmId.toInt(),
            Intent(this, AlarmRingingActivity::class.java)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_MISSION, mission)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("WakeProof alarm")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(fullScreenIntent)
            .setFullScreenIntent(fullScreenIntent, true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun contentTextFor(alarm: Alarm) = when (alarm.type) {
        AlarmType.PUSHUP -> "Pushups to silence it. No shortcuts."
        AlarmType.WATER -> "Drink your water and prove it."
        AlarmType.MATH -> "Solve the sums to silence it."
        AlarmType.CUSTOM -> "Complete your verification."
    }

    companion object {
        private const val ACTION_STOP = "com.ogenrwotdaniel.wakeproof.action.STOP_RINGING"
        private const val ACTION_SET_VOLUME = "com.ogenrwotdaniel.wakeproof.action.SET_VOLUME"
        private const val EXTRA_VOLUME = "com.ogenrwotdaniel.wakeproof.extra.VOLUME"
        private const val CHANNEL_ID = "wakeproof_alarm"
        private const val NOTIFICATION_ID = 1
        private const val RAMP_START_VOLUME = 0.3f
        private const val RAMP_DURATION_MS = 60_000L
        private const val RAMP_STEPS = 30

        fun start(context: Context, alarmId: Long, mission: String? = null) {
            context.startForegroundService(
                Intent(context, AlarmRingingService::class.java)
                    .putExtra(EXTRA_ALARM_ID, alarmId)
                    .putExtra(EXTRA_MISSION, mission)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AlarmRingingService::class.java).setAction(ACTION_STOP)
            )
        }

        /** Ducks/restores the ringtone (cancels the ramp) — used by the water wizard. */
        fun setVolume(context: Context, volume: Float) {
            context.startService(
                Intent(context, AlarmRingingService::class.java)
                    .setAction(ACTION_SET_VOLUME)
                    .putExtra(EXTRA_VOLUME, volume)
            )
        }
    }
}
