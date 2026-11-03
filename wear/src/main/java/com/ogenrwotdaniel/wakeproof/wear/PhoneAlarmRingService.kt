package com.ogenrwotdaniel.wakeproof.wear

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Plays the alarm song on the watch's own speaker while the phone's alarm
 * rings (2026-11-04, user's request: "can the watch use the same song").
 * The song is `res/raw/locked_alarm.*` in THIS module — a copy of the phone's,
 * looked up by name so the build works without it (then the wrist only buzzes).
 *
 * A foreground service, because a listener service lives for a moment and the
 * song has to outlive it. Starting one from the background is restricted, so
 * [start] tries directly and, if refused, goes through an exact alarm
 * ([StartReceiver]) — one of the documented exemptions, and the watch already
 * holds USE_EXACT_ALARM for its pomodoro. The buzz and the notification from
 * [PhoneAlarmMirror] stand either way.
 *
 * Stops when the phone says the ring ended, or after [PHONE_ALARM_BUZZ_MILLIS]
 * if that never arrives. It cannot silence the phone.
 */
class PhoneAlarmRingService : Service() {

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val tone = toneUri(this)
        val notification = PhoneAlarmMirror.notification(this, intent?.getStringExtra(EXTRA_TEXT).orEmpty())
        if (tone == null || notification == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(PhoneAlarmMirror.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (player == null) play(tone)
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, PHONE_ALARM_BUZZ_MILLIS)
        return START_NOT_STICKY
    }

    private fun play(tone: Uri) {
        val mp = MediaPlayer()
        val ok = runCatching {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(this, tone)
            mp.isLooping = true
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
        }.onFailure { Log.w(TAG, "could not play the alarm song", it) }.isSuccess
        if (ok) player = mp else mp.release()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        runCatching { player?.release() }
        player = null
        super.onDestroy()
    }

    /** The exact-alarm route in, for when a direct start from the background is refused. */
    class StartReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runCatching {
                context.startForegroundService(
                    Intent(context, PhoneAlarmRingService::class.java)
                        .putExtra(EXTRA_TEXT, intent.getStringExtra(EXTRA_TEXT))
                )
            }.onFailure { Log.w(TAG, "alarm song refused even from an exact alarm", it) }
        }
    }

    companion object {
        private const val TAG = "PhoneAlarmRing"
        private const val ACTION_STOP = "com.ogenrwotdaniel.wakeproof.wear.STOP_PHONE_ALARM_SONG"
        private const val EXTRA_TEXT = "text"
        private const val TONE_NAME = "locked_alarm"

        fun toneUri(context: Context): Uri? {
            @Suppress("DiscouragedApi")
            val id = context.resources.getIdentifier(TONE_NAME, "raw", context.packageName)
            return if (id == 0) null else Uri.parse("android.resource://${context.packageName}/$id")
        }

        fun start(context: Context, text: String) {
            if (toneUri(context) == null) return
            val intent = Intent(context, PhoneAlarmRingService::class.java).putExtra(EXTRA_TEXT, text)
            val direct = runCatching { context.startForegroundService(intent) }.isSuccess
            if (direct) return
            runCatching {
                val pending = PendingIntent.getBroadcast(
                    context, 0,
                    Intent(context, StartReceiver::class.java).putExtra(EXTRA_TEXT, text),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 100, pending
                )
            }.onFailure { Log.w(TAG, "could not schedule the alarm song", it) }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, PhoneAlarmRingService::class.java).setAction(ACTION_STOP))
            }
            runCatching { context.stopService(Intent(context, PhoneAlarmRingService::class.java)) }
        }
    }
}
