package com.ogenrwotdaniel.wakeproof.data

/**
 * One device's measured study-app minutes for one day, as published to the
 * other devices on the account (see `data/sync/StudyUsageSync`).
 */
data class DeviceStudyUsage(
    val deviceName: String,
    val epochDay: Long,
    /** Package → whole minutes in the foreground that day. */
    val minutes: Map<String, Long>,
    /** Package → label as the publishing device knew it, for apps not installed here. */
    val labels: Map<String, String> = emptyMap(),
    val updatedAtMillis: Long = 0L
)

/** One study app's minutes today, summed over every device that measured it. */
data class StudyAppTotal(
    val packageName: String,
    val label: String,
    val minutes: Long
)

/**
 * What the study card shows. [measured] is real foreground time on a screen;
 * [creditedMinutes] is focus-timer time no screen accounts for, attributed to
 * [creditedPackage]. Total = measured + credited, which by construction is
 * max(measured, focus) — the timer and the screens never both count one minute.
 */
data class StudyTimeSummary(
    val measured: List<StudyAppTotal>,
    val creditedPackage: String?,
    val creditedMinutes: Long,
    /** Other devices whose numbers went in, for the "measured on" line. */
    val peers: List<DeviceStudyUsage>
) {
    val measuredMinutes: Long get() = measured.sumOf { it.minutes }
    val totalMinutes: Long get() = measuredMinutes + creditedMinutes
}

/**
 * Merges this device's measured study time with the peers' and credits the
 * focus time nothing measured.
 *
 * A focus block timed on the watch, or studied on paper, leaves no usage
 * events on any screen, so measured time alone undercounts. Crediting every
 * focus minute on top would double count the far more common case of a block
 * spent in Samsung Notes on this very device. Crediting only the SHORTFALL —
 * focus minutes beyond what the screens measured — is the one rule that is
 * right at both ends. It cannot tell a minute in Notes outside a block from
 * one inside it (usage carries no session id), so an hour of Notes in the
 * morning and an unmeasured watch block in the evening credit too little,
 * never too much.
 *
 * Peer entries for another day are dropped: a device that has not published
 * since yesterday is not reporting today.
 *
 * @param local this device's minutes per study package.
 * @param focusMinutes today's verified focus minutes, every device included.
 * @param creditPackage the app focus time is spent in, or null for no credit.
 */
fun studyTimeSummary(
    today: Long,
    local: Map<String, Long>,
    localLabels: Map<String, String>,
    peers: List<DeviceStudyUsage>,
    focusMinutes: Long,
    creditPackage: String?
): StudyTimeSummary {
    val current = peers.filter { it.epochDay == today }
    val totals = LinkedHashMap<String, Long>()
    (listOf(local) + current.map { it.minutes }).forEach { device ->
        device.forEach { (pkg, minutes) ->
            if (minutes > 0) totals[pkg] = (totals[pkg] ?: 0L) + minutes
        }
    }
    val labelOf = { pkg: String ->
        localLabels[pkg] ?: current.firstNotNullOfOrNull { it.labels[pkg] } ?: pkg
    }
    val measured = totals.map { (pkg, minutes) -> StudyAppTotal(pkg, labelOf(pkg), minutes) }
        .sortedByDescending { it.minutes }
    val credit = creditPackage?.takeIf { it.isNotBlank() }
    val shortfall = (focusMinutes - measured.sumOf { it.minutes }).coerceAtLeast(0L)
    return StudyTimeSummary(
        measured = measured,
        creditedPackage = credit,
        creditedMinutes = if (credit != null) shortfall else 0L,
        peers = current.filter { peer -> peer.minutes.values.any { it > 0 } }
    )
}
