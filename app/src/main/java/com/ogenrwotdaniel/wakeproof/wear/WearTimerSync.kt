package com.ogenrwotdaniel.wakeproof.wear

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

/**
 * Mirrors the live Pomodoro timer onto the Wearable Data Layer so the watch can
 * render a self-ticking Ongoing Activity. Pushed only on transitions (start,
 * pause, resume, phase change, stop) — the watch ticks between them from the
 * absolute [finishAtMillis], so there is no per-second Bluetooth traffic.
 */
@Singleton
class WearTimerSync @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataClient by lazy { Wearable.getDataClient(context) }

    suspend fun push(
        active: Boolean,
        running: Boolean,
        phaseLabel: String,
        remainingSeconds: Int,
        finishAtMillis: Long
    ) {
        val request = PutDataMapRequest.create(WearContract.TIMER_PATH).apply {
            dataMap.putBoolean(WearContract.KEY_TIMER_ACTIVE, active)
            dataMap.putBoolean(WearContract.KEY_TIMER_RUNNING, running)
            dataMap.putString(WearContract.KEY_TIMER_PHASE, phaseLabel)
            dataMap.putInt(WearContract.KEY_TIMER_REMAINING, remainingSeconds)
            dataMap.putLong(WearContract.KEY_TIMER_FINISH_AT, finishAtMillis)
            dataMap.putLong(WearContract.KEY_UPDATED, System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        runCatching { dataClient.putDataItem(request).await() }
    }

    /** Tells the watch to dismiss the timer chip. */
    suspend fun clear() = push(active = false, running = false, phaseLabel = "", remainingSeconds = 0, finishAtMillis = 0)
}
