package com.ogenrwotdaniel.wakeproof.verify

import android.content.Context
import com.google.mlkit.common.sdkinternal.MlKitContext

/**
 * Makes sure ML Kit is set up before a detector is created (2026-11-04).
 *
 * ML Kit normally initialises itself from a content provider at process start.
 * WakeProof's process can start BEFORE the first unlock (the alarm must ring
 * then), and that provider is not direct-boot aware, so it never runs — the
 * first pushup camera after unlocking then threw "MlKitContext has not been
 * initialized" and crashed the locked 05:30 alarm's verify screen on the S25 Edge.
 * Idempotent; failures are swallowed so the caller's own error path decides.
 */
object MlKitInit {
    fun ensure(context: Context) {
        runCatching { MlKitContext.initializeIfNeeded(context.applicationContext) }
    }
}
