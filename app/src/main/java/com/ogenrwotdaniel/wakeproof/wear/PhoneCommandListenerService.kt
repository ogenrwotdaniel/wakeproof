package com.ogenrwotdaniel.wakeproof.wear

import androidx.glance.appwidget.updateAll
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.ogenrwotdaniel.wakeproof.data.CompletionRepository
import com.ogenrwotdaniel.wakeproof.data.sync.SyncWorker
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import com.ogenrwotdaniel.wakeproof.widget.WakeProofWidget
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await

/**
 * The phone's end of everything the watch sends: timer commands, and focus
 * blocks the watch timed on its own.
 *
 * ### Commands
 *
 * Translates a path and a payload into one of the calls the notification's own
 * action buttons already make, so the watch has no more authority than the
 * phone's notification shade does and there is no second copy of the timer's
 * rules to keep in step.
 *
 * `PomodoroTimerService.start` is a `startForegroundService` call from the
 * background, which Android normally forbids. It is allowed here because the app
 * holds SYSTEM_ALERT_WINDOW (granted for the floating timer bubble), which is one
 * of the documented exemptions — the same grant that lets `AppBlockService`
 * launch `BlockedActivity`. Pause/resume/stop are ordinary `startService` calls
 * against an already-running foreground service and need no exemption.
 *
 * ### Focus blocks from the watch
 *
 * The watch runs pomodoros with no phone in reach and leaves each finished
 * block on the Data Layer (`WearContract.SESSION_PATH_PREFIX`). Whenever the two
 * reconnect, the items arrive here; each is recorded as an ordinary VERIFIED
 * FOCUS log — so it counts towards the streak, the forest and the charts, and
 * cloud sync carries it to the tablet — and then its DataItem is DELETED. The
 * delete is the acknowledgement: it syncs back and the watch stops counting the
 * block as waiting.
 *
 * Delete only after the insert has committed. If the import throws, the item
 * stays and is redelivered on the next connection, rather than being
 * acknowledged and lost.
 */
@AndroidEntryPoint
class PhoneCommandListenerService : WearableListenerService() {

    @Inject lateinit var completionRepository: CompletionRepository
    @Inject lateinit var wearStatsSync: WearStatsSync

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != WearContract.COMMAND_PATH) return
        when (String(messageEvent.data, Charsets.UTF_8)) {
            WearContract.CMD_TIMER_START -> PomodoroTimerService.start(applicationContext)
            WearContract.CMD_TIMER_PAUSE -> PomodoroTimerService.pause(applicationContext)
            WearContract.CMD_TIMER_RESUME -> PomodoroTimerService.resume(applicationContext)
            WearContract.CMD_TIMER_STOP -> PomodoroTimerService.stop(applicationContext)
            // An unknown command is a watch running a newer build than the
            // phone. Ignoring it is the right answer: the alternative is
            // crashing the listener that also carries the commands we do know.
            else -> Unit
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        // Copied out before release(): the buffer's items are invalid afterwards.
        val arrived = dataEvents
            .filter { it.type == DataEvent.TYPE_CHANGED }
            .filter { it.dataItem.uri.path?.startsWith(WearContract.SESSION_PATH_PREFIX) == true }
            .map { it.dataItem.uri to DataMapItem.fromDataItem(it.dataItem).dataMap }
        dataEvents.release()
        if (arrived.isEmpty()) return

        // Blocking is fine and intended: this callback already runs on a
        // background thread, and the service may be torn down once it returns —
        // a coroutine launched past that point could be killed between the
        // insert and the acknowledgement.
        runBlocking {
            val today = LocalDate.now().toEpochDay()
            val dataClient = Wearable.getDataClient(applicationContext)
            var imported = 0
            for ((uri, map) in arrived) {
                val block = WatchSessionImport.accept(
                    id = map.getString(WearContract.KEY_SESSION_ID),
                    epochDay = map.getLong(WearContract.KEY_SESSION_EPOCH_DAY),
                    minutes = map.getInt(WearContract.KEY_SESSION_MINUTES),
                    subject = map.getString(WearContract.KEY_SESSION_SUBJECT),
                    todayEpochDay = today
                )
                val recorded = block == null || runCatching {
                    if (completionRepository.importFocusBlock(
                            block.id, block.epochDay, block.minutes, block.subject
                        )
                    ) imported++
                }.isSuccess
                // A rejected item is acknowledged too — redelivering it forever
                // would not make it valid — but a failed insert is not.
                if (recorded) runCatching { dataClient.deleteDataItems(uri).await() }
            }
            if (imported > 0) {
                runCatching { wearStatsSync.push() }
                runCatching { WakeProofWidget().updateAll(applicationContext) }
                SyncWorker.requestOneOff(applicationContext)
            }
        }
    }
}
