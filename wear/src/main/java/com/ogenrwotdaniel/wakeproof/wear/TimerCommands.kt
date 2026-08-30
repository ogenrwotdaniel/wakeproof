package com.ogenrwotdaniel.wakeproof.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Sends timer commands from the watch to the phone.
 *
 * The other half of the contract in the phone's `WearContract` /
 * `PhoneCommandListenerService`. Like `/wakeproof/stats` and `/wakeproof/timer`,
 * these constants are duplicated by hand rather than shared through a module —
 * keep the two copies in step.
 *
 * MessageClient, not DataClient, and for a reason worth not undoing: a DataItem
 * is state, so sending "pause" twice writes the same bytes twice and the second
 * one raises no event at all. A command is an event and has to be delivered
 * every time.
 */
internal object TimerCommands {

    const val PATH = "/wakeproof/command"

    const val START = "timer_start"
    const val PAUSE = "timer_pause"
    const val RESUME = "timer_resume"
    const val STOP = "timer_stop"

    /**
     * Fires [command] at every connected node.
     *
     * Broadcast rather than aimed at one node because a watch has exactly one
     * paired phone in practice, and picking "the right" node from a list would
     * be guesswork with no better answer available. A node that is not running
     * WakeProof simply has nothing listening on this path.
     *
     * Returns whether at least one node accepted it, so the UI can say
     * "phone unreachable" instead of appearing to work. Failures are caught:
     * a Bluetooth drop is an ordinary condition on a watch, not a crash.
     */
    suspend fun send(context: Context, command: String): Boolean {
        val nodes = runCatching {
            Wearable.getNodeClient(context).connectedNodes.await()
        }.getOrNull().orEmpty()
        if (nodes.isEmpty()) return false

        val messageClient = Wearable.getMessageClient(context)
        val payload = command.toByteArray(Charsets.UTF_8)
        var delivered = false
        for (node in nodes) {
            val ok = runCatching {
                messageClient.sendMessage(node.id, PATH, payload).await()
            }.isSuccess
            delivered = delivered || ok
        }
        return delivered
    }
}
