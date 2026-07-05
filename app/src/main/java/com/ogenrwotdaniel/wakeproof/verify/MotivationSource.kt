package com.ogenrwotdaniel.wakeproof.verify

/**
 * Supplies the short motivational line shown after a completed verification.
 * Implemented by KimiRepository; null (no key, offline, error) means "show
 * nothing extra" — motivation must never block a dismissal.
 */
interface MotivationSource {
    suspend fun motivationalMessage(context: String): String?
}
