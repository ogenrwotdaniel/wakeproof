package com.ogenrwotdaniel.wakeproof.ui.adaptive

/**
 * How much room the window has, and therefore what shape the shell takes.
 *
 * The app has always drawn one phone-width column, whatever it was running on.
 * On the Galaxy Tab S9 FE+ that means a 1200 dp-wide window rendering a layout
 * designed for 400 dp: navigation that costs a round trip through Home when a
 * permanent list would fit beside the content, and body text stretched to a
 * line length nobody can track back to the left margin.
 *
 * Only the *shell* reads this. Individual screens stay width-agnostic — they
 * are handed a column of readable width and do not know or care how wide the
 * window around it is, which is what stops every screen growing its own
 * breakpoint logic.
 */
enum class PaneMode {
    /** A phone, or a tablet in a small multi-window split. One column, no rail. */
    COMPACT,

    /** Room for permanent navigation beside the content. */
    WIDE
}

/**
 * The breakpoint, as a pure function of window width so it can be tested
 * without a device, a Compose harness or a configuration change.
 *
 * [WIDE_MIN_DP] is Material 3's "medium" boundary. It is deliberately the lower
 * of the two candidate breakpoints (600 rather than 840): the Tab S9 FE+ in
 * PORTRAIT is around 800 dp, and a tablet held upright is exactly the case that
 * most wants the permanent navigation. Waiting for 840 would have given the
 * expanded layout in landscape only, which is the orientation it is needed
 * least.
 *
 * A phone in landscape sits near 800–900 dp too, and gets the rail as well.
 * That is intended rather than tolerated — the rail costs a strip of width and
 * removes a navigation round trip, which is the right trade in the orientation
 * where vertical space is scarcest.
 */
fun paneModeForWidthDp(widthDp: Int): PaneMode =
    if (widthDp >= WIDE_MIN_DP) PaneMode.WIDE else PaneMode.COMPACT

/** Material 3's medium-width boundary. */
const val WIDE_MIN_DP = 600

/**
 * The widest a column of body text is allowed to get, whatever the window does
 * with the space left over.
 *
 * Capping this is the *other* half of tablet support, and the half that is easy
 * to forget once a rail is in place: a settings description running the full
 * width of a 12-inch tablet is one line of 200 characters, which is unreadable
 * for the same reason newspapers use columns. Extra width past this becomes
 * margin, not longer lines.
 */
const val READABLE_MAX_WIDTH_DP = 720
