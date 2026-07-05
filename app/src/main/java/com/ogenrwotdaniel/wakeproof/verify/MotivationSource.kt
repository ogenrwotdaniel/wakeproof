package com.ogenrwotdaniel.wakeproof.verify

/**
 * Supplies the short motivational line shown after a completed verification.
 * Stubbed until the Kimi API phase binds a real implementation; null means
 * "show nothing extra".
 */
interface MotivationSource {
    suspend fun motivationalMessage(context: String): String?
}

/** No-op implementation used before/without a configured Kimi API key. */
class StubMotivationSource : MotivationSource {
    override suspend fun motivationalMessage(context: String): String? = null
}
