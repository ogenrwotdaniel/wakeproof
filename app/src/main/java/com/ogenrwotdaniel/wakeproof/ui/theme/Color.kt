package com.ogenrwotdaniel.wakeproof.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The app's palette: a cold teal against a near-black slate, with amber as the
 * one warm accent.
 *
 * Written out in full — every Material 3 role, not just the four or five that
 * get named in a theme tutorial. A [androidx.compose.material3.darkColorScheme]
 * given only `primary`, `secondary`, `tertiary`, `background` and `surface`
 * keeps Material's **purple** baseline for everything else, and the roles it
 * keeps are the ones cards are made of: `primaryContainer`,
 * `secondaryContainer`, `surfaceContainerLow`. That is why a teal app had
 * lilac cards in it.
 *
 * The dark surfaces are deliberately close to black. WakeProof's own screen is
 * the one a person sees at 05:00 with their eyes still adjusting, and a tinted
 * "dark" surface at 12 % lightness is meaningfully brighter than one at 6 %.
 */

// Teal — the app's own colour, used for anything in progress.
internal val Teal20 = Color(0xFF00382F)
internal val Teal30 = Color(0xFF005046)
internal val Teal40 = Color(0xFF006B5F)
internal val Teal90 = Color(0xFF74F8E5)
internal val Teal80 = Color(0xFF4FD1C5)
internal val Teal10 = Color(0xFF00201C)

// Muted teal-grey — supporting surfaces and the quieter half of the UI.
internal val TealGrey80 = Color(0xFFB0CCC7)
internal val TealGrey40 = Color(0xFF4A6360)
internal val TealGrey20 = Color(0xFF1C3531)
internal val TealGrey30 = Color(0xFF334B47)
internal val TealGrey90 = Color(0xFFCCE8E3)
internal val TealGrey10 = Color(0xFF051F1C)

// Amber — warnings, countdowns running out, the one warm thing on the screen.
internal val Amber80 = Color(0xFFFFD8A8)
internal val Amber40 = Color(0xFF7A5900)
internal val Amber20 = Color(0xFF452B00)
internal val Amber30 = Color(0xFF633F00)
internal val Amber90 = Color(0xFFFFDDB3)
internal val Amber10 = Color(0xFF281800)

// Night: the dark scheme's backgrounds, from the app bar down to a raised card.
internal val NightBackground = Color(0xFF0F1417)
internal val NightSurface = Color(0xFF0F1417)
internal val NightContainerLowest = Color(0xFF0A0F11)
internal val NightContainerLow = Color(0xFF171E21)
internal val NightContainer = Color(0xFF1B2225)
internal val NightContainerHigh = Color(0xFF252C2F)
internal val NightContainerHighest = Color(0xFF30373A)
internal val NightOn = Color(0xFFDEE4E3)
internal val NightVariant = Color(0xFF3F4947)
internal val NightOnVariant = Color(0xFFBEC9C6)
internal val NightOutline = Color(0xFF889390)

// Day: the light scheme, for the rare phone that is forced to it.
internal val DayBackground = Color(0xFFFAFDFB)
internal val DayContainerLow = Color(0xFFF1F5F3)
internal val DayContainer = Color(0xFFEBF0EE)
internal val DayContainerHigh = Color(0xFFE5EAE8)
internal val DayContainerHighest = Color(0xFFDFE4E2)
internal val DayOn = Color(0xFF191C1C)
internal val DayVariant = Color(0xFFDAE5E1)
internal val DayOnVariant = Color(0xFF3F4947)
internal val DayOutline = Color(0xFF6F7977)

// Error, in both directions. Material's defaults are fine hues but the dark
// ones have to be written out too, for the same reason as everything above.
internal val ErrorDark = Color(0xFFFFB4AB)
internal val OnErrorDark = Color(0xFF690005)
internal val ErrorContainerDark = Color(0xFF93000A)
internal val OnErrorContainerDark = Color(0xFFFFDAD6)
internal val ErrorLight = Color(0xFFBA1A1A)
internal val ErrorContainerLight = Color(0xFFFFDAD6)
internal val OnErrorContainerLight = Color(0xFF410002)

/**
 * A break is green, everywhere, in both schemes.
 *
 * Not a Material role: the scheme has no "rest" colour, and borrowing
 * `tertiary` would put amber — which this app uses for *running out* — on the
 * one phase that is the opposite of urgent. Fixed rather than derived, because
 * a colour only means something if it is the same every morning.
 */
val BreakGreen = Color(0xFF81C784)
