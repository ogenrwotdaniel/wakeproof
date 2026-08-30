package com.ogenrwotdaniel.wakeproof.verify.water

import com.ogenrwotdaniel.wakeproof.data.KimiRepository
import com.ogenrwotdaniel.wakeproof.data.KimiResult
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.WaterVerdict
import com.ogenrwotdaniel.wakeproof.data.remote.ApiKeyStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Compares one before/after pair of bottle photos and reports how much was
 * drunk between them.
 *
 * One interface, two implementations, and the verdict computed by neither of
 * them — see [WaterVerdictRules].
 */
interface WaterObserver {
    suspend fun observe(
        before: ByteArray,
        after: ByteArray,
        bottleCapacityMl: Int
    ): KimiResult<WaterVerdict>
}

/** The original path: a vision model over the wire. Needs a key and a network. */
@Singleton
class CloudWaterObserver @Inject constructor(
    private val kimiRepository: KimiRepository
) : WaterObserver {
    override suspend fun observe(
        before: ByteArray,
        after: ByteArray,
        bottleCapacityMl: Int
    ): KimiResult<WaterVerdict> = kimiRepository.verifyWater(before, after, bottleCapacityMl)
}

/**
 * Classic computer vision, on the device, with no key and no network.
 *
 * The perception is [WaterLineDetector] and [ContainerMatcher]; this class only
 * assembles their answers into the six observations the app already knew how to
 * reason about. Two of those observations never needed perception at all:
 * `refilled` is arithmetic (the level going *up*), and `scaleMlReadable` is OCR,
 * which is [ML Kit Text Recognition]'s job and is not wired up yet — reporting
 * `false` is safe, since the app already falls back to the fill fraction when
 * printed marks are not legible.
 */
@Singleton
class OnDeviceWaterObserver @Inject constructor(
    private val sampler: WaterImageSampler
) : WaterObserver {

    override suspend fun observe(
        before: ByteArray,
        after: ByteArray,
        bottleCapacityMl: Int
    ): KimiResult<WaterVerdict> = withContext(Dispatchers.Default) {
        val first = sampler.sample(before)
        val second = sampler.sample(after)
        if (first == null || second == null) {
            return@withContext KimiResult.ApiError(null, "Could not read the photo.")
        }
        KimiResult.Success(observe(first, second, bottleCapacityMl))
    }

    /** Split out so it is callable from a unit test with no JPEG and no device. */
    internal fun observe(before: RgbImage, after: RgbImage, bottleCapacityMl: Int): WaterVerdict {
        val first = WaterLineDetector.detect(before)
        val second = WaterLineDetector.detect(after)
        val container = ContainerMatcher.match(before, after, first.line, second.line)
        return WaterVerdictRules.decide(
            WaterVerdict(
                sameContainer = container.same,
                levelReadable = first.readable && second.readable,
                beforeFill = first.fill,
                afterFill = second.fill,
                scaleMlReadable = false,
                refilled = second.fill > first.fill + WaterVerdictRules.REFILL_TOLERANCE
            ),
            bottleCapacityMl
        )
    }
}

/**
 * The binding the app actually uses: on-device first, cloud only as a second
 * opinion.
 *
 * This is Phase 3 of `docs/OFFLINE_WATER.md`, and its point is that **the
 * honour-mode hold for a missing key disappears**. Before this, no key meant a
 * 30-second countdown that anyone could lie through — the app's central promise
 * quietly switched off for whoever had not pasted an API key in.
 *
 * The order is chosen around which mistake costs what:
 * - On-device **accepts** → done. No network at 06:00, no latency, no key. A
 *   wrong accept here is a cheat that still had to photograph a bottle twice,
 *   and `docs/OFFLINE_WATER.md` is explicit that the bar is "harder than pressing
 *   snooze", not "tamper-proof".
 * - On-device **does not accept** → ask the cloud, if a key exists. Every
 *   user-visible rejection therefore gets the stronger opinion before it is
 *   shown, which is the failure that actually annoys someone at 06:00.
 * - No key, or the cloud call fails → the on-device answer stands. That is
 *   strictly better than the honour-mode hold it replaces.
 *
 * The detector has never been run against a real photograph, which is why
 * [SettingsRepository.settings] carries a switch to force the cloud path.
 */
@Singleton
class LocalFirstWaterObserver @Inject constructor(
    private val onDevice: OnDeviceWaterObserver,
    private val cloud: CloudWaterObserver,
    private val keyStore: ApiKeyStore,
    private val settingsRepository: SettingsRepository
) : WaterObserver {

    override suspend fun observe(
        before: ByteArray,
        after: ByteArray,
        bottleCapacityMl: Int
    ): KimiResult<WaterVerdict> {
        val offlineAllowed = settingsRepository.settings.first().offlineWaterVerification
        if (!offlineAllowed) return cloud.observe(before, after, bottleCapacityMl)

        val local = onDevice.observe(before, after, bottleCapacityMl)
        val accepted = (local as? KimiResult.Success)?.value?.accepted == true
        if (accepted || !keyStore.hasKey()) return local

        // Second opinion. A cloud failure must not turn a usable local answer
        // into an honour-mode hold, so the local result is what falls through.
        return when (val remote = cloud.observe(before, after, bottleCapacityMl)) {
            is KimiResult.Success -> remote
            else -> local
        }
    }
}
