package com.ogenrwotdaniel.wakeproof.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Teal80,
    onPrimary = Teal20,
    primaryContainer = Teal30,
    onPrimaryContainer = Teal90,
    inversePrimary = Teal40,
    secondary = TealGrey80,
    onSecondary = TealGrey20,
    secondaryContainer = TealGrey30,
    onSecondaryContainer = TealGrey90,
    tertiary = Amber80,
    onTertiary = Amber20,
    tertiaryContainer = Amber30,
    onTertiaryContainer = Amber90,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = NightBackground,
    onBackground = NightOn,
    surface = NightSurface,
    onSurface = NightOn,
    surfaceVariant = NightVariant,
    onSurfaceVariant = NightOnVariant,
    surfaceContainerLowest = NightContainerLowest,
    surfaceContainerLow = NightContainerLow,
    surfaceContainer = NightContainer,
    surfaceContainerHigh = NightContainerHigh,
    surfaceContainerHighest = NightContainerHighest,
    surfaceTint = Teal80,
    outline = NightOutline,
    outlineVariant = NightVariant,
    inverseSurface = NightOn,
    inverseOnSurface = NightBackground,
    scrim = Color.Black
)

private val LightColorScheme = lightColorScheme(
    primary = Teal40,
    onPrimary = Color.White,
    primaryContainer = Teal90,
    onPrimaryContainer = Teal10,
    inversePrimary = Teal80,
    secondary = TealGrey40,
    onSecondary = Color.White,
    secondaryContainer = TealGrey90,
    onSecondaryContainer = TealGrey10,
    tertiary = Amber40,
    onTertiary = Color.White,
    tertiaryContainer = Amber90,
    onTertiaryContainer = Amber10,
    error = ErrorLight,
    onError = Color.White,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = DayBackground,
    onBackground = DayOn,
    surface = DayBackground,
    onSurface = DayOn,
    surfaceVariant = DayVariant,
    onSurfaceVariant = DayOnVariant,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = DayContainerLow,
    surfaceContainer = DayContainer,
    surfaceContainerHigh = DayContainerHigh,
    surfaceContainerHighest = DayContainerHighest,
    surfaceTint = Teal40,
    outline = DayOutline,
    outlineVariant = DayVariant,
    inverseSurface = DayOn,
    inverseOnSurface = DayBackground,
    scrim = Color.Black
)

/**
 * App theme. Dark regardless of the system setting — WakeProof is used at 5 AM
 * and must never flash a bright screen.
 *
 * [dynamicColor] defaults to **off**, which is a change: it used to default on,
 * and so the palette in `Color.kt` was never once drawn. That left the app
 * wearing whatever hue the wallpaper suggested while the Pomodoro ring stayed
 * hardcoded teal — the one element that could not follow along, sitting in the
 * middle of the screen next to colours it had never met. An alarm clock also
 * wants to look the same every morning: its colours mean something (teal is
 * running, green is a break, amber is running out), and a scheme that changes
 * with the wallpaper cannot carry meaning. The parameter stays, so a caller
 * that wants Material You can still ask for it.
 */
@Composable
fun WakeProofTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
