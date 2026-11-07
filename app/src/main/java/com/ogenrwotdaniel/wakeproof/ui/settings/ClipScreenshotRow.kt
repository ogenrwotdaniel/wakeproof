package com.ogenrwotdaniel.wakeproof.ui.settings

/** What the "Send screenshots automatically" row shows. */
data class ClipScreenshotRow(
    val checked: Boolean,
    val switchEnabled: Boolean,
    val subtitle: String,
    /** The way to Android Settings, for when the system dialog no longer can. */
    val settingsLink: Boolean,
    val settingsLinkEnabled: Boolean
)

/**
 * Pure. The subtitle follows what actually happens, so it never claims screenshots
 * are leaving the device, or staying on it, against the facts.
 *
 * @param stored the user's saved choice
 * @param photos READ_MEDIA_IMAGES is held; a "selected photos" grant does not count
 * @param canAsk this build declares the permission (the sideloaded one)
 * @param refused the last request came back without full access
 * @param inSession a focus session is running, which walls Android Settings
 */
fun clipScreenshotRow(
    stored: Boolean,
    clipEnabled: Boolean,
    photos: Boolean,
    canAsk: Boolean,
    refused: Boolean,
    inSession: Boolean
): ClipScreenshotRow {
    val needsSettings = canAsk && clipEnabled && refused && !photos
    return ClipScreenshotRow(
        checked = stored && photos,
        switchEnabled = canAsk && clipEnabled,
        subtitle = when {
            !canAsk -> "Only the sideloaded build can send screenshots."
            !clipEnabled -> "Turn on \"Share the clipboard\" first."
            stored && photos -> "Every new screenshot goes straight to your other devices' clipboards."
            photos -> "Off. Turn this on to send new screenshots to your other devices."
            needsSettings && inSession -> "Needs access to all photos, which Android Settings can give after this session."
            needsSettings -> "Needs access to all photos, which Android Settings can give."
            else -> "Needs access to all photos. Turn this on to allow it."
        },
        settingsLink = needsSettings,
        settingsLinkEnabled = !inSession
    )
}
