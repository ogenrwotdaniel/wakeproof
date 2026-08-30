package com.ogenrwotdaniel.wakeproof.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService

/**
 * Receives the watch's timer commands and drives [PomodoroTimerService] with
 * them.
 *
 * Until now the Data Layer ran one way: the phone pushed stats and timer state,
 * and the watch was a display. Starting a focus block therefore meant taking the
 * phone out — which is the exact thing a focus block is trying to avoid, and the
 * reason this direction exists at all.
 *
 * The service is deliberately thin. It translates a path and a payload into one
 * of the calls the notification's own action buttons already make, so the watch
 * has no more authority than the phone's notification shade does and there is no
 * second copy of the timer's rules to keep in step.
 *
 * ### Why this can start a foreground service
 *
 * `PomodoroTimerService.start` is a `startForegroundService` call from the
 * background, which Android normally forbids. It is allowed here because the app
 * holds SYSTEM_ALERT_WINDOW (granted for the floating timer bubble), which is one
 * of the documented exemptions — the same grant that lets `AppBlockService`
 * launch `BlockedActivity`. Pause/resume/stop are ordinary `startService` calls
 * against an already-running foreground service and need no exemption.
 *
 * Not `@AndroidEntryPoint`: nothing here is injected. The service's control API
 * is static and takes a Context, which is all this needs.
 */
class PhoneCommandListenerService : WearableListenerService() {

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
}
