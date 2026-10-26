package com.ogenrwotdaniel.wakeproof.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.runBlocking

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
 * cloud sync carries it to the tablet. The acknowledgement is the block's id in
 * the next stats push (`WearContract.KEY_SESSION_ACKED`); the watch then deletes
 * the item itself — a delete from the phone would only remove the phone's copy.
 *
 * The phone's copy is deleted only after the insert has committed. If the import
 * throws, the item stays — but NOT redelivered: an item that already synced raises no second
 * event. [WatchBlockImporter.sweep] (app start, every sync pass) picks it up.
 */
@AndroidEntryPoint
class PhoneCommandListenerService : WearableListenerService() {

    @Inject lateinit var importer: WatchBlockImporter

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != WearContract.COMMAND_PATH) return
        when (String(messageEvent.data, Charsets.UTF_8)) {
            WearContract.CMD_TIMER_START -> PomodoroTimerService.start(applicationContext)
            // Pause and stop are refused by the timer while a session runs
            // (nuclear focus); passed on anyway so the rule lives in one place.
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
        runBlocking { importer.importItems(arrived) }
    }
}
